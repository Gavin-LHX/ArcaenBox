package io.nekohasekai.sagernet.fmt.v2ray

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class FinalMaskTest {
    private val mask = """{"tcp":[{"type":"sudoku","settings":{"password":"synthetic+/secret","ascii":"prefer_ascii","paddingMin":0,"paddingMax":3}}]}"""
    private fun link(json: String = mask, transport: String = "tcp") =
        "vless://00000000-0000-4000-8000-000000000001@203.0.113.5:18405?type=$transport&security=none&encryption=none&fm=" +
            URLEncoder.encode(json, "UTF-8") + "#Sudoku"

    @Test fun clipboardImportPreservesMaskAndUsesExternalCore() = runBlocking {
        val beans = parseProxies(link())
        assertEquals(1, beans.size)
        val bean = beans.single() as VMessBean
        assertEquals(parseTcpSudokuFinalMask(mask), parseTcpSudokuFinalMask(bean.finalMask))
        assertTrue(ProxyEntity().apply { putBean(bean) }.needExternal())
        assertTrue(bean.isVLESS)
        assertEquals(18405, bean.serverPort)
        assertEquals("Sudoku", bean.name)
    }

    @Test fun shareCloneAndDatabaseRetainEveryMaskSetting() {
        val full = mask.replace("\"paddingMin\":0", "\"customTables\":[\"xpxvvpvv\"],\"paddingMin\":0")
        val bean = parseV2Ray(link(full)).applyDefaultValues() as VMessBean
        for (copy in listOf(bean.clone(), KryoConverters.vmessDeserialize(KryoConverters.serialize(bean)),
            parseV2Ray(bean.toUriVMessVLESSTrojan(false)).applyDefaultValues())) {
            assertEquals(bean, copy)
            assertEquals(bean.name, copy.name)
        }
    }

    @Test fun rawAliasIsNormalizedWithoutChangingTheMask() {
        val bean = parseV2Ray(link(transport = "raw"))
        assertEquals("tcp", bean.type)
        assertEquals(mask, bean.finalMask)
    }

    @Test fun ordinaryVlessAndVmessStillUseSingBox() {
        for (uri in listOf(
            "vless://00000000-0000-4000-8000-000000000001@203.0.113.5:443?type=tcp&security=reality&sni=example.com&pbk=test&sid=00&flow=xtls-rprx-vision",
            "vmess://00000000-0000-4000-8000-000000000001@203.0.113.5:443?type=ws&security=tls&path=%2Ftest"
        )) {
            val bean = parseV2Ray(uri).applyDefaultValues()
            assertEquals("", bean.finalMask)
            assertFalse(ProxyEntity().apply { putBean(bean) }.needExternal())
            assertEquals(bean, KryoConverters.vmessDeserialize(KryoConverters.serialize(bean)))
        }
    }

    @Test fun legacyVersionFourKeepsTrailingMetadata() {
        val bytes = ByteBufferOutput(4096).apply {
            writeInt(4); writeString("legacy.example"); writeInt(443)
            writeString("00000000-0000-4000-8000-000000000001"); writeString(""); writeInt(-1)
            writeString("tcp"); writeString("none")
            writeBoolean(false); writeString(""); writeInt(0)
            writeBoolean(false); writeBoolean(false); writeInt(0); writeInt(1)
            writeInt(1); writeString("Old VLESS"); writeString("{}"); writeString("{\"old\":true}")
        }.toBytes()
        val bean = KryoConverters.vmessDeserialize(bytes)
        assertEquals("", bean.finalMask)
        assertEquals("Old VLESS", bean.name)
        assertEquals("{}", bean.customOutboundJson)
        assertEquals("{\"old\":true}", bean.customConfigJson)
        assertFalse(ProxyEntity().apply { putBean(bean) }.needExternal())
    }

    @Test fun configUsesProtectedLoopbackMappingAndCarriesUdp() {
        val bean = parseV2Ray(link()).apply { finalAddress = "127.0.0.1"; finalPort = 23456 }
        val config = JsonParser.parseString(bean.buildXrayFinalMaskConfig(34567)).asJsonObject
        val inbound = config["inbounds"].asJsonArray.single().asJsonObject
        assertEquals("127.0.0.1", inbound["listen"].asString)
        assertEquals(34567, inbound["port"].asInt)
        assertTrue(inbound["settings"].asJsonObject["udp"].asBoolean)
        val outbound = config["outbounds"].asJsonArray.single().asJsonObject
        val remote = outbound["settings"].asJsonObject["vnext"].asJsonArray.single().asJsonObject
        assertEquals("127.0.0.1", remote["address"].asString)
        assertEquals(23456, remote["port"].asInt)
        assertEquals(parseTcpSudokuFinalMask(mask), outbound["streamSettings"].asJsonObject["finalmask"])
        assertEquals("none", remote["users"].asJsonArray.single().asJsonObject["encryption"].asString)
    }

    @Test fun tlsUsesOriginalServerNameBehindMapping() {
        val bean = parseV2Ray(link()).apply {
            serverAddress = "node.example"; finalAddress = "127.0.0.1"; finalPort = 23456
            security = "tls"; sni = ""; alpn = "h2,http/1.1"
        }
        fun stream() = JsonParser.parseString(bean.buildXrayFinalMaskConfig(34567)).asJsonObject["outbounds"]
            .asJsonArray.single().asJsonObject["streamSettings"].asJsonObject
        assertEquals("node.example", stream()["tlsSettings"].asJsonObject["serverName"].asString)
        bean.realityPubKey = "test"; bean.realityShortId = "00"
        assertEquals("reality", stream()["security"].asString)
        assertEquals("node.example", stream()["realitySettings"].asJsonObject["serverName"].asString)
    }

    @Test fun rejectsUnsupportedMasksAndTransportsInsteadOfDowngrading() {
        val invalid = listOf("", "{}", "null", "[]", "not json", mask.replace("sudoku", "noise"),
            mask.replace("prefer_ascii", "invalid"), mask.replace("paddingMin\":0", "paddingMin\":4"),
            mask.replace("paddingMax\":3", "paddingMax\":101"), mask.replace("paddingMax\":3", "paddingMax\":1.5"),
            mask.replace("paddingMax\":3", "paddingMax\":4294967296"), mask.replace("\"password\"", "\"unsupported\""),
            mask.replace("\"tcp\"", "\"udp\""))
        for (json in invalid) assertThrows(IllegalArgumentException::class.java) { parseV2Ray(link(json)) }
        assertThrows(IllegalArgumentException::class.java) { parseV2Ray(link(transport = "ws")) }
        assertThrows(IllegalArgumentException::class.java) { parseV2Ray(link().replace("vless://", "vmess://")) }
        assertThrows(IllegalArgumentException::class.java) { parseV2Ray(link().replace("#Sudoku", "&fm=%7B%7D")) }
        assertThrows(IllegalArgumentException::class.java) { parseV2Ray(link().replace("encryption=none", "encryption=unsupported")) }
        val bean = parseV2Ray(link()).apply { enableMux = true }
        assertThrows(IllegalArgumentException::class.java) { bean.buildXrayFinalMaskConfig(34567) }
    }
}
