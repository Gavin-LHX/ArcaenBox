package io.nekohasekai.sagernet.fmt;

import com.google.gson.*;
import io.nekohasekai.sagernet.utils.PublicIp;
import io.nekohasekai.sagernet.utils.ResourceFiles;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionExperienceTest {
    @Test public void probeBeatsDirectCatchAllAndUsesLoopbackOnly() {
        String config = "{\"inbounds\":[{\"type\":\"mixed\",\"tag\":\"arcaenbox-exit-probe\"}],\"outbounds\":[{\"type\":\"socks\",\"tag\":\"proxy\"}],\"route\":{\"rules\":[{\"outbound\":\"direct\"}]}}";
        JsonObject result = JsonParser.parseString(ExitProbeConfig.apply(config, 12345, ExitProbeConfig.proxyTag(config))).getAsJsonObject();
        JsonObject inbound = result.getAsJsonArray("inbounds").get(1).getAsJsonObject();
        assertEquals("127.0.0.1", inbound.get("listen").getAsString());
        assertEquals("arcaenbox-exit-probe-1", inbound.get("tag").getAsString());
        JsonArray rules = result.getAsJsonObject("route").getAsJsonArray("rules");
        assertEquals("proxy", rules.get(0).getAsJsonObject().get("outbound").getAsString());
        assertEquals("direct", rules.get(1).getAsJsonObject().get("outbound").getAsString());
    }
    @Test public void rawDirectConfigDoesNotPretendToHaveProxyExit() {
        String direct = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"proxy\"}]}";
        assertNull(ExitProbeConfig.proxyTag(direct));
        assertEquals(direct, ExitProbeConfig.apply(direct, 0, null));
        assertEquals("selector", ExitProbeConfig.proxyTag("{\"outbounds\":[{\"type\":\"selector\",\"tag\":\"selector\"}],\"route\":{\"final\":\"selector\"}}"));
    }
    @Test public void publicIpParsesPlainAndJsonWithoutDns() throws Exception {
        assertEquals("8.8.8.8", PublicIp.parse(" 8.8.8.8\n"));
        assertEquals("1.1.1.1", PublicIp.parse("{\"ip\":\"1.1.1.1\"}"));
        assertTrue(PublicIp.parse("2606:4700:4700::1111").contains(":"));
        for (String invalid : new String[]{"localhost", "1.2.3", "127.0.0.1", "10.2.3.4", "100.64.0.1", "198.18.0.1", "::1", "fc00::1", "169.254.1.1", "8.8.8.888", "01.2.3.4", "<html>8.8.8.8</html>"}) {
            try { PublicIp.parse(invalid); fail(invalid); } catch (Exception expected) {}
        }
    }
    @Test public void tuningPreservesNodeOverridesAndSkipsQuic() {
        String config = "{\"inbounds\":[{\"type\":\"tun\"}],\"outbounds\":[{\"type\":\"trojan\",\"tls\":{\"enabled\":true}},{\"type\":\"hysteria2\",\"tls\":{\"enabled\":true}},{\"type\":\"vmess\",\"multiplex\":{\"enabled\":false},\"tls\":{\"enabled\":true,\"record_fragment\":false}}]}";
        JsonObject result = JsonParser.parseString(ConnectionOptions.apply(config, 60, "packet", 250, true, "smux", 16, true)).getAsJsonObject();
        assertEquals("60s", result.getAsJsonArray("inbounds").get(0).getAsJsonObject().get("udp_timeout").getAsString());
        JsonArray out = result.getAsJsonArray("outbounds");
        assertEquals("250ms", out.get(0).getAsJsonObject().getAsJsonObject("tls").get("fragment_fallback_delay").getAsString());
        JsonObject mux = out.get(0).getAsJsonObject().getAsJsonObject("multiplex");
        assertEquals(16, mux.get("max_streams").getAsInt());
        assertFalse(mux.has("max_connections"));
        assertFalse(out.get(1).getAsJsonObject().getAsJsonObject("tls").has("fragment"));
        assertFalse(out.get(2).getAsJsonObject().getAsJsonObject("multiplex").get("enabled").getAsBoolean());
        assertFalse(out.get(2).getAsJsonObject().getAsJsonObject("tls").has("fragment"));
    }
    @Test public void resourceNamesCannotEscapeStorageOrImportDat() {
        assertTrue(ResourceFiles.validName("geosite.db"));
        assertTrue(ResourceFiles.validName("geoip-cn.srs"));
        for (String invalid : new String[]{"../geosite.db", "x/geoip.db", "x\\geoip.db", "geoip.dat", ".config.json", "x..json", "/tmp/a.db"}) assertFalse(invalid, ResourceFiles.validName(invalid));
    }
    @Test public void invalidResourceLeavesExistingBytesUntouched() throws Exception {
        java.io.File file = java.io.File.createTempFile("resource-test", ".tmp");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write("<html>error</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            for (String name : new String[]{"geoip.db", "geosite.db", "test.srs", "test.json"}) {
                try { ResourceFiles.validate(file, name); fail(name); } catch (java.io.IOException expected) {}
            }
            assertEquals(18, file.length());
        } finally { file.delete(); }
    }
}
