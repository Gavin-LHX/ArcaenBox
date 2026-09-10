package io.nekohasekai.sagernet.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature

class CoreManifestTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val metadata = """{"schema":1,"channel":"stable","version":"1.14.0","revision":1,"bridge":"test-bridge","assets":{"arm64-v8a":{"name":"core-arm64-v8a.so","size":1024,"sha256":"${"a".repeat(64)}"}}}""".toByteArray()
    private fun sign(data: ByteArray)=Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(data); sign() }
    private fun verify(data: ByteArray=metadata, sig: ByteArray=sign(data), bridge: String="test-bridge", channel: String="stable") = CoreManifest.verify(data,sig,key.public.encoded,bridge,channel)
    @Test fun acceptsSignedCompatibleManifest() {
        val m = verify(); assertEquals("1.14.0",m.version); assertEquals(1024L,m.assets["arm64-v8a"]!!.size)
    }
    private fun rejected(reason: String, run: () -> Unit) { try { run(); fail("Update accepted") } catch(e: UpdateException) { assertEquals(reason,e.reason) } }
    @Test fun rejectsModifiedMetadataAndSignature() {
        val sig=sign(metadata)
        val changed=metadata.toString(Charsets.UTF_8).replace("1.14.0","9.99.0").toByteArray()
        rejected("signature") { verify(changed,sig) }
        sig[0]=(sig[0].toInt() xor 1).toByte()
        rejected("signature") { verify(sig=sig) }
    }
    @Test fun rejectsDifferentKeyBridgeAndChannel() {
        val other=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        rejected("signature") { CoreManifest.verify(metadata,sign(metadata),other.public.encoded,"test-bridge","stable") }
        rejected("incompatible") { verify(bridge="other-bridge") }
        rejected("invalid") { verify(channel="preview") }
    }
    @Test fun rejectsUnsafeFileNamesAndOversizedLibraries() {
        for (pair in listOf("core-arm64-v8a.so" to "../core.so","1024" to "999999999999","1.14.0" to "1.15.0-alpha.2")) {
            val changed=metadata.toString(Charsets.UTF_8).replace(pair.first,pair.second).toByteArray()
            rejected("invalid") { verify(changed) }
        }
    }
    @Test fun verifiesDownloadedBytesAndSize() {
        val f=File.createTempFile("core-update",".so")
        try {
            f.writeBytes(ByteArray(1024){it.toByte()})
            val asset=CoreAsset("core.so",f.length(),CoreManifest.sha256(f))
            CoreManifest.checkBinary(f,asset)
            f.writeBytes(ByteArray(1024))
            rejected("checksum") { CoreManifest.checkBinary(f,asset) }
            f.writeBytes(ByteArray(1023))
            rejected("checksum") { CoreManifest.checkBinary(f,asset) }
        } finally { f.delete() }
    }
}
