package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.NodeTestResult
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ExitProbeConfig
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.mkPort
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class NodeTestKind { TCP, URL, UDP, SPEED }

/** Each test owns a core and a private inbound; user routes and the active VPN cannot select a different node. */
class NodeTestSession(profile: ProxyEntity) : BoxInstance(profile) {
    private val port by lazy { mkPort() }

    override fun buildConfig() {
        config = buildConfig(profile, forTest = true)
        val target = ExitProbeConfig.proxyTag(config.config)
            ?: throw IOException("This configuration has no proxy outbound to test")
        val root = JsonParser.parseString(ExitProbeConfig.apply(config.config, port, target)).asJsonObject
        // Test profiles must not open the original custom configuration's TUN or fixed listeners.
        val probe = root.getAsJsonArray("inbounds").last().asJsonObject
        probe.addProperty("type", "mixed")
        if (profile.type == ProxyEntity.TYPE_CONFIG && profile.configBean?.type == 0)
            root.add("inbounds", com.google.gson.JsonArray().apply { add(probe) })
        root.remove("experimental") // never share cache files or API listeners with the running VPN
        config.config = root.toString()
    }

    suspend fun measure(kind: NodeTestKind): NodeTestResult = withContext(Dispatchers.IO) {
        val timeout = DataStore.nodeTestTimeout.coerceIn(2, 60) * 1000
        if (kind == NodeTestKind.TCP) {
            if (!profile.requireBean().canTCPing()) throw UnsupportedOperationException("This protocol does not expose a TCP server port")
            var socket: Socket? = null
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val elapsed = cancellableIO({ cancelled.set(true); socket?.close() }) {
                val bean = profile.requireBean()
                val network = SagerNet.underlyingNetwork
                val addresses = network?.getAllByName(bean.serverAddress) ?: InetAddress.getAllByName(bean.serverAddress)
                socket = network?.socketFactory?.createSocket() ?: Socket(Proxy.NO_PROXY)
                if (cancelled.get()) { socket!!.close(); throw InterruptedIOException("Cancelled") }
                val start = System.nanoTime()
                socket!!.use { it.connect(InetSocketAddress(addresses.first(), bean.serverPort), timeout) }
                ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            }
            return@withContext NodeTestResult(profile.id, kind.name, elapsed, System.currentTimeMillis())
        }
        coroutineScope {
            val owner = this
            processes = GuardedProcessPool { cause -> owner.cancel(CancellationException("Test core stopped", cause)) }
            try {
                init()
                ensureActive()
                this@NodeTestSession.launch()
                if (processes.processCount > 0) delay(750)
                ensureActive()
                if (kind == NodeTestKind.UDP) {
                    val probe = SocksUdpProbe()
                    val elapsed = cancellableIO({ probe.close() }) {
                        probe.measure(port, DataStore.udpTestHost.trim(), DataStore.udpTestPort.coerceIn(1, 65535), timeout)
                    }
                    NodeTestResult(profile.id, kind.name, elapsed.toLong(), System.currentTimeMillis())
                } else {
                    httpTest(kind, timeout)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    close()
                    processes.coroutineContext[Job]?.join()
                }
            }
        }
    }

    private suspend fun httpTest(kind: NodeTestKind, timeout: Int): NodeTestResult {
        val speed = kind == NodeTestKind.SPEED
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val request = Request.Builder().url(if (speed) DataStore.speedTestURL else DataStore.connectionTestURL)
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        try {
            return cancellableIO({ call.cancel() }) {
                val start = System.nanoTime()
                var received = 0L
                val limit = DataStore.speedTestLimitMiB.coerceIn(1, 256) * 1024L * 1024L
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (!speed) return@cancellableIO NodeTestResult(profile.id, kind.name,
                        ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1), System.currentTimeMillis())
                    val body = response.body ?: throw IOException("Empty download body")
                    if (body.contentType()?.subtype?.contains("html") == true) throw IOException("The speed test URL returned an HTML page")
                    val buffer = ByteArray(32 * 1024)
                    try {
                        body.byteStream().use { input ->
                            while (received < limit && (System.nanoTime() - start) / 1_000_000 < timeout) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - received).toInt())
                                if (count < 0) break
                                received += count
                            }
                        }
                    } catch (e: InterruptedIOException) {
                        // A time-bounded download reports the bytes actually received; other failures remain failures.
                        if (received < 32768 || (System.nanoTime() - start) / 1_000_000 < timeout - 100) throw e
                    }
                }
                if (received < 32768) throw IOException("The speed test response is too small (less than 32 KiB)")
                val nanos = (System.nanoTime() - start).coerceAtLeast(1)
                NodeTestResult(profile.id, kind.name, (received * 1_000_000_000.0 / nanos).toLong(),
                    System.currentTimeMillis(), transferred = received)
            }
        } finally {
            call.cancel()
            // TLS shutdown must run on IO, including cancellation and timeout paths.
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun <T> cancellableIO(cancel: () -> Unit, block: () -> T): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val worker = launch(Dispatchers.IO) {
                try {
                    val value = block()
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { runCatching(cancel); worker.cancel() }
        }
    }
}
