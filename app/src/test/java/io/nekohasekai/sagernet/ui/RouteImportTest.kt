package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class RouteImportTest {
    @Test fun xrayDomainPortAndNetworkConversion() {
        val rule = RouteImportUi.decode("""{"routing":{"rules":[{"type":"field","domain":["domain:example.com","geosite:cn"],"port":"80,1000-2000","network":"tcp,udp","outboundTag":"direct"}]}}""", emptyList()).single()
        assertEquals(-1L, rule.outbound); assertEquals("domain:example.com\ngeosite:cn", rule.domains)
        assertEquals("80,1000:2000", rule.port); assertEquals("", rule.network)
    }
    @Test fun singBoxExactDomainsRemainExact() {
        val rule = RouteImportUi.decode("""{"rules":[{"domain":["one.example"],"domain_suffix":[".two.example"],"inbound":["tun"],"action":"reject"}]}""", emptyList()).single()
        assertEquals("full:one.example\ndomain:two.example",rule.domains)
        assertEquals(-2L,rule.outbound); assertTrue(rule.config.contains("tun-in"))
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedFieldsAbortImport() {
        RouteImportUi.decode("""[{"domain":["example.com"],"process_name":["game.exe"],"outbound":"proxy"}]""", emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun badPortIsNotSilentlyDropped() {
        RouteImportUi.decode("""[{"port":"0-70000","outbound":"proxy"}]""", emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun missingNamedNodeCannotFallBackToDefaultProxy() {
        RouteImportUi.decode("""[{"domain":["example.com"],"outbound":"missing-node"}]""", emptyList())
    }
}
