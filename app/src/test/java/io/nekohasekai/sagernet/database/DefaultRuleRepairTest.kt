package io.nekohasekai.sagernet.database

import org.junit.Assert.*
import org.junit.Test

class DefaultRuleRepairTest {
    private val seed = RuleEntity(name = "Block QUIC", port = "443", network = "udp", outbound = -2)

    @Test fun retainsFirstRuleAndRemovesOnlyRepeatedDefaults() {
        val first = seed.copy(id = 1, userOrder = 1)
        val second = seed.copy(id = 2, userOrder = 2)
        assertEquals(listOf(second), DefaultRuleRepair.duplicates(listOf(first, second), listOf(seed)))
    }

    @Test fun preservesEditedFieldsAndDifferentEnabledStates() {
        val rows = listOf(seed.copy(id = 1), seed.copy(id = 2, enabled = true),
            seed.copy(id = 3, name = "My QUIC rule"), seed.copy(id = 4, port = "8443"),
            seed.copy(id = 5, config = "{}"), seed.copy(id = 6, outbound = 7))
        assertTrue(DefaultRuleRepair.duplicates(rows, listOf(seed)).isEmpty())
    }

    @Test fun doesNotMergeUserCreatedRulesOrRepeatRepair() {
        val custom = RuleEntity(name = "Custom", domains = "example.com")
        val rows = listOf(seed.copy(id = 1), seed.copy(id = 2), custom.copy(id = 3), custom.copy(id = 4))
        val duplicates = DefaultRuleRepair.duplicates(rows, listOf(seed))
        assertEquals(listOf(2L), duplicates.map { it.id })
        assertTrue(DefaultRuleRepair.duplicates(rows - duplicates.toSet(), listOf(seed)).isEmpty())
    }
}
