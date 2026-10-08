package io.nekohasekai.sagernet.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadedApk(val file: File, val release: GithubRelease, val asset: ReleaseAsset, val sha256: String, val abi: String)

object AppUpdater {
    private fun abi(): String {
        val processAbi = CoreRuntime.abi
        val is64Bit = if (Build.VERSION.SDK_INT >= 23) Process.is64Bit()
            else processAbi in setOf("arm64-v8a", "x86_64")
        return AppUpdatePlan.chooseAbi(Build.SUPPORTED_ABIS.toList(), processAbi, is64Bit)
    }

    /** OkHttp cancellation closes an in-flight read immediately, including metadata requests. */
    private suspend fun <T> request(url: String, consume: (Response, () -> Unit) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = ReleaseService.client().newBuilder().callTimeout(20, TimeUnit.MINUTES).build()
                .newCall(ReleaseService.request(url))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            continuation.context.ensureActive()
                            if (!it.isSuccessful) throw UpdateException(when (it.code) {
                                404 -> "missing"; 403, 429 -> "rate_limit"; else -> "network"
                            })
                            consume(it) { continuation.context.ensureActive() }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    suspend fun download(context: Context, release: GithubRelease, progress: (Int) -> Unit): DownloadedApk =
        withContext(Dispatchers.IO) {
            val abi = abi()
            val asset = AppUpdatePlan.apk(release, abi)
            val sums = AppUpdatePlan.checksumAsset(release)
            val checksumText = request(sums.url) { response, check ->
                val body = response.body ?: throw UpdateException("network")
                if (body.contentLength() > 65536) throw UpdateException("app_invalid")
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 65536) throw UpdateException("app_invalid")
                        output.write(buffer, 0, count)
                    }
                }
                output.toString("UTF-8")
            }
            val hash = AppUpdatePlan.checksum(checksumText, asset.name)
            val directory = File(context.cacheDir, "app-updates")
            if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("app_storage")
            val destination = File(directory, "$hash.apk")
            val result = DownloadedApk(destination, release, asset, hash, abi)
            if (destination.isFile) {
                try { verify(context, result); progress(100); return@withContext result }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { destination.delete() }
            }
            if (directory.usableSpace < asset.size + 16L * 1024 * 1024) throw UpdateException("app_storage")
            val stage = File(directory, "${UUID.randomUUID()}.part.apk")
            try {
                request(asset.url) { response, check ->
                    check()
                    val body = response.body ?: throw UpdateException("network")
                    if (body.contentLength() >= 0 && body.contentLength() != asset.size) throw UpdateException("app_invalid")
                    var received = 0L
                    var lastPercent = -1
                    try {
                        stage.outputStream().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    check()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received > asset.size) throw UpdateException("app_invalid")
                                    output.write(buffer, 0, count)
                                    val percent = (received * 100 / asset.size).toInt()
                                    if (lastPercent != percent) { lastPercent = percent; progress(percent) }
                                }
                            }
                            output.fd.sync()
                        }
                        if (received != asset.size) throw UpdateException("app_invalid")
                        check()
                    } catch (e: Exception) {
                        // The callback can still be unwinding after coroutine cancellation.
                        // Clean here as well so it cannot leave a file created after the caller's finally.
                        stage.delete()
                        throw e
                    }
                }
                coroutineContext.ensureActive()
                verify(context, result.copy(file = stage))
                coroutineContext.ensureActive()
                if (!stage.setReadOnly() || !stage.renameTo(destination)) throw UpdateException("app_storage")
                result
            } finally {
                stage.delete()
            }
        }

    @Suppress("DEPRECATION")
    suspend fun verify(context: Context, apk: DownloadedApk) = withContext(Dispatchers.IO) {
        if (apk.file.length() != apk.asset.size) throw UpdateException("app_invalid")
        val digest = MessageDigest.getInstance("SHA-256")
        apk.file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (hex(digest.digest()) != apk.sha256) throw UpdateException("app_checksum")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(apk.file.path, flags) ?: throw UpdateException("app_invalid")
        val installed = manager.getPackageInfo(context.packageName, flags)
        AppUpdatePlan.verifyIdentity(installed.packageName, versionCode(installed), signers(installed),
            candidate.packageName, versionCode(candidate), candidate.versionName, signers(candidate), apk.release.tag)
        if (Build.VERSION.SDK_INT >= 24 && (candidate.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) {
            throw UpdateException("app_abi")
        }
        ZipFile(apk.file).use {
            if (it.getEntry("lib/${apk.abi}/libgojni.so") == null) throw UpdateException("app_abi")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        } else info.signatures
        return signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
