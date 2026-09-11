package io.nekohasekai.sagernet.po0

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The token is a credential, including when a debugger or exception prints this object. */
class Po0Token(val value: String, val slot: Int?) {
    override fun toString() = "Po0Token([redacted], slot=$slot)"
}

data class Po0Entry(val ip: String, val slot: Int? = null)
data class Po0Result(
    val status: String,
    val currentIp: String = "",
    val entries: List<Po0Entry> = emptyList(),
    val limit: Int = 5,
    val httpCode: Int = 0,
) {
    val retryable get() = status == "network" || status == "no_network" ||
        (status == "http" && (httpCode == 429 || httpCode >= 500))
}

object Po0Protocol {
    const val API = "https://" + Po0RouteConfig.API_IP + "/api/firewall/"
    const val GUIDE = "https://wiki.uuuz.de/guide/tutorials/po0fw-whitelist.html"
    private val tokenPattern = Regex("^(pgnfw_[A-Za-z0-9_-]{1,512})(?:@([0-4]))?$")

    fun tokens(text: String): List<Po0Token> {
        val parts = text.trim().split(Regex("[,，|;；、\\s]+")).filter { it.isNotEmpty() }
        require(parts.size <= 20) { "Invalid token list" }
        val result = parts.map {
            val match = tokenPattern.matchEntire(it) ?: throw IllegalArgumentException("Invalid token list")
            Po0Token(match.groupValues[1], match.groupValues[2].toIntOrNull())
        }
        // A machine must not receive two conflicting slot assignments in one update.
        require(result.groupBy { it.value }.values.none { group -> group.map { it.slot }.distinct().size > 1 }) {
            "Conflicting slots"
        }
        return result.distinctBy { it.value }
    }

    fun url(token: Po0Token): HttpUrl = API.toHttpUrl().newBuilder()
        .addPathSegment(token.value).addPathSegment("add")
        .apply { token.slot?.let { addQueryParameter("slot", it.toString()) } }.build()

    private fun ipv4(value: String): List<Int>? {
        val parts = value.removeSuffix("/24").split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
    }

    fun sameNetwork(a: String, b: String): Boolean {
        val left = ipv4(a) ?: return false
        val right = ipv4(b) ?: return false
        return if (a.endsWith("/24") || b.endsWith("/24")) left.take(3) == right.take(3) else left == right
    }

    fun response(code: Int, body: String, requestedSlot: Int?): Po0Result {
        if (code !in 200..299) return Po0Result(if (code == 403) "forbidden" else "http", httpCode = code)
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            if (!obj.has("enabled") || !obj.has("whitelist")) return Po0Result("invalid_response")
            val current = obj.get("currentIp")?.asString.orEmpty()
            if (ipv4(current) == null) return Po0Result("invalid_response")
            val entries = obj.getAsJsonArray("whitelist").map { raw ->
                if (raw.isJsonPrimitive) Po0Entry(raw.asString) else {
                    val entry = raw.asJsonObject
                    Po0Entry(entry.get("ip").asString,
                        entry.get("slot")?.takeUnless { it.isJsonNull }?.asInt)
                }
            }
            if (entries.any { ipv4(it.ip) == null || (it.slot != null && it.slot !in 0..4) }) {
                return Po0Result("invalid_response")
            }
            val status = when {
                !obj.get("enabled").asBoolean -> "disabled"
                entries.none { sameNetwork(it.ip, current) } -> "not_applied"
                requestedSlot != null && entries.none { sameNetwork(it.ip, current) && it.slot == requestedSlot } -> "slot_mismatch"
                else -> "success"
            }
            Po0Result(status, current, entries, (obj.get("limit")?.asInt ?: 5).coerceIn(1, 100))
        } catch (_: Exception) {
            // Never echo server text: it may contain the token, URL or an HTML error page.
            Po0Result("invalid_response")
        }
    }
}
