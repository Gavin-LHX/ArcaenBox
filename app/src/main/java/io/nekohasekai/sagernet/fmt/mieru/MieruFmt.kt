/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import com.google.gson.Gson
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun MieruBean.validate() {
    require(serverAddress.isNotBlank()) { "Mieru server is required" }
    require(serverPort in 1..65535) { "Invalid Mieru port" }
    require(username.isNotEmpty() && password.isNotEmpty()) { "Mieru username and password are required" }
    require(username.toByteArray(Charsets.UTF_8).size <= 64 && password.toByteArray(Charsets.UTF_8).size <= 64) {
        "Mieru username and password must each be at most 64 UTF-8 bytes"
    }
    require(protocol in listOf("TCP", "UDP")) { "Invalid Mieru protocol" }
    require(mtu == 0 || mtu in 1280..1500) { "Mieru MTU must be 0 (default) or between 1280 and 1500" }
    require(multiplexing in listOf("MULTIPLEXING_DEFAULT", "MULTIPLEXING_OFF", "MULTIPLEXING_LOW", "MULTIPLEXING_MIDDLE", "MULTIPLEXING_HIGH")) {
        "Invalid Mieru multiplexing level"
    }
    require(handshakeMode in listOf("HANDSHAKE_DEFAULT", "HANDSHAKE_STANDARD", "HANDSHAKE_NO_WAIT")) { "Invalid Mieru handshake mode" }
}

/** Official simple sharing URI. Port lives in the query, not the authority. */
fun parseMieru(link: String): MieruBean {
    require(link.startsWith("mierus://")) { "Invalid Mieru simple URI" }
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid Mieru simple URI")
    val supported = setOf("profile", "port", "protocol", "mtu", "multiplexing", "handshake-mode")
    require(url.queryParameterNames.all { it in supported }) { "Unsupported Mieru URI parameter" }
    // A profile currently has one mapped server port. Do not discard extra bindings
    // or traffic-pattern data and silently change how an imported node connects.
    require(url.queryParameterNames.all { url.queryParameterValues(it).size == 1 }) {
        "Multiple Mieru port bindings or duplicate parameters are not supported"
    }
    val profile = url.queryParameter("profile")
    require(!profile.isNullOrBlank()) { "Mieru profile is required" }
    return MieruBean().apply {
        serverAddress = url.host
        serverPort = url.queryParameter("port")?.toIntOrNull() ?: error("A single Mieru port is required")
        protocol = url.queryParameter("protocol") ?: error("Mieru protocol is required")
        username = url.username
        password = url.password
        name = url.fragment ?: profile
        mtu = url.queryParameter("mtu")?.let { it.toIntOrNull() ?: error("Invalid Mieru MTU") }
        multiplexing = url.queryParameter("multiplexing")
        handshakeMode = url.queryParameter("handshake-mode")
        initializeDefaultValues()
        validate()
    }
}

fun MieruBean.toUri(): String {
    validate()
    return linkBuilder().host(serverAddress).username(username).password(password)
        .addQueryParameter("profile", name.ifBlank { "default" })
        .addQueryParameter("port", serverPort.toString())
        .addQueryParameter("protocol", protocol)
        .addQueryParameter("mtu", mtu.toString())
        .addQueryParameter("multiplexing", multiplexing)
        .addQueryParameter("handshake-mode", handshakeMode)
        .toLink("mierus", false)
}

fun MieruBean.buildMieruConfig(port: Int): String {
    validate()
    return Gson().toJson(linkedMapOf(
        "activeProfile" to "default",
        "socks5Port" to port,
        "socks5ListenLAN" to false,
        "loggingLevel" to "INFO",
        "profiles" to listOf(linkedMapOf(
            "profileName" to "default",
            "user" to mapOf("name" to username, "password" to password),
            "servers" to listOf(mapOf(
                "ipAddress" to finalAddress,
                "portBindings" to listOf(mapOf("port" to finalPort, "protocol" to protocol))
            )),
            "mtu" to mtu,
            "multiplexing" to mapOf("level" to multiplexing),
            "handshakeMode" to handshakeMode
        ))
    ))
}
