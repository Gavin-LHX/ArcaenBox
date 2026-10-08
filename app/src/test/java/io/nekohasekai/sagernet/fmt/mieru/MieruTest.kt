package io.nekohasekai.sagernet.fmt.mieru

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MieruTest {
    // Same official simple-link shape as the reported failure, with synthetic credentials.
    private val link = "mierus://user:pass@203.0.113.1?handshake-mode=HANDSHAKE_NO_WAIT" +
        "&mtu=1400&multiplexing=MULTIPLEXING_OFF&port=18403&profile=default&protocol=TCP"

    @Test fun importsSimpleUriWithQueryPortAndConnectionOptions() {
        val bean = parseMieru(link)
        assertEquals("203.0.113.1", bean.serverAddress)
        assertEquals(18403, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
        assertEquals("TCP", bean.protocol)
        assertEquals(1400, bean.mtu)
        assertEquals("default", bean.name)
        assertEquals("MULTIPLEXING_OFF", bean.multiplexing)
        assertEquals("HANDSHAKE_NO_WAIT", bean.handshakeMode)
    }

    @Test fun clipboardAndSubscriptionParserRecognizesMierusExactlyOnce() = runBlocking {
        val results = parseProxies("  $link\r\n")
        assertEquals(1, results.size)
        assertTrue(results.single() is MieruBean)
        assertEquals(18403, results.single().serverPort)
    }

    @Test fun percentEncodedCredentialsAndIpv6SurviveExportAndImport() {
        val source = parseMieru(link).apply {
            serverAddress = "2001:db8::1"
            username = "user+@:/ 测试"
            password = "secret?&#%+:/ 密码"
            name = "Mieru 测试 #1"
        }
        val restored = parseMieru(source.toUri())
        assertEquals(source.serverAddress, restored.serverAddress)
        assertEquals(source.serverPort, restored.serverPort)
        assertEquals(source.username, restored.username)
        assertEquals(source.password, restored.password)
        assertEquals(source.name, restored.name)
        assertEquals(source.multiplexing, restored.multiplexing)
        assertEquals(source.handshakeMode, restored.handshakeMode)
    }

    @Test fun omittedOptionsRetainCoreDefaults() {
        val bean = parseMieru("mierus://u:p@example.com?profile=demo&port=1234&protocol=UDP")
        assertEquals(1400, bean.mtu)
        assertEquals("MULTIPLEXING_LOW", bean.multiplexing)
        assertEquals("HANDSHAKE_STANDARD", bean.handshakeMode)
        assertFalse(bean.canTCPing())
    }

    @Test fun databaseBackupAndClonePreserveOptionsAndTcpMtu() {
        for (protocol in listOf("TCP", "UDP")) {
            val bean = parseMieru(link).apply { this.protocol = protocol; mtu = 1370 }
            val restored = KryoConverters.mieruDeserialize(KryoConverters.serialize(bean))
            for (copy in listOf(restored, bean.clone())) {
                assertEquals(bean, copy)
                assertEquals(1370, copy.mtu)
                assertEquals(bean.name, copy.name)
                assertEquals("MULTIPLEXING_OFF", copy.multiplexing)
                assertEquals("HANDSHAKE_NO_WAIT", copy.handshakeMode)
            }
        }
    }

    @Test fun legacyVersionZeroProfilesKeepFieldsAndExtraData() {
        for (protocol in listOf("TCP", "UDP")) {
            // Real v0 layout, including AbstractBean's trailing metadata.
            val old = ByteBufferOutput(4096).apply {
                writeInt(0)
                writeString("203.0.113.9")
                writeInt(1234)
                writeString(protocol)
                writeString("legacy-user")
                writeString("legacy-pass")
                if (protocol == "UDP") writeInt(1300)
                writeInt(1)
                writeString("Legacy profile")
                writeString("{\"test\":true}")
                writeString("{}")
            }.toBytes()
            val bean = KryoConverters.mieruDeserialize(old)
            assertEquals("203.0.113.9", bean.serverAddress)
            assertEquals(1234, bean.serverPort)
            assertEquals("legacy-user", bean.username)
            assertEquals("legacy-pass", bean.password)
            assertEquals(protocol, bean.protocol)
            assertEquals(if (protocol == "UDP") 1300 else 1400, bean.mtu)
            assertEquals("Legacy profile", bean.name)
            assertEquals("{\"test\":true}", bean.customOutboundJson)
            assertEquals("{}", bean.customConfigJson)
            assertEquals("MULTIPLEXING_LOW", bean.multiplexing)
            assertEquals("HANDSHAKE_STANDARD", bean.handshakeMode)
            assertEquals(bean, KryoConverters.mieruDeserialize(KryoConverters.serialize(bean)))
        }
    }

    @Test fun nativeConfigUsesProtectedMappingAndImportedConnectionOptions() {
        val bean = parseMieru(link).apply { finalAddress = "127.0.0.1"; finalPort = 23456 }
        val config = JsonParser.parseString(bean.buildMieruConfig(34567)).asJsonObject
        assertEquals(34567, config["socks5Port"].asInt)
        assertFalse(config["socks5ListenLAN"].asBoolean)
        val profile = config["profiles"].asJsonArray.single().asJsonObject
        assertEquals(config["activeProfile"].asString, profile["profileName"].asString)
        assertEquals("user", profile["user"].asJsonObject["name"].asString)
        assertEquals("pass", profile["user"].asJsonObject["password"].asString)
        assertEquals("MULTIPLEXING_OFF", profile["multiplexing"].asJsonObject["level"].asString)
        assertEquals("HANDSHAKE_NO_WAIT", profile["handshakeMode"].asString)
        assertEquals(1400, profile["mtu"].asInt)
        val server = profile["servers"].asJsonArray.single().asJsonObject
        assertEquals("127.0.0.1", server["ipAddress"].asString)
        val binding = server["portBindings"].asJsonArray.single().asJsonObject
        assertEquals(23456, binding["port"].asInt)
        assertEquals("TCP", binding["protocol"].asString)
    }

    @Test fun acceptsCoreSupportedMtuAndDefaultEnums() {
        for (mtu in listOf(0, 1280, 1400, 1500)) {
            val bean = parseMieru(link.replace("mtu=1400", "mtu=$mtu")
                .replace("MULTIPLEXING_OFF", "MULTIPLEXING_DEFAULT")
                .replace("HANDSHAKE_NO_WAIT", "HANDSHAKE_DEFAULT"))
            assertEquals(mtu, bean.mtu)
            assertEquals(bean, parseMieru(bean.toUri()))
        }
    }

    @Test fun rejectsInvalidOrUnsupportedFieldsInsteadOfSilentlyDroppingThem() {
        val invalid = listOf(
            link.replace("port=18403", "port=0"),
            link.replace("port=18403", "port=65536"),
            link.replace("port=18403", "port=18000-19000"),
            link.replace("port=18403&", ""),
            link.replace("protocol=TCP", "protocol=QUIC"),
            link.replace("profile=default&", ""),
            link.replace("mtu=1400", "mtu=1279"),
            link.replace("mtu=1400", "mtu=1501"),
            link.replace("MULTIPLEXING_OFF", "invalid"),
            link.replace("HANDSHAKE_NO_WAIT", "invalid"),
            "$link&port=18404&protocol=TCP",
            "$link&profile=other",
            "$link&traffic-pattern=CAo=",
            link.replace("user:pass@", "user@"),
            link.replace("mierus://", "mieru://")
        )
        for (uri in invalid) assertThrows(RuntimeException::class.java) { parseMieru(uri) }
    }
}
