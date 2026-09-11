package io.nekohasekai.sagernet.bg.proto;

import java.net.IDN;
import java.net.InetAddress;
import java.util.Locale;

/** A protocol prefix selects its default port; plain hosts retain legacy NTP settings. */
public final class UdpTestTarget {
    public final String mode, host;
    public final int port;
    private UdpTestTarget(String mode, String host, int port) { this.mode=mode; this.host=host; this.port=port; }

    public static UdpTestTarget parse(String value, int legacyPort) {
        String mode="ntp", host=value.trim(); int port=legacyPort;
        for (String candidate : new String[]{"ntp", "dns", "stun", "mcbe"}) {
            if (host.toLowerCase(Locale.ROOT).startsWith(candidate+":")) {
                mode=candidate; host=host.substring(candidate.length()+1);
                port=mode.equals("dns")?53:mode.equals("stun")?3478:mode.equals("mcbe")?19132:123;
                break;
            }
        }
        if (host.startsWith("[")) {
            int end=host.indexOf(']');
            if (end < 2) throw new IllegalArgumentException("Invalid IPv6 address");
            String suffix=host.substring(end+1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) throw new IllegalArgumentException("Invalid port");
                port=Integer.parseInt(suffix.substring(1));
            }
            host=host.substring(1,end);
            if (!host.matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException("Invalid IPv6 address");
            try { if (InetAddress.getByName(host).getAddress().length!=16) throw new IllegalArgumentException("Not IPv6"); }
            catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("Invalid IPv6 address"); }
        } else {
            int colon=host.indexOf(':');
            if (colon>=0) { port=Integer.parseInt(host.substring(colon+1));host=host.substring(0,colon); }
            host=IDN.toASCII(host);
            if (host.isEmpty() || host.length()>253 || !host.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid host");
        }
        if (port<1 || port>65535) throw new IllegalArgumentException("Invalid port");
        return new UdpTestTarget(mode,host,port);
    }
}
