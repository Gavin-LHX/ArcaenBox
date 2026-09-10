package io.nekohasekai.sagernet.po0

import org.junit.Assert.*
import org.junit.Test

class Po0ProtocolTest {
    @Test fun acceptsMultipleMachinesAndOptionalSlots() {
        val tokens = Po0Protocol.tokens("pgnfw_a@0，pgnfw_b\npgnfw_c@4;pgnfw_b")
        assertEquals(3, tokens.size)
        assertEquals(0, tokens[0].slot)
        assertNull(tokens[1].slot)
        assertEquals(4, tokens[2].slot)
        assertEquals("https://124.221.69.228/api/firewall/pgnfw_a/add?slot=0", Po0Protocol.url(tokens[0]).toString())
        assertNull(Po0Protocol.url(tokens[1]).query)
        assertFalse(tokens[0].toString().contains("pgnfw_a"))
    }

    @Test fun rejectsMalformedOrConflictingCredentialsWithoutEchoingThem() {
        for (text in listOf("pgnfw_secret@5", "pgnfw_secret@-1", "pgnfw_secret@x", "pgnfw_secret/path",
            "https://example.com", "pgnfw_secret?redirect=evil", "pgnfw_secret@0,pgnfw_secret@1",
            "pgnfw_secret@0,pgnfw_secret", "pgnfw_")) {
            val exception = assertThrows(IllegalArgumentException::class.java) { Po0Protocol.tokens(text) }
            assertFalse(exception.message.orEmpty().contains(text))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Po0Protocol.tokens((1..21).joinToString(",") { "pgnfw_$it" })
        }
    }

    @Test fun comparesValidatedIpv4And24Networks() {
        assertTrue(Po0Protocol.sameNetwork("203.0.113.12", "203.0.113.0/24"))
        assertFalse(Po0Protocol.sameNetwork("203.0.113.12", "203.0.113.13"))
        assertFalse(Po0Protocol.sameNetwork("203.0.113.12", "203.0.114.0/24"))
        for (bad in listOf("203.0.113.999", "203.0.113.1/8", "203.0.113.secret", "::1", "", "secret")) {
            assertFalse(Po0Protocol.sameNetwork(bad, bad))
        }
    }

    private fun response(enabled: Boolean = true, ip: String = "203.0.113.0/24", slot: String = "null") =
        """{"enabled":$enabled,"currentIp":"203.0.113.8","limit":5,"whitelist":[{"ip":"$ip","slot":$slot}]}"""

    @Test fun verifiesServerStateInsteadOfTreatingAny200AsSuccess() {
        assertEquals("success", Po0Protocol.response(200, response(), null).status)
        assertEquals("disabled", Po0Protocol.response(200, response(enabled = false), null).status)
        assertEquals("not_applied", Po0Protocol.response(200, response(ip = "203.0.114.0/24"), null).status)
        assertEquals("invalid_response", Po0Protocol.response(200, "<html>pgnfw_secret</html>", null).status)
        assertEquals("invalid_response", Po0Protocol.response(200, "{}", null).status)
        assertEquals("invalid_response", Po0Protocol.response(200, response(ip = "secret"), null).status)
    }

    @Test fun verifiesRequestedSlotAndSupportsLegacyStringEntries() {
        assertEquals("success", Po0Protocol.response(200, response(slot = "0"), 0).status)
        assertEquals("slot_mismatch", Po0Protocol.response(200, response(slot = "1"), 0).status)
        assertEquals("slot_mismatch", Po0Protocol.response(200, response(), 0).status)
        val old = """{"enabled":true,"currentIp":"203.0.113.0/24","whitelist":["203.0.113.8"]}"""
        assertEquals("success", Po0Protocol.response(200, old, null).status)
    }

    @Test fun distinguishesPermanentAndRetryableFailuresAndRedactsServerErrors() {
        assertEquals("forbidden", Po0Protocol.response(403, "pgnfw_secret", null).status)
        assertFalse(Po0Protocol.response(401, "pgnfw_secret", null).retryable)
        assertTrue(Po0Protocol.response(503, "pgnfw_secret", null).retryable)
        assertTrue(Po0Protocol.response(429, "pgnfw_secret", null).retryable)
        assertFalse(Po0Protocol.response(400, "pgnfw_secret", null).toString().contains("pgnfw_secret"))
    }
}
