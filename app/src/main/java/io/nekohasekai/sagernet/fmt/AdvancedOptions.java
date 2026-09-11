package io.nekohasekai.sagernet.fmt;

import com.google.gson.*;
import java.net.IDN;
import java.net.InetAddress;
import java.util.*;

/** Android/sing-box counterparts of the advanced settings, applied before core migration. */
public final class AdvancedOptions {
    public static class Settings {
        public boolean dnsCache = true, optimistic, blockAAAA, blockHttps, systemHosts, cacheFile;
        public int dnsTimeout = 10, dnsCapacity = 4096, secondPort;
        public String hosts = "", bootstrap = "", customDns = "", cachePath = "", cacheId = "";
        public String sniffers = "", fingerprint = "", username = "", password = "";
    }

    public static JsonObject validateDns(String text) {
        JsonObject dns = JsonParser.parseString(text).getAsJsonObject();
        if (!dns.has("servers") || !dns.get("servers").isJsonArray()) throw new IllegalArgumentException("DNS servers array is required");
        Set<String> tags = new HashSet<>();
        for (JsonElement entry : dns.getAsJsonArray("servers")) {
            JsonObject server = entry.getAsJsonObject();
            if (!server.has("tag") || !tags.add(server.get("tag").getAsString())) throw new IllegalArgumentException("DNS server tags must be unique");
        }
        if (!tags.contains("dns-direct") || !tags.contains("dns-remote"))
            throw new IllegalArgumentException("Keep dns-direct and dns-remote server tags for node resolution and routing");
        if (dns.has("final") && !tags.contains(dns.get("final").getAsString())) throw new IllegalArgumentException("Unknown final DNS server");
        if (dns.has("rules") && !dns.get("rules").isJsonArray()) throw new IllegalArgumentException("DNS rules must be an array");
        return dns;
    }

    public static void validateIp(String value) {
        try {
            if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") && !(value.contains(":") && value.matches("[0-9a-fA-F:.]+")))
                throw new IllegalArgumentException("A numeric IP address is required");
            if (!value.contains(":")) for (String part : value.split("\\."))
                if (Integer.parseInt(part) > 255 || part.length() > 3) throw new IllegalArgumentException("Invalid IPv4 address");
            InetAddress.getByName(value); // input cannot be a hostname
        } catch (Exception e) { throw new IllegalArgumentException("Invalid IP address: " + value); }
    }

    public static JsonObject parseHosts(String text) {
        JsonObject entries = new JsonObject();
        int lineNo = 0;
        for (String line : text.split("\\R")) {
            lineNo++;
            String clean = line.split("#", 2)[0].trim();
            if (clean.isEmpty()) continue;
            String[] parts = clean.split("\\s+");
            if (parts.length < 2) throw new IllegalArgumentException("Hosts line " + lineNo + ": domain IP [IP...]");
            String domain = IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (domain.isEmpty() || domain.length() > 253) throw new IllegalArgumentException("Invalid hosts domain");
            JsonArray ips = new JsonArray();
            for (int i = 1; i < parts.length; i++) { validateIp(parts[i]); ips.add(parts[i]); }
            entries.add(domain, ips);
        }
        return entries;
    }

    public static String apply(String config, Settings options) {
        JsonObject root = JsonParser.parseString(config).getAsJsonObject();
        if (!options.customDns.isBlank()) root.add("dns", validateDns(options.customDns));
        else if (root.has("dns")) {
            if (options.dnsTimeout < 1 || options.dnsTimeout > 60 || options.dnsCapacity < 1024 || options.dnsCapacity > 65536)
                throw new IllegalArgumentException("Invalid DNS timeout or cache capacity");
            JsonObject dns = root.getAsJsonObject("dns");
            dns.addProperty("disable_cache", !options.dnsCache);
            dns.addProperty("optimistic", options.dnsCache && options.optimistic);
            if (options.optimistic && options.dnsCache) dns.remove("disable_expire");
            dns.addProperty("cache_capacity", options.dnsCapacity);
            dns.addProperty("timeout", options.dnsTimeout + "s");
            JsonArray servers = array(dns, "servers");
            if (!options.bootstrap.isBlank()) {
                validateIp(options.bootstrap);
                JsonObject bootstrap = new JsonObject();
                bootstrap.addProperty("type", "udp"); bootstrap.addProperty("tag", "arcaenbox-bootstrap");
                // Typed DNS servers dial directly by default; an empty direct outbound is not a valid detour.
                bootstrap.addProperty("server", options.bootstrap);
                servers.add(bootstrap);
                for (JsonElement element : servers) {
                    JsonObject server = element.getAsJsonObject();
                    if (server.has("tag") && server.get("tag").getAsString().equals("dns-direct"))
                        server.addProperty("address_resolver", "arcaenbox-bootstrap");
                }
            }
            JsonArray rules = new JsonArray();
            if (options.blockAAAA || options.blockHttps) {
                JsonObject block = new JsonObject();
                JsonArray types = new JsonArray();
                if (options.blockAAAA) types.add("AAAA");
                if (options.blockHttps) { types.add("SVCB"); types.add("HTTPS"); }
                block.add("query_type", types); block.addProperty("action", "predefined"); block.addProperty("rcode", "NOERROR");
                rules.add(block);
            }
            JsonObject predefined = parseHosts(options.hosts);
            if (options.systemHosts || predefined.size() > 0) {
                JsonObject hosts = new JsonObject();
                hosts.addProperty("type", "hosts"); hosts.addProperty("tag", "arcaenbox-hosts");
                JsonArray paths = new JsonArray(); paths.add(options.systemHosts ? "/system/etc/hosts" : "/dev/null"); hosts.add("path", paths);
                hosts.add("predefined", predefined);
                servers.add(hosts);
                JsonObject rule = new JsonObject();
                rule.addProperty("preferred_by", "arcaenbox-hosts"); rule.addProperty("action", "route"); rule.addProperty("server", "arcaenbox-hosts");
                rules.add(rule);
            }
            if (dns.has("rules")) rules.addAll(dns.getAsJsonArray("rules"));
            dns.add("servers", servers); dns.add("rules", rules);
        }
        JsonArray routeRules = new JsonArray();
        if (root.has("inbounds")) {
            JsonArray inbounds = root.getAsJsonArray("inbounds");
            JsonObject second = null;
            for (JsonElement item : inbounds) {
                JsonObject inbound = item.getAsJsonObject();
                if (options.secondPort > 0 && inbound.has("listen_port") && inbound.get("listen_port").getAsInt() == options.secondPort)
                    throw new IllegalArgumentException("The second listener port is already in use");
                if (inbound.has("tag") && inbound.get("tag").getAsString().equals("mixed-in")) {
                    if (options.secondPort > 0) {
                        if (options.secondPort > 65535) throw new IllegalArgumentException("Invalid second listener port");
                        second = inbound.deepCopy(); second.addProperty("tag", "mixed-secondary"); second.addProperty("listen_port", options.secondPort);
                        if (!options.username.isEmpty() || !options.password.isEmpty()) {
                            if (options.username.isBlank() || options.password.isEmpty()) throw new IllegalArgumentException("Set both proxy username and password");
                            JsonObject user = new JsonObject(); user.addProperty("username", options.username); user.addProperty("password", options.password);
                            JsonArray users = new JsonArray(); users.add(user); second.add("users", users);
                            // Internal updaters and Android's HTTP proxy use the first listener without credentials.
                            inbound.addProperty("listen", "127.0.0.1");
                        }
                    }
                }
            }
            if (second != null) inbounds.add(second);
            if (!options.sniffers.isBlank()) {
                JsonArray sniffers = new JsonArray();
                for (String sniffer : options.sniffers.split(",")) {
                    if (!Arrays.asList("http", "tls", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp", "ntp").contains(sniffer))
                        throw new IllegalArgumentException("Invalid protocol sniffer: " + sniffer);
                    sniffers.add(sniffer);
                }
                for (JsonElement item : inbounds) {
                    JsonObject inbound = item.getAsJsonObject();
                    if (!inbound.has("sniff") || !inbound.get("sniff").getAsBoolean()) continue;
                    JsonObject sniff = new JsonObject(); sniff.addProperty("action", "sniff"); sniff.add("sniffer", sniffers.deepCopy());
                    JsonArray tags = new JsonArray(); tags.add(inbound.get("tag").getAsString()); sniff.add("inbound", tags);
                    if (inbound.has("sniff_override_destination") && inbound.get("sniff_override_destination").getAsBoolean()) sniff.addProperty("override_destination", true);
                    inbound.remove("sniff"); inbound.remove("sniff_override_destination");
                    routeRules.add(sniff);
                }
            }
        }
        if (routeRules.size() > 0) {
            JsonObject route = root.has("route") ? root.getAsJsonObject("route") : new JsonObject();
            if (route.has("rules")) routeRules.addAll(route.getAsJsonArray("rules"));
            route.add("rules", routeRules); root.add("route", route);
        }
        if (!options.fingerprint.isBlank() && root.has("outbounds")) for (JsonElement element : root.getAsJsonArray("outbounds")) {
            JsonObject out = element.getAsJsonObject();
            if (!Arrays.asList("vmess", "vless", "trojan", "anytls", "http").contains(out.get("type").getAsString()) || !out.has("tls")) continue;
            JsonObject tls = out.getAsJsonObject("tls");
            if (!tls.has("utls") && tls.has("enabled") && tls.get("enabled").getAsBoolean()) {
                JsonObject utls = new JsonObject(); utls.addProperty("enabled", true); utls.addProperty("fingerprint", options.fingerprint); tls.add("utls", utls);
            }
        }
        if (options.cacheFile) {
            JsonObject experimental = root.has("experimental") ? root.getAsJsonObject("experimental") : new JsonObject();
            if (!experimental.has("cache_file")) {
                JsonObject cache = new JsonObject(); cache.addProperty("enabled", true); cache.addProperty("path", options.cachePath);
                cache.addProperty("cache_id", options.cacheId); cache.addProperty("store_fakeip", true);
                experimental.add("cache_file", cache);
            }
            root.add("experimental", experimental);
        }
        return root.toString();
    }

    private static JsonArray array(JsonObject root, String key) { return root.has(key) ? root.getAsJsonArray(key) : new JsonArray(); }
}
