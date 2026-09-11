package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.ResourceFiles
import java.io.File
import moe.matsuri.nb4a.SingBoxOptions.RuleSet

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String {
            return (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", "prefer_ipv4")
            }
        }
    }

}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    rule_set = mutableListOf<String>()
    domain = mutableListOf<String>()
    domain_suffix = mutableListOf<String>()
    domain_regex = mutableListOf<String>()
    domain_keyword = mutableListOf<String>()
    list.forEach {
        if (it.startsWith("geosite:") || it.startsWith("rule-set:")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach {
        when {
            it.startsWith("rule-set:") -> {
                val name = it.removePrefix("rule-set:")
                require(ResourceFiles.validName(name) && (name.endsWith(".srs") || name.endsWith(".json"))) { "Invalid rule set filename" }
                val file = File(app.externalAssets, name)
                require(file.isFile) { "Missing resource: $name" }
                ruleSet.add(RuleSet().apply {
                    type = "local"
                    tag = it
                    format = if (name.endsWith(".srs")) "binary" else "source"
                    path = file.absolutePath
                })
            }
            it.startsWith("geoip:") -> {
                ruleSet.add(RuleSet().apply {
                    type = "local"
                    tag = it
                    format = "binary"
                    path = it
                })
            }

            it.startsWith("geosite:") -> {
                ruleSet.add(RuleSet().apply {
                    type = "local"
                    tag = it
                    format = "binary"
                    path = it
                })
            }
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    if (isIP) {
        ip_cidr = mutableListOf<String>()
        rule_set = mutableListOf<String>()
    } else {
        rule_set = mutableListOf<String>()
        domain = mutableListOf<String>()
        domain_suffix = mutableListOf<String>()
        domain_regex = mutableListOf<String>()
        domain_keyword = mutableListOf<String>()
    }
    list.forEach {
        if (isIP) {
            if (it.startsWith("rule-set:")) {
                rule_set.plusAssign(it)
            } else if (it.startsWith("geoip:")) {
                if (it == "geoip:private") {
                    ip_is_private = true
                } else {
                    rule_set.plusAssign(it)
                }
            } else {
                ip_cidr.plusAssign(it)
            }
            return@forEach
        }
        if (it.startsWith("geosite:") || it.startsWith("rule-set:")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    ip_cidr?.removeIf { it.isNullOrBlank() }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}
