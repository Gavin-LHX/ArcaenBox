package io.nekohasekai.sagernet.po0;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;

/** Apply after custom-config merging so an earlier catch-all cannot capture Po0. */
public final class Po0RouteConfig {
    public static final String API_IP = "124.221.69.228";
    public static final String API_CIDR = API_IP + "/32";
    private Po0RouteConfig() {}

    public static String apply(String config, boolean enabled) {
        if (!enabled) return config;
        JsonObject root = JsonParser.parseString(config).getAsJsonObject();
        JsonArray outbounds = array(root, "outbounds");
        Set<String> tags = new HashSet<>();
        Set<String> directTags = new HashSet<>();
        String direct = null;
        for (JsonElement raw : outbounds) {
            JsonObject outbound = raw.getAsJsonObject();
            if (!outbound.has("tag")) continue;
            String tag = outbound.get("tag").getAsString();
            tags.add(tag);
            // Reuse only a plain direct outbound, never one with a detour or overrides.
            if (outbound.size() == 2 && outbound.has("type") &&
                    "direct".equals(outbound.get("type").getAsString())) {
                directTags.add(tag);
                if (direct == null) direct = tag;
            }
        }
        if (root.has("endpoints")) {
            for (JsonElement endpoint : root.getAsJsonArray("endpoints")) {
                if (endpoint.getAsJsonObject().has("tag")) tags.add(endpoint.getAsJsonObject().get("tag").getAsString());
            }
        }
        if (direct == null) {
            direct = "po0-direct";
            for (int suffix = 1; tags.contains(direct); suffix++) direct = "po0-direct-" + suffix;
            JsonObject outbound = new JsonObject();
            outbound.addProperty("type", "direct");
            outbound.addProperty("tag", direct);
            outbounds.add(outbound);
            directTags.add(direct);
        }
        JsonObject route;
        if (root.has("route")) route = root.getAsJsonObject("route");
        else { route = new JsonObject(); root.add("route", route); }
        JsonArray rules = array(route, "rules");
        JsonObject bypass = new JsonObject();
        JsonArray cidr = new JsonArray(); cidr.add(API_CIDR);
        bypass.add("ip_cidr", cidr);
        bypass.addProperty("action", "route");
        bypass.addProperty("outbound", direct);
        JsonArray ordered = new JsonArray(); ordered.add(bypass);
        for (JsonElement rule : rules) {
            if (!exactDirectRule(rule, directTags)) ordered.add(rule);
        }
        route.add("rules", ordered);
        // A literal IP matcher at the head of the list adds no resolve/sniff action
        // or DNS rule: the sing-box equivalent of IP-CIDR,...,DIRECT,no-resolve.
        return root.toString();
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object.has(key)) return object.getAsJsonArray(key);
        JsonArray array = new JsonArray(); object.add(key, array); return array;
    }

    private static boolean exactDirectRule(JsonElement raw, Set<String> directTags) {
        if (!raw.isJsonObject()) return false;
        JsonObject rule = raw.getAsJsonObject();
        if (!rule.has("ip_cidr") || !rule.has("outbound") ||
                !directTags.contains(rule.get("outbound").getAsString())) return false;
        if (rule.size() != (rule.has("action") ? 3 : 2)) return false;
        if (rule.has("action") && !"route".equals(rule.get("action").getAsString())) return false;
        JsonElement cidr = rule.get("ip_cidr");
        return cidr.isJsonArray() && cidr.getAsJsonArray().size() == 1 &&
                API_CIDR.equals(cidr.getAsJsonArray().get(0).getAsString());
    }
}
