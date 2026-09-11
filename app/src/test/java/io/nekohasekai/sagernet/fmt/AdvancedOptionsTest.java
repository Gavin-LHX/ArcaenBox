package io.nekohasekai.sagernet.fmt;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdvancedOptionsTest {
    private final String base = "{\"dns\":{\"servers\":[{\"tag\":\"dns-direct\",\"address\":\"https://dns.example/dns-query\"},{\"tag\":\"dns-remote\",\"address\":\"1.1.1.1\"}],\"rules\":[{\"domain\":[\"keep.example\"],\"server\":\"dns-direct\"}]},\"inbounds\":[{\"type\":\"mixed\",\"tag\":\"mixed-in\",\"listen\":\"0.0.0.0\",\"listen_port\":2080,\"sniff\":true}],\"route\":{\"rules\":[{\"ip_cidr\":[\"124.221.69.228/32\"],\"outbound\":\"direct\"}]},\"outbounds\":[{\"type\":\"vless\",\"tls\":{\"enabled\":true,\"utls\":{\"fingerprint\":\"firefox\"}}},{\"type\":\"trojan\",\"tls\":{\"enabled\":true}}]}";

    @Test public void dnsBlockingHostsAndBootstrapPreserveExistingRules() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings();
        s.blockAAAA = true; s.blockHttps = true; s.hosts = "sample.example 192.0.2.3 2001:db8::2"; s.bootstrap = "9.9.9.9";
        JsonObject dns = JsonParser.parseString(AdvancedOptions.apply(base, s)).getAsJsonObject().getAsJsonObject("dns");
        assertEquals(3, dns.getAsJsonArray("rules").size());
        assertEquals("AAAA", dns.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("query_type").get(0).getAsString());
        assertEquals("keep.example", dns.getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("domain").get(0).getAsString());
        assertEquals("arcaenbox-bootstrap", dns.getAsJsonArray("servers").get(0).getAsJsonObject().get("address_resolver").getAsString());
        assertFalse(dns.getAsJsonArray("servers").get(2).getAsJsonObject().has("detour"));
        assertEquals(2, dns.getAsJsonArray("servers").get(3).getAsJsonObject().getAsJsonObject("predefined").getAsJsonArray("sample.example").size());
    }

    @Test public void authProtectsLanWithoutBreakingInternalClients() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings(); s.secondPort = 3080; s.username = "example"; s.password = "example-password";
        JsonArray inbounds = JsonParser.parseString(AdvancedOptions.apply(base, s)).getAsJsonObject().getAsJsonArray("inbounds");
        assertEquals("127.0.0.1", inbounds.get(0).getAsJsonObject().get("listen").getAsString());
        assertFalse(inbounds.get(0).getAsJsonObject().has("users"));
        assertEquals("0.0.0.0", inbounds.get(1).getAsJsonObject().get("listen").getAsString());
        assertEquals("example", inbounds.get(1).getAsJsonObject().getAsJsonArray("users").get(0).getAsJsonObject().get("username").getAsString());
    }

    @Test public void sniffersAndFingerprintKeepExplicitProfileOptions() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings(); s.sniffers = "http,tls"; s.fingerprint = "chrome";
        JsonObject root = JsonParser.parseString(AdvancedOptions.apply(base, s)).getAsJsonObject();
        assertFalse(root.getAsJsonArray("inbounds").get(0).getAsJsonObject().has("sniff"));
        assertEquals("sniff", root.getAsJsonObject("route").getAsJsonArray("rules").get(0).getAsJsonObject().get("action").getAsString());
        assertEquals("firefox", root.getAsJsonArray("outbounds").get(0).getAsJsonObject().getAsJsonObject("tls").getAsJsonObject("utls").get("fingerprint").getAsString());
        assertEquals("chrome", root.getAsJsonArray("outbounds").get(1).getAsJsonObject().getAsJsonObject("tls").getAsJsonObject("utls").get("fingerprint").getAsString());
    }

    @Test public void disablingCacheAlsoDisablesOptimistic() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings(); s.dnsCache = false; s.optimistic = true;
        JsonObject dns = JsonParser.parseString(AdvancedOptions.apply(base, s)).getAsJsonObject().getAsJsonObject("dns");
        assertTrue(dns.get("disable_cache").getAsBoolean()); assertFalse(dns.get("optimistic").getAsBoolean());
    }

    @Test public void customDnsOverridesBasicOptionsAndKeepsRequiredTags() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings(); s.blockAAAA = true;
        s.customDns = "{\"servers\":[{\"tag\":\"dns-direct\",\"type\":\"local\"},{\"tag\":\"dns-remote\",\"type\":\"https\",\"server\":\"1.1.1.1\"}],\"final\":\"dns-remote\"}";
        assertEquals(JsonParser.parseString(s.customDns), JsonParser.parseString(AdvancedOptions.apply(base, s)).getAsJsonObject().get("dns"));
    }

    @Test(expected = IllegalArgumentException.class) public void missingDnsTagRejected() { AdvancedOptions.validateDns("{\"servers\":[]}"); }
    @Test(expected = IllegalArgumentException.class) public void hostsCannotTriggerDnsResolution() { AdvancedOptions.parseHosts("x.example attacker.example"); }
    @Test(expected = IllegalArgumentException.class) public void portCollisionRejected() {
        AdvancedOptions.Settings s = new AdvancedOptions.Settings(); s.secondPort = 2080; AdvancedOptions.apply(base, s);
    }
}
