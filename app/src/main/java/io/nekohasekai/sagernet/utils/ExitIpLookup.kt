package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ExitIpLookup {
    suspend fun query(port: Int, endpoint: String): String {
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
                                require(body.contentLength() <= 4096) { "IP response too large" }
                                val bytes = ByteArray(4097)
                                var length = 0
                                val stream = body.byteStream()
                                while (length < bytes.size) {
                                    val read = stream.read(bytes, length, bytes.size - length)
                                    if (read < 0) break
                                    length += read
                                }
                                require(length <= 4096) { "IP response too large" }
                                PublicIp.parse(String(bytes, 0, length, Charsets.UTF_8))
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
