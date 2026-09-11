package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

object RoutePresets {
    val values = listOf("custom", "whitelist", "blacklist", "global")

    fun rules(mode: String): List<RuleEntity> {
        if (mode == "global" || mode == "custom") return emptyList()
        val rules = mutableListOf(
            RuleEntity(name = app.getString(R.string.preset_lan_ip), ip = "geoip:private", outbound = -1, enabled = true),
            RuleEntity(name = app.getString(R.string.preset_lan_domain), domains = "geosite:private", outbound = -1, enabled = true)
        )
        if (mode == "whitelist") {
            rules += RuleEntity(name = "Google APIs CN", domains = "domain:googleapis.cn", enabled = true)
            rules += RuleEntity(name = app.getString(R.string.preset_cn_domain), domains = "geosite:cn", outbound = -1, enabled = true)
            rules += RuleEntity(name = app.getString(R.string.preset_cn_ip), ip = "geoip:cn", outbound = -1, enabled = true)
        } else if (mode == "blacklist") {
            rules += RuleEntity(name = app.getString(R.string.preset_global_domain), domains = "geosite:geolocation-!cn", enabled = true)
        }
        rules.forEachIndexed { index, rule -> rule.id = -100L - index; rule.userOrder = index.toLong() }
        return rules
    }
}
