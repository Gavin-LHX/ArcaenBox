package io.nekohasekai.sagernet.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePlanTest {
    private val tag = "v1.7.2-arcaenbox.1"
    private val hash = "a".repeat(64)
    private fun asset(name: String, size: Long = 64000000) = ReleaseAsset(name, "${ReleaseService.DOWNLOAD_ROOT}$tag/$name", size)
    private fun release(assets: List<ReleaseAsset>) = GithubRelease(tag, false, "", assets)
    private fun rejects(reason: String, action: () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (e: UpdateException) { assertEquals(reason, e.reason) }
    }

    @Test fun keepsProcessAbiOn64BitDevice() {
        assertEquals("armeabi-v7a", AppUpdatePlan.chooseAbi(listOf("arm64-v8a", "armeabi-v7a"), "armeabi-v7a", false))
        assertEquals("arm64-v8a", AppUpdatePlan.chooseAbi(listOf("arm64-v8a", "armeabi-v7a"), "arm64-v8a", true))
        assertEquals("x86_64", AppUpdatePlan.chooseAbi(listOf("x86_64", "x86"), "x86_64", true))
        rejects("app_abi") { AppUpdatePlan.chooseAbi(listOf("arm64-v8a"), "x86_64", true) }
        rejects("app_abi") { AppUpdatePlan.chooseAbi(listOf("arm64-v8a", "armeabi-v7a"), "arm64-v8a", false) }
    }

    @Test fun choosesExactVersionAbiAndOfficialAsset() {
        val apk = asset("ArcaenBox-1.7.2-arcaenbox.1-arm64-v8a.apk")
        val other = asset("ArcaenBox-1.7.2-arcaenbox.1-x86_64.apk")
        assertEquals(apk, AppUpdatePlan.apk(release(listOf(other, apk)), "arm64-v8a"))
        rejects("app_abi") { AppUpdatePlan.apk(release(listOf(other)), "arm64-v8a") }
        rejects("app_abi") { AppUpdatePlan.apk(release(listOf(apk, apk)), "arm64-v8a") }
        rejects("app_invalid") { AppUpdatePlan.apk(release(listOf(apk.copy(size = 0))), "arm64-v8a") }
        rejects("app_invalid") { AppUpdatePlan.apk(release(listOf(apk.copy(size = AppUpdatePlan.MAX_APK_SIZE + 1))), "arm64-v8a") }
        for (url in listOf("http://github.com/Gavin-LHX/ArcaenBox/releases/download/$tag/${apk.name}",
            apk.url.replace("github.com", "github.com.evil.example"), apk.url.replace(tag, "v1.7.1-arcaenbox.1"),
            apk.url + "?redirect=1")) {
            rejects("app_invalid") { AppUpdatePlan.apk(release(listOf(apk.copy(url = url))), "arm64-v8a") }
        }
    }

    @Test fun parsesOnlyUniqueExactChecksum() {
        val name = "ArcaenBox-1.7.2-arcaenbox.1-x86.apk"
        assertEquals(hash, AppUpdatePlan.checksum("\uFEFF${hash.uppercase()}  $name\r\n", name))
        assertEquals(hash, AppUpdatePlan.checksum("$hash *$name\n", name))
        rejects("app_checksum_missing") { AppUpdatePlan.checksum("$hash  other.apk", name) }
        rejects("app_checksum_missing") { AppUpdatePlan.checksum("$hash  $name\n$hash  $name", name) }
        rejects("app_checksum_missing") { AppUpdatePlan.checksum("${"z".repeat(64)}  $name", name) }
        rejects("app_checksum_missing") { AppUpdatePlan.checksumAsset(release(emptyList())) }
        rejects("app_checksum_missing") { AppUpdatePlan.checksumAsset(release(listOf(asset("SHA256SUMS"), asset("SHA256SUMS.txt")))) }
    }

    @Test fun requiresSamePackageNewerCodeReleaseNameAndCurrentSigners() {
        fun verify(pkg: String = "com.arcaenbox.android", code: Long = 296, name: String? = "1.7.2-arcaenbox.1", signers: Set<String> = setOf("current")) =
            AppUpdatePlan.verifyIdentity("com.arcaenbox.android", 292, setOf("current"), pkg, code, name, signers, tag)
        verify()
        rejects("app_package") { verify(pkg = "other.app") }
        rejects("app_version") { verify(code = 292) }
        rejects("app_version") { verify(code = 290) }
        rejects("app_version") { verify(name = "1.7.1-arcaenbox.1") }
        rejects("app_version") { verify(name = null) }
        rejects("app_signature") { verify(signers = setOf("other")) }
        rejects("app_signature") { verify(signers = setOf("current", "other")) }
        rejects("app_signature") { verify(signers = emptySet()) }
    }
}
