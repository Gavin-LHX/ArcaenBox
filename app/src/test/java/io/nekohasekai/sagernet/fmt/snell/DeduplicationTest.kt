package io.nekohasekai.sagernet.fmt.snell

import moe.matsuri.nb4a.Protocols
import org.junit.Assert.*
import org.junit.Test

class DeduplicationTest {
    private fun profile() = SnellBean().apply {
        initializeDefaultValues()
        serverAddress = "example.com"; serverPort = 443; psk = "test-credentials"; version = 5
    }
    @Test fun sameEndpointWithDifferentCredentialsOrVersionsIsNotDuplicate() {
        val first = profile()
        val duplicate = first.clone().apply { name = "Another display name" }
        val differentPassword = first.clone().apply { psk = "other-credentials" }
        val differentVersion = first.clone().apply { version = 6 }
        fun key(bean: SnellBean) = Protocols.Deduplication(bean, "Snell")
        assertEquals(key(first), key(duplicate))
        assertEquals(key(first).hashCode(), key(duplicate).hashCode())
        assertNotEquals(key(first), key(differentPassword))
        assertNotEquals(key(first), key(differentVersion))
        assertEquals(3, setOf(key(first), key(duplicate), key(differentPassword), key(differentVersion)).size)
    }
}
