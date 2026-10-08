package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Keep the wire JSON intact, including optional Sudoku tables and padding. */
fun parseTcpSudokuFinalMask(text: String): JsonObject {
    require(text.toByteArray(Charsets.UTF_8).size <= 16384) { "FinalMask configuration is too large" }
    try {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.keySet() == setOf("tcp")) { "Only FinalMask Sudoku over TCP is supported" }
        val masks = root["tcp"].asJsonArray
        require(masks.size() == 1) { "Use one Sudoku TCP mask" }
        val mask = masks.single().asJsonObject
        require(mask.keySet() == setOf("type", "settings") && mask["type"].asString == "sudoku") {
            "Only the Sudoku TCP mask is supported"
        }
        val settings = mask["settings"].asJsonObject
        require(settings.keySet().all { it in setOf("password", "ascii", "customTable", "customTables", "paddingMin", "paddingMax") }) {
            "Unsupported Sudoku setting"
        }
        fun string(key: String): String? = settings[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Sudoku $key must be a string" }
            it.asString
        }
        require(!string("password").isNullOrEmpty()) { "Sudoku password is required" }
        require(string("ascii") in listOf(null, "", "prefer_ascii", "prefer_entropy")) { "Invalid Sudoku ASCII mode" }
        string("customTable")
        settings["customTables"]?.let { tables ->
            require(tables.isJsonArray && tables.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) {
                "Sudoku customTables must be an array of strings"
            }
        }
        fun padding(key: String): Int = settings[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("[0-9]+"))) {
                "Sudoku $key must be an integer"
            }
            val value = it.asString.toIntOrNull()
            require(value != null && value in 0..100) { "Sudoku padding must be between 0 and 100" }
            value
        } ?: 0
        require(padding("paddingMin") <= padding("paddingMax")) { "Sudoku paddingMin exceeds paddingMax" }
        return root
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Invalid FinalMask Sudoku JSON", error)
    }
}

fun StandardV2RayBean.usesFinalMask(): Boolean = !finalMask.isNullOrBlank()

fun StandardV2RayBean.validateFinalMask() {
    if (!usesFinalMask()) return
    require(isVLESS && type == "tcp") { "FinalMask Sudoku requires VLESS over TCP" }
    require(security in listOf("none", "tls")) { "Unsupported Sudoku transport security" }
    require(!enableECH && !enableMux && packetEncoding == 0) {
        "FinalMask Sudoku requires ECH and multiplexing off, with default packet encoding"
    }
    require(encryption in listOf("", "none", "xtls-rprx-vision")) { "Unsupported VLESS flow" }
    require(encryption != "xtls-rprx-vision" || security == "tls") { "XTLS Vision requires TLS or REALITY" }
    parseTcpSudokuFinalMask(finalMask)
}

/** Xray only handles this node. sing-box owns VPN protection, routing and DNS. */
fun StandardV2RayBean.buildXrayFinalMaskConfig(port: Int): String {
    validateFinalMask()
    require(usesFinalMask()) { "Missing FinalMask Sudoku configuration" }
    val user = linkedMapOf<String, Any>("id" to uuid, "encryption" to "none")
    if (encryption !in listOf("", "none")) user["flow"] = encryption
    val stream = linkedMapOf<String, Any>(
        "network" to "tcp", "security" to security,
        "finalmask" to parseTcpSudokuFinalMask(finalMask)
    )
    if (security == "tls") {
        val serverName = sni.ifBlank { serverAddress }
        if (realityPubKey.isNotBlank()) {
            stream["security"] = "reality"
            stream["realitySettings"] = mapOf(
                "serverName" to serverName, "publicKey" to realityPubKey,
                "shortId" to realityShortId, "fingerprint" to utlsFingerprint.ifBlank { "chrome" }
            )
        } else {
            stream["tlsSettings"] = linkedMapOf<String, Any>(
                "serverName" to serverName, "allowInsecure" to allowInsecure
            ).apply {
                if (alpn.isNotBlank()) put("alpn", alpn.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() })
                if (utlsFingerprint.isNotBlank()) put("fingerprint", utlsFingerprint)
                if (certificates.isNotBlank()) put("certificates", listOf(mapOf(
                    "usage" to "verify", "certificate" to certificates.lines()
                )))
            }
        }
    }
    return Gson().toJson(linkedMapOf(
        "log" to mapOf("loglevel" to "warning"),
        "inbounds" to listOf(mapOf(
            "tag" to "local", "listen" to "127.0.0.1", "port" to port, "protocol" to "socks",
            "settings" to mapOf("auth" to "noauth", "udp" to true, "ip" to "127.0.0.1")
        )),
        "outbounds" to listOf(mapOf(
            "tag" to "proxy", "protocol" to "vless",
            "settings" to mapOf("vnext" to listOf(mapOf(
                "address" to finalAddress, "port" to finalPort, "users" to listOf(user)
            ))), "streamSettings" to stream
        ))
    ))
}
