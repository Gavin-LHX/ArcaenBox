package io.nekohasekai.sagernet.database

/** Repair only identical copies of known initial rules; keep edits and custom rules. */
object DefaultRuleRepair {
    fun duplicates(rules: List<RuleEntity>, defaults: List<RuleEntity>): List<RuleEntity> {
        val templates = defaults.map { it.copy(id = 0, userOrder = 0, enabled = false) }.toSet()
        val seen = HashSet<RuleEntity>()
        return rules.filter { rule ->
            val content = rule.copy(id = 0, userOrder = 0)
            content.copy(enabled = false) in templates && !seen.add(content)
        }
    }
}
