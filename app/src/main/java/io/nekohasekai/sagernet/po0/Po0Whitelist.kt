package io.nekohasekai.sagernet.po0

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Po0Whitelist {
    private const val PERIODIC = "arcaenbox.po0.periodic"
    private const val IMMEDIATE = "arcaenbox.po0.immediate"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    // WorkManager CONNECTED checks the default (possibly blocked VPN) network's
    // validation. The worker checks the physical network itself so it can repair
    // a whitelist even when the VPN currently has no working Internet access.
    private val constraints = Constraints.Builder().build()

    @Synchronized
    fun initialize(context: Context, mainProcess: Boolean) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            // The existing listener selects the best underlying network, excluding VPNs.
            var previous: Network? = null
            DefaultNetworkListener.start(this@Po0Whitelist) { network ->
                if (network != previous) {
                    previous = network
                    if (network != null) scope.launch {
                        runCatching { if (Po0Store.state(app).automatic) enqueue(app, manual = false) }
                    }
                }
            }
            if (mainProcess) runCatching { configure(app) }
        }
    }

    fun configure(context: Context) {
        val work = RemoteWorkManager.getInstance(context)
        if (Po0Store.state(context).automatic) {
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Po0Worker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).setInitialDelay(15, TimeUnit.MINUTES).build()).get()
        } else {
            work.cancelUniqueWork(PERIODIC).get()
            work.cancelUniqueWork(IMMEDIATE).get()
        }
    }

    fun enqueue(context: Context, manual: Boolean): java.util.UUID {
        val state = Po0Store.state(context)
        val request = OneTimeWorkRequestBuilder<Po0Worker>()
            .setInputData(workDataOf("manual" to manual, "revision" to state.revision))
            .setConstraints(constraints)
            .setInitialDelay(if (manual) 0 else 2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // A new network supersedes an old in-flight network request; old credentials never replay.
        RemoteWorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.REPLACE, request).get()
        return request.id
    }
}

class Po0Worker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val configuration = try { Po0Store.configuration(applicationContext) } catch (_: Exception) {
            runCatching {
                val state = Po0Store.state(applicationContext)
                Po0Store.record(applicationContext, state.revision, listOf(Po0Result("storage")))
            }
            return@withContext Result.failure()
        }
        val state = configuration.first
        val revision = inputData.getString("revision")
        if (revision != null && revision != state.revision) return@withContext Result.success()
        if (!inputData.getBoolean("manual", false) && !state.automatic) return@withContext Result.success()
        val tokens = try { Po0Protocol.tokens(configuration.second) } catch (_: Exception) {
            Po0Store.record(applicationContext, state.revision, listOf(Po0Result("storage")))
            return@withContext Result.failure()
        }
        if (tokens.isEmpty()) return@withContext Result.success()
        val preferred = withTimeoutOrNull(10_000) {
            DefaultNetworkListener.start(this@Po0Worker) {}
            try { DefaultNetworkListener.get() } finally { DefaultNetworkListener.stop(this@Po0Worker) }
        }
        fun physical(network: Network): Boolean = SagerNet.connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        // Android 7/8 default callbacks may report the VPN instead of its transport.
        val network = preferred?.takeIf(::physical) ?: SagerNet.connectivity.allNetworks
            .filter(::physical).maxByOrNull { candidate ->
                val c = SagerNet.connectivity.getNetworkCapabilities(candidate)!!
                (if (android.os.Build.VERSION.SDK_INT >= 23 && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
                    (if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 3 else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1)
            }
        val caps = network?.let { SagerNet.connectivity.getNetworkCapabilities(it) }
        if (network == null || caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            Po0Store.record(applicationContext, state.revision, tokens.map { Po0Result("no_network") })
            return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns { hostname -> network.getAllByName(hostname).toList() }
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
        try {
            // Bounded concurrency keeps a slow machine from delaying all the other machines.
            val results = tokens.chunked(4).flatMap { batch ->
                coroutineScope { batch.map { token -> async { call(client, token) } }.awaitAll() }
            }
            ensureActive()
            Po0Store.record(applicationContext, state.revision, results)
            if (results.any { it.retryable } && runAttemptCount < 2) Result.retry() else Result.success()
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun call(client: OkHttpClient, token: Po0Token): Po0Result {
        val request = Request.Builder().url(Po0Protocol.url(token))
            .header("Content-Type", "application/json").post(ByteArray(0).toRequestBody()).build()
        return try {
            val payload = suspendCancellableCoroutine<Pair<Int, String>> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                // Read at most 64 KiB, and close the original response as well.
                                val body = it.peekBody(65_537).bytes()
                                val text = if (body.size > 65_536) "" else String(body, Charsets.UTF_8)
                                if (continuation.isActive) continuation.resume(it.code to text)
                            } catch (e: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            }
                        }
                    }
                })
            }
            Po0Protocol.response(payload.first, payload.second, token.slot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: javax.net.ssl.SSLException) {
            Po0Result("tls")
        } catch (_: Exception) {
            // Exception messages may include credential-bearing URLs. Do not log them.
            Po0Result("network")
        }
    }
}
