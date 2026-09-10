package io.nekohasekai.sagernet.update

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class UpdateException(val reason: String) : IOException(reason)
data class ReleaseAsset(val name: String, val url: String)
data class GithubRelease(val tag: String, val preview: Boolean, val url: String, val assets: List<ReleaseAsset>)

object ReleaseService {
    const val REPOSITORY = "Gavin-LHX/ArcaenBox"
    const val DOWNLOAD_ROOT = "https://github.com/$REPOSITORY/releases/download/"
    private val direct = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followSslRedirects(false).build()

    private fun client(): OkHttpClient = if (DataStore.serviceState.connected) direct.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort))).build() else direct

    private fun request(url: String): Request {
        require(url.startsWith("https://api.github.com/repos/$REPOSITORY/releases") || url.startsWith(DOWNLOAD_ROOT))
        return Request.Builder().url(url).header("User-Agent", "ArcaenBox-Updater")
            .header("Accept", if (url.startsWith("https://api.")) "application/vnd.github+json" else "application/octet-stream").build()
    }

    suspend fun fetch(url: String, limit: Long = 2 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val clients = listOf(client(), direct).distinct()
        var failure: IOException? = null
        for (client in clients) {
            coroutineContext.ensureActive()
            try {
                client.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) throw UpdateException(when (response.code) { 404 -> "missing"; 403,429 -> "rate_limit"; else -> "server" })
                    val body = response.body ?: throw UpdateException("server")
                    if (body.contentLength() > limit) throw UpdateException("invalid")
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            if (output.size().toLong() + n > limit) throw UpdateException("invalid")
                            output.write(buffer,0,n)
                        }
                    }
                    return@withContext output.toByteArray()
                }
            } catch (e: UpdateException) { throw e } catch (e: IOException) { failure = e }
        }
        throw failure ?: UpdateException("network")
    }

    suspend fun releases(): List<GithubRelease> {
        val all = mutableListOf<GithubRelease>()
        for (page in 1..10) {
            val list = JsonParser.parseString(fetch("https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page").toString(Charsets.UTF_8)).asJsonArray
            list.forEach { raw ->
                val r = raw.asJsonObject
                if (r.get("draft").asBoolean) return@forEach
                val tag = r.get("tag_name").asString
                val url = r.get("html_url").asString
                if (!url.startsWith("https://github.com/$REPOSITORY/releases/tag/")) return@forEach
                val assets = r.getAsJsonArray("assets").mapNotNull { a ->
                    val item = a.asJsonObject; val link = item.get("browser_download_url").asString
                    if (!link.startsWith(DOWNLOAD_ROOT)) null else ReleaseAsset(item.get("name").asString, link)
                }
                all += GithubRelease(tag,r.get("prerelease").asBoolean,url,assets)
            }
            if (list.size() < 100) break
        }
        return all
    }

    fun applicationRelease(releases: List<GithubRelease>, preview: Boolean): GithubRelease? = releases
        .filter { it.preview == preview && it.tag.startsWith("v") && it.assets.any { a -> a.name.endsWith(".apk") } }
        .mapNotNull { r -> ReleaseVersion.parse(r.tag)?.let { it to r } }
        .maxByOrNull { it.first }?.second

    suspend fun download(url: String, file: File, expectedSize: Long, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (expectedSize !in 1024..100L*1024*1024) throw UpdateException("invalid")
        client().newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException(if (response.code == 404) "missing" else "network")
            val body = response.body ?: throw UpdateException("network")
            if (body.contentLength() > expectedSize) throw UpdateException("invalid")
            var total = 0L; var last = -1
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > expectedSize) throw UpdateException("invalid")
                        out.write(buffer,0,n)
                        val percent = (total*100/expectedSize).toInt()
                        if (percent != last) { last = percent; progress(percent) }
                    }
                }
                out.fd.sync()
            }
            if (total != expectedSize) throw UpdateException("invalid")
        }
    }
}
