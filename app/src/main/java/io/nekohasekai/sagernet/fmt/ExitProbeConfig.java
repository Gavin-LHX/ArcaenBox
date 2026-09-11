package io.nekohasekai.sagernet.fmt;

import com.google.gson.*;

/** A private inbound bypasses user routing so an IP lookup cannot report a direct exit. */
public final class ExitProbeConfig {
    private ExitProbeConfig() {}

    public static String proxyTag(String config) {
        JsonObject root = JsonParser.parseString(config).getAsJsonObject();
        String preferred = "proxy";
        if (root.has("route") && root.getAsJsonObject("route").has("final")) {
            preferred = root.getAsJsonObject("route").get("final").getAsString();
        }
        for (String key : new String[]{"outbounds", "endpoints"}) {
            if (!root.has(key)) continue;
            for (JsonElement element : root.getAsJsonArray(key)) {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("tag") || !item.has("type")) continue;
                String tag = item.get("tag").getAsString();
                String type = item.get("type").getAsString();
                if (tag.equals(preferred) && !type.equals("direct") && !type.equals("block")
                        && !type.equals("dns")) return tag;
            }
        }
        return null;
    }

    public static String apply(String config, int port, String outbound) {
        if (port == 0 || outbound == null) return config;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid probe port");
        JsonObject root = JsonParser.parseString(config).getAsJsonObject();
        JsonArray inbounds = root.has("inbounds") ? root.getAsJsonArray("inbounds") : new JsonArray();
        java.util.Set<String> tags = new java.util.HashSet<>();
        for (JsonElement element : inbounds) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("tag")) tags.add(item.get("tag").getAsString());
        }
        String tag = "arcaenbox-exit-probe";
        for (int suffix = 1; tags.contains(tag); suffix++) tag = "arcaenbox-exit-probe-" + suffix;
        JsonObject inbound = new JsonObject();
        inbound.addProperty("type", "socks");
        inbound.addProperty("tag", tag);
        inbound.addProperty("listen", "127.0.0.1");
        inbound.addProperty("listen_port", port);
        inbounds.add(inbound);
        root.add("inbounds", inbounds);
        JsonObject route = root.has("route") ? root.getAsJsonObject("route") : new JsonObject();
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        JsonArray matches = new JsonArray();
        matches.add(tag);
        rule.add("inbound", matches);
        rule.addProperty("action", "route");
        rule.addProperty("outbound", outbound);
        rules.add(rule);
        if (route.has("rules")) rules.addAll(route.getAsJsonArray("rules"));
        route.add("rules", rules);
        root.add("route", route);
        return new Gson().toJson(root);
    }
}
