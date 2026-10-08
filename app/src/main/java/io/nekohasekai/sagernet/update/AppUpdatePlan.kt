package io.nekohasekai.sagernet.update

import java.net.URI

/** Release selection and validation kept independent of Android for regression tests. */
object AppUpdatePlan {
    const val MAX_APK_SIZE = 256L * 1024 * 1024
    private val knownAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun chooseAbi(supported: List<String>, processAbi: String, is64Bit: Boolean): String {
        if (processAbi !in knownAbis || processAbi !in supported ||
            (processAbi in setOf("arm64-v8a", "x86_64")) != is64Bit) throw UpdateException("app_abi")
        // A 32-bit app on a 64-bit device must keep using its 32-bit native libraries.
        return processAbi
    }

    fun apk(release: GithubRelease, abi: String): ReleaseAsset {
        if (abi !in knownAbis || ReleaseVersion.parse(release.tag) == null) throw UpdateException("app_abi")
        val name = "ArcaenBox-${release.tag.removePrefix("v")}-$abi.apk"
        return release.assets.singleOrNull { it.name == name }?.also {
            validateUrl(release.tag, it)
            if (it.size !in 1024..MAX_APK_SIZE) throw UpdateException("app_invalid")
        } ?: throw UpdateException("app_abi")
    }

    fun checksumAsset(release: GithubRelease): ReleaseAsset = release.assets
        .singleOrNull { it.name == "SHA256SUMS.txt" || it.name == "SHA256SUMS" }
        ?.also { validateUrl(release.tag, it) } ?: throw UpdateException("app_checksum_missing")

    fun validateUrl(tag: String, asset: ReleaseAsset) {
        val uri = try { URI(asset.url) } catch (_: Exception) { throw UpdateException("app_invalid") }
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || asset.name.contains('/') || asset.name.contains('\\') ||
            uri.path != "/${ReleaseService.REPOSITORY}/releases/download/$tag/${asset.name}") {
            throw UpdateException("app_invalid")
        }
    }

    fun checksum(text: String, name: String): String {
        val rows = text.removePrefix("\uFEFF").lineSequence().mapNotNull { line ->
            Regex("^([0-9a-fA-F]{64}) [ *](.+)$").matchEntire(line.trimEnd('\r'))
        }.filter { it.groupValues[2] == name }.toList()
        return rows.singleOrNull()?.groupValues?.get(1)?.lowercase() ?: throw UpdateException("app_checksum_missing")
    }

    fun verifyIdentity(installedPackage: String, installedCode: Long, installedSigners: Set<String>,
        candidatePackage: String, candidateCode: Long, candidateName: String?, candidateSigners: Set<String>, tag: String) {
        if (installedPackage != candidatePackage) throw UpdateException("app_package")
        val expected = ReleaseVersion.parse(tag) ?: throw UpdateException("app_invalid")
        if (ReleaseVersion.parse(candidateName ?: "") != expected || candidateCode <= installedCode) {
            throw UpdateException("app_version")
        }
        if (installedSigners.isEmpty() || installedSigners != candidateSigners) throw UpdateException("app_signature")
    }
}
