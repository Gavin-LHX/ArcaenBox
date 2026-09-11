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
        assertThrows(IllegalArgumentException::class.java) { profile(7).buildSnellConfig(12345) }
        assertThrows(IllegalArgumentException::class.java) { profile().apply { psk = "" }.toUri() }
    }

    @Test fun snellSixPreservesModeAcrossUriDatabaseAndConfig() {
        for (mode in listOf("default", "unshaped", "unsafe-raw")) {
            val source = profile(6).apply { this.mode = mode; finalAddress = "127.0.0.1"; finalPort = 12345 }
            assertEquals(mode, parseSnell(source.toUri()).mode)
            assertEquals(mode, KryoConverters.snellDeserialize(KryoConverters.serialize(source)).mode)
            val proxy = JsonParser.parseString(source.buildSnellConfig(23456)).asJsonObject["proxies"].asJsonArray[0].asJsonObject
            assertEquals(6, proxy["version"].asInt)
            assertEquals(mode, proxy["snell-mode"].asString)
        }
        assertThrows(IllegalArgumentException::class.java) { profile(6).apply { psk = "short" }.validate() }
        assertThrows(IllegalArgumentException::class.java) { profile(6).apply { obfs = "http" }.validate() }
        assertThrows(IllegalArgumentException::class.java) { profile(5).apply { mode = "unshaped" }.validate() }
    }

    @Test fun readsVersionZeroProfilesWithoutLosingExistingFields() {
        val source = profile(5).apply { obfs = "http"; obfsHost = "front.example"; reuse = true }
        val buffer = com.esotericsoftware.kryo.io.ByteBufferOutput(4096)
        source.serialize(buffer)
        val modeBytes = com.esotericsoftware.kryo.io.ByteBufferOutput(100).apply { writeString("default") }.toBytes()
        val old = buffer.toBytes().dropLast(modeBytes.size).toByteArray()
        for (i in 0..3) old[i] = 0
        val restored = SnellBean().apply { deserialize(com.esotericsoftware.kryo.io.ByteBufferInput(old)); initializeDefaultValues() }
        assertEquals("default", restored.mode)
        assertEquals(5, restored.version)
        assertEquals(source.psk, restored.psk)
        assertEquals(source.obfsHost, restored.obfsHost)
        assertTrue(restored.reuse)
    }
}
