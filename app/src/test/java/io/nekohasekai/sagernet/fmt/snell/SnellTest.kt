package io.nekohasekai.sagernet.fmt.snell

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.*
import org.junit.Test

class SnellTest {
    private fun profile(version: Int = 4) = SnellBean().apply {
        initializeDefaultValues()
        serverAddress = "2001:db8::1"
        serverPort = 443
        psk = "secret:/+@&= with space"
        this.version = version
        name = "Snell 测试 #1"
    }

    @Test fun uriPreservesBothVersionsAndCredentials() {
        for (version in listOf(4, 5)) {
            val source = profile(version).apply { obfs = "http"; obfsHost = "example.com"; reuse = true; udp = false }
            val restored = parseSnell(source.toUri())
            assertEquals(source.serverAddress, restored.serverAddress)
            assertEquals(443, restored.serverPort)
            assertEquals(source.psk, restored.psk)
            assertEquals(source.version, restored.version)
            assertEquals(source.name, restored.name)
            assertEquals(source.obfsHost, restored.obfsHost)
            assertTrue(restored.reuse)
            assertFalse(restored.udp)
        }
    }

    @Test fun databaseAndBackupSerializationPreserveVersion() {
        val source = profile(5).apply { reuse = true; udp = false }
        val result = KryoConverters.snellDeserialize(KryoConverters.serialize(source))
        assertEquals(5, result.version)
        assertEquals(source.psk, result.psk)
        assertTrue(result.reuse)
        assertFalse(result.udp)
        assertEquals(source.name, result.name)
    }

    @Test fun externalTrafficUsesMappedLoopbackNotOriginalServer() {
        val bean = profile(5).apply { finalAddress = "127.0.0.1"; finalPort = 12345 }
        val config = JsonParser.parseString(bean.buildSnellConfig(23456)).asJsonObject
        val proxy = config["proxies"].asJsonArray[0].asJsonObject
        assertEquals("127.0.0.1", proxy["server"].asString)
        assertEquals(12345, proxy["port"].asInt)
        assertEquals(5, proxy["version"].asInt)
        assertEquals("127.0.0.1", config["bind-address"].asString)
        assertFalse(config["allow-lan"].asBoolean)
        assertFalse(config["dns"].asJsonObject["enable"].asBoolean)
        assertFalse(config.has("external-controller"))
    }

    @Test fun clashImportPreservesVersionAndObfuscation() {
        val bean = parseSnellClash(mapOf("server" to "example.com", "port" to 443L, "psk" to "test",
            "version" to 5L, "udp" to false, "obfs-opts" to mapOf("mode" to "tls", "host" to "front.example")))
        assertEquals(5, bean.version)
        assertEquals("tls", bean.obfs)
        assertEquals("front.example", bean.obfsHost)
        assertEquals("tcp", bean.network())
    }

    @Test fun rejectsUnsupportedVersionInsteadOfSilentlyDowngrading() {
        assertThrows(IllegalArgumentException::class.java) { parseSnell("snell://key@example.com:443?version=3") }
        assertThrows(IllegalArgumentException::class.java) { profile(6).buildSnellConfig(12345) }
        assertThrows(IllegalArgumentException::class.java) { profile().apply { psk = "" }.toUri() }
    }
}
