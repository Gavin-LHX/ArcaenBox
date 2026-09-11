package io.nekohasekai.sagernet.fmt;

import com.google.gson.*;
import java.util.Arrays;

/** Supported sing-box defaults; explicit per-profile values take precedence. */
public final class ConnectionOptions {
    private ConnectionOptions() {}
    public static String apply(String config, int udpSeconds, String fragment, int fragmentDelay,
                               boolean mux, String muxProtocol, int muxStreams, boolean muxPadding) {
        if (udpSeconds < 0 || udpSeconds > 86400 || fragmentDelay < 1 || fragmentDelay > 1000
                || muxStreams < 1 || muxStreams > 1024
                || !Arrays.asList("off", "record", "packet").contains(fragment)
                || !Arrays.asList("h2mux", "smux", "yamux").contains(muxProtocol))
            throw new IllegalArgumentException("Invalid connection options");
        JsonObject root = JsonParser.parseString(config).getAsJsonObject();
        if (udpSeconds > 0 && root.has("inbounds")) {
            for (JsonElement element : root.getAsJsonArray("inbounds")) {
                JsonObject inbound = element.getAsJsonObject();
                if (Arrays.asList("tun", "mixed", "socks").contains(inbound.get("type").getAsString()))
                    if (!inbound.has("udp_timeout")) inbound.addProperty("udp_timeout", udpSeconds + "s");
            }
        }
        if (root.has("outbounds")) for (JsonElement element : root.getAsJsonArray("outbounds")) {
            JsonObject outbound = element.getAsJsonObject();
            String type = outbound.get("type").getAsString();
            if (!fragment.equals("off") && Arrays.asList("vmess", "vless", "trojan", "anytls", "http", "shadowtls").contains(type)
                    && outbound.has("tls")) {
                JsonObject tls = outbound.getAsJsonObject("tls");
                if (tls.has("enabled") && tls.get("enabled").getAsBoolean()
                        && !tls.has("fragment") && !tls.has("record_fragment")) {
                    tls.addProperty(fragment.equals("record") ? "record_fragment" : "fragment", true);
                    if (fragment.equals("packet")) tls.addProperty("fragment_fallback_delay", fragmentDelay + "ms");
                }
            }
            if (mux && Arrays.asList("shadowsocks", "vmess", "vless", "trojan").contains(type)
                    && !outbound.has("multiplex")) {
                JsonObject multiplex = new JsonObject();
                multiplex.addProperty("enabled", true);
                multiplex.addProperty("protocol", muxProtocol);
                multiplex.addProperty("max_streams", muxStreams);
                multiplex.addProperty("padding", muxPadding);
                outbound.add("multiplex", multiplex);
            }
        }
        return new Gson().toJson(root);
    }
}
