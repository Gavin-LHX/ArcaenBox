package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ExitIpLookup {
    suspend fun latency(port: Int, endpoint: String, timeoutSeconds: Int): Long = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "No proxy probe available" }
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(timeoutSeconds.coerceIn(2, 60).toLong(), TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        try {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(Request.Builder().url(endpoint).header("Cache-Control", "no-cache").build())
                val start = System.nanoTime()
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            if (it.isSuccessful) continuation.resume(((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1))
                            else continuation.resumeWithException(IOException("HTTP ${it.code}"))
                        }
                    }
                })
            }
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    suspend fun query(port: Int, endpoint: String): String = withContext(Dispatchers.IO) {
        // External protocol processes can begin listening shortly after the TUN
        // reports connected. Every attempt keeps the same explicit proxy; an
        // unavailable node must never reveal the direct connection's public IP.
        for (attempt in 0..2) {
            try {
                return@withContext queryOnce(port, endpoint)
            } catch (e: IOException) {
                if (attempt == 2) throw e
                delay(if (attempt == 0) 750L else 1500L)
            }
        }
        error("Exit IP attempts exhausted")
    }

    private suspend fun queryOnce(port: Int, endpoint: String): String {
        require(port in 1..65535) { "No proxy probe available for this configuration" }
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "HTTPS required" }
        // Explicit SOCKS proxy: connection failure must never fall back to a direct request.
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(12, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).followRedirects(false)
            .retryOnConnectionFailure(false).build()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(Request.Builder().url(url).build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                check(it.isSuccessful) { "HTTP ${it.code}" }
                                val body = it.body ?: error("Empty IP response")
                                require(body.contentLength() <= 32768) { "IP response too large" }
                                val bytes = ByteArray(32769)
                                var length = 0
                                val stream = body.byteStream()
                                while (length < bytes.size) {
                                    val read = stream.read(bytes, length, bytes.size - length)
                                    if (read < 0) break
                                    length += read
                                }
                                require(length <= 32768) { "IP response too large" }
                                PublicIp.describe(String(bytes, 0, length, Charsets.UTF_8))
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                })
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
