package io.nekohasekai.sagernet.po0;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class Po0RouteConfigTest {
    private JsonObject parse(String config) { return JsonParser.parseString(config).getAsJsonObject(); }
    private JsonArray rules(JsonObject root) { return root.getAsJsonObject("route").getAsJsonArray("rules"); }
    private JsonObject first(JsonObject root) { return rules(root).get(0).getAsJsonObject(); }

    @Test public void disabledPo0DoesNotTouchConfiguration() {
        String text = "  { \"route\": {\"final\":\"proxy\"} }  ";
        assertEquals(text, Po0RouteConfig.apply(text, false));
        assertEquals("invalid custom config", Po0RouteConfig.apply("invalid custom config", false));
    }

    @Test public void ipBypassPrecedesResolveAndGlobalProxyWithoutChangingDns() {
        String text = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"bypass\"}],"
                + "\"dns\":{\"final\":\"remote\"},\"route\":{\"final\":\"proxy\",\"rules\":["
                + "{\"action\":\"resolve\",\"server\":\"remote\"},{\"outbound\":\"proxy\"}]}}";
        JsonObject before = parse(text), result = parse(Po0RouteConfig.apply(text, true));
        assertEquals("124.221.69.228/32", first(result).getAsJsonArray("ip_cidr").get(0).getAsString());
        assertEquals("bypass", first(result).get("outbound").getAsString());
        assertEquals("route", first(result).get("action").getAsString());
        assertEquals(3, first(result).size());
        assertEquals(before.get("dns"), result.get("dns"));
        assertEquals(before.get("outbounds"), result.get("outbounds"));
        assertEquals(rules(before).get(0), rules(result).get(1));
        assertEquals(rules(before).get(1), rules(result).get(2));
        assertEquals("proxy", result.getAsJsonObject("route").get("final").getAsString());
    }

    @Test public void repeatedStartupDoesNotAccumulateRulesOrOutbounds() {
        String once = Po0RouteConfig.apply("{}", true);
        String twice = Po0RouteConfig.apply(once, true);
        assertEquals(once, twice);
        assertEquals(1, rules(parse(twice)).size());
        assertEquals(1, parse(twice).getAsJsonArray("outbounds").size());
    }

    @Test public void customProxyAndEndpointTagsCannotCaptureDirectRoute() {
        JsonObject result = parse(Po0RouteConfig.apply("{\"outbounds\":[{\"type\":\"socks\",\"tag\":\"po0-direct\"}],"
                + "\"endpoints\":[{\"type\":\"wireguard\",\"tag\":\"po0-direct-1\"}]}", true));
        assertEquals("po0-direct-2", first(result).get("outbound").getAsString());
        JsonObject added = result.getAsJsonArray("outbounds").get(1).getAsJsonObject();
        assertEquals("direct", added.get("type").getAsString());
        assertEquals(2, added.size());
        assertEquals(1, result.getAsJsonArray("endpoints").size());
    }

    @Test public void directOutboundWithProxyDetourIsNeverReused() {
        JsonObject result = parse(Po0RouteConfig.apply("{\"outbounds\":[{\"type\":\"direct\","
                + "\"tag\":\"direct\",\"detour\":\"proxy\"}]}", true));
        assertEquals("po0-direct", first(result).get("outbound").getAsString());
        assertEquals("proxy", result.getAsJsonArray("outbounds").get(0).getAsJsonObject().get("detour").getAsString());
    }

    @Test public void equivalentExistingRulesAreCoalescedAtHighestPriority() {
        String match = "{\"ip_cidr\":[\"124.221.69.228/32\"],\"outbound\":\"direct\"}";
        JsonObject result = parse(Po0RouteConfig.apply("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],"
                + "\"route\":{\"rules\":[{\"outbound\":\"proxy\"}," + match + "," + match + "]}}", true));
        assertEquals(2, rules(result).size());
        assertEquals("direct", first(result).get("outbound").getAsString());
        assertEquals("proxy", rules(result).get(1).getAsJsonObject().get("outbound").getAsString());
    }

    @Test public void constrainedRulesAndOtherAddressesKeepTheirBehaviorAndOrder() {
        String text = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"rules\":["
                + "{\"ip_cidr\":[\"124.221.69.228/32\"],\"port\":[443],\"outbound\":\"direct\"},"
                + "{\"ip_cidr\":[\"203.0.113.1/32\"],\"outbound\":\"proxy\"}]}}";
        JsonObject before = parse(text), after = parse(Po0RouteConfig.apply(text, true));
        assertEquals(3, rules(after).size());
        assertEquals(rules(before).get(0), rules(after).get(1));
        assertEquals(rules(before).get(1), rules(after).get(2));
    }
}
