package io.nekohasekai.sagernet.fmt.snell

import com.google.gson.Gson
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun SnellBean.validate() {
    require(version == 4 || version == 5) { "Snell version must be 4 or 5" }
    require(serverPort in 1..65535) { "Invalid Snell port" }
    require(psk.isNotBlank()) { "Snell PSK is required" }
    require(obfs in listOf("none", "http", "tls")) { "Unsupported Snell obfuscation: $obfs" }
}

fun parseSnell(link: String): SnellBean {
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid Snell URI")
    return SnellBean().apply {
        initializeDefaultValues()
        serverAddress = url.host
        serverPort = url.port
        psk = url.queryParameter("psk") ?: url.username
        version = url.queryParameter("version")?.let { it.toInt() } ?: 4
        udp = url.queryParameter("udp")?.toBooleanStrict() ?: true
        reuse = url.queryParameter("reuse")?.toBooleanStrict() ?: false
        obfs = url.queryParameter("obfs") ?: "none"
        obfsHost = url.queryParameter("obfs-host") ?: ""
        name = url.fragment ?: ""
        validate()
    }
}

fun SnellBean.toUri(): String {
    validate()
    return linkBuilder().host(serverAddress).port(serverPort).username(psk)
        .addQueryParameter("version", version.toString())
        .addQueryParameter("udp", udp.toString())
        .addQueryParameter("reuse", reuse.toString())
        .apply {
            if (obfs != "none") {
                addQueryParameter("obfs", obfs)
                addQueryParameter("obfs-host", obfsHost)
            }
            if (name.isNotBlank()) fragment(name)
        }.toLink("snell", false)
}

fun parseSnellClash(proxy: Map<String, Any?>): SnellBean = SnellBean().apply {
    initializeDefaultValues()
    serverAddress = proxy["server"]?.toString() ?: error("Snell server is required")
    serverPort = proxy["port"].toString().toInt()
    psk = proxy["psk"]?.toString() ?: ""
    version = proxy["version"]?.toString()?.toInt() ?: 4
    udp = proxy["udp"]?.toString()?.toBooleanStrict() ?: true
    reuse = proxy["reuse"]?.toString()?.toBooleanStrict() ?: false
    name = proxy["name"]?.toString() ?: ""
    (proxy["obfs-opts"] as? Map<*, *>)?.let {
        obfs = it["mode"]?.toString() ?: "none"
        obfsHost = it["host"]?.toString() ?: ""
    }
    validate()
}

/** Only a loopback SOCKS listener; sing-box owns routing, DNS and protected sockets. */
fun SnellBean.buildSnellConfig(port: Int): String {
    validate()
    val proxy = linkedMapOf<String, Any>(
        "name" to "snell", "type" to "snell", "server" to finalAddress,
        "port" to finalPort, "psk" to psk, "version" to version,
        "udp" to udp, "reuse" to reuse
    )
    if (obfs != "none") proxy["obfs-opts"] = mapOf("mode" to obfs, "host" to obfsHost)
    return Gson().toJson(linkedMapOf(
        "socks-port" to port, "allow-lan" to false, "bind-address" to "127.0.0.1",
        "mode" to "rule", "log-level" to "warning", "ipv6" to true,
        "find-process-mode" to "off", "geodata-mode" to false,
        "geo-auto-update" to false, "profile" to mapOf("store-selected" to false),
        "dns" to mapOf("enable" to false), "proxies" to listOf(proxy),
        "rules" to listOf("MATCH,snell")
    ))
}
