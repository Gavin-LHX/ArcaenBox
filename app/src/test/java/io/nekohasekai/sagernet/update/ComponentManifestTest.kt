package io.nekohasekai.sagernet.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class ComponentManifestTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val metadata = """{"schema":1,"contract":1,"component":"naive","channel":"preview","version":"v150.0.7871.63-1","revision":12,"min_sdk":24,"protocols":[],"assets":{"x86_64":{"name":"core-x86_64.so","size":1024,"sha256":"${"a".repeat(64)}"}}}"""
    private fun sign(data: ByteArray) = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(data); sign() }
    private fun verify(text: String = metadata, id: String = "naive", channel: String = "preview") = text.toByteArray().let {
        ComponentManifest.verify(it, sign(it), key.public.encoded, id, channel)
    }
    private fun rejected(reason: String, block: () -> Unit) {
        try { block(); fail("Accepted invalid component update") } catch (e: UpdateException) { assertEquals(reason, e.reason) }
    }
    @Test fun supportsUpstreamVersionSchemesAndPerChannelRevision() {
        assertEquals(12L, verify().revision)
        assertEquals("sing-snell bc5a12 / adapter 1", verify(metadata.replace("v150.0.7871.63-1", "sing-snell bc5a12 / adapter 1")).version)
    }
    @Test fun cannotSwapComponentChannelOrContract() {
        rejected("invalid") { verify(id = "mieru") }
        rejected("invalid") { verify(channel = "stable") }
        rejected("incompatible") { verify(metadata.replace("\"contract\":1", "\"contract\":2")) }
    }
    @Test fun rejectsTamperingAndInvalidAssets() {
        rejected("signature") { ComponentManifest.verify(metadata.replace("\"revision\":12", "\"revision\":999").toByteArray(), sign(metadata.toByteArray()), key.public.encoded, "naive", "preview") }
        for ((from, to) in listOf("core-x86_64.so" to "../core.so", "1024" to "9999999999", "\"revision\":12" to "\"revision\":0")) {
            rejected("invalid") { verify(metadata.replace(from, to)) }
        }
    }
    @Test fun snellCapabilitiesMustBeExplicit() {
        rejected("invalid") { verify(metadata.replace("\"naive\"", "\"snell\""), id = "snell") }
        assertEquals(setOf(4,5,6), verify(metadata.replace("\"naive\"", "\"snell\"").replace("\"protocols\":[]", "\"protocols\":[4,5,6]"), id = "snell").protocols)
    }
}
