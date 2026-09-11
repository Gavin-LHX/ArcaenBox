package io.nekohasekai.sagernet.utils;

import com.google.gson.JsonParser;
import java.net.InetAddress;

public final class PublicIp {
    private PublicIp() {}
    public static String parse(String response) throws Exception {
        String value = response.trim();
        if (value.startsWith("{")) value = JsonParser.parseString(value).getAsJsonObject().get("ip").getAsString();
        // Restrict input to numeric literals before InetAddress, so parsing cannot resolve a hostname.
        if (value.length() > 45 || !value.matches("[0-9a-fA-F:.]+") || !value.contains(".") && !value.contains(":"))
            throw new IllegalArgumentException("Not an IP address");
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Not IPv4");
            for (String part : parts) if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                throw new IllegalArgumentException("Not IPv4");
        }
        InetAddress address = InetAddress.getByName(value);
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                || bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) >= 240
                || (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127
                || (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19)))
            throw new IllegalArgumentException("Not a public IP address");
        return address.getHostAddress();
    }
}
