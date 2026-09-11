package io.nekohasekai.sagernet.bg.proto;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Arrays;

/** RFC 1928 UDP ASSOCIATE with an RFC 5905 request sent through the selected proxy. */
public final class SocksUdpProbe implements Closeable {
    private volatile boolean cancelled;
    private volatile Socket control;
    private volatile DatagramSocket datagram;

    public int measure(int proxyPort, String host, int port, int timeoutMs) throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            control = new Socket(Proxy.NO_PROXY);
            checkCancelled();
            control.connect(new InetSocketAddress("127.0.0.1", proxyPort), timeoutMs);
            control.setSoTimeout(timeoutMs);
            DataInputStream input = new DataInputStream(control.getInputStream());
            OutputStream output = control.getOutputStream();
            output.write(new byte[]{5, 1, 0});
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0)
                throw new IOException("SOCKS authentication failed");
            output.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0)
                throw new IOException("UDP ASSOCIATE is not supported by this proxy");
            InetSocketAddress relay = readAddress(input);
            InetAddress relayAddress = relay.getAddress();
            if (relayAddress == null || (!relayAddress.isAnyLocalAddress() && !relayAddress.isLoopbackAddress()))
                throw new IOException("Unexpected non-local SOCKS relay");
            relay = new InetSocketAddress("127.0.0.1", relay.getPort());
            if (relay.getPort() == 0) throw new IOException("Invalid UDP relay port");

            byte[] request = new byte[48];
            request[0] = 0x23; // NTP v4, client. A nonce correlates the originate timestamp.
            byte[] nonce = new byte[8];
            new SecureRandom().nextBytes(nonce);
            System.arraycopy(nonce, 0, request, 40, 8);
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(packet);
            data.write(new byte[]{0, 0, 0, 3});
            byte[] name = IDN.toASCII(host).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (name.length == 0 || name.length > 253 || port < 1 || port > 65535)
                throw new IOException("Invalid NTP destination");
            data.writeByte(name.length);
            data.write(name);
            data.writeShort(port);
            data.write(request);
            datagram = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            checkCancelled();
            datagram.connect(relay);
            datagram.setSoTimeout(Math.max(1, (int) ((deadline - System.nanoTime()) / 1_000_000L)));
            long start = System.nanoTime();
            datagram.send(new DatagramPacket(packet.toByteArray(), packet.size()));
            DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
            datagram.receive(reply);
            DataInputStream received = new DataInputStream(new ByteArrayInputStream(reply.getData(), 0, reply.getLength()));
            if (received.readUnsignedShort() != 0 || received.readUnsignedByte() != 0)
                throw new IOException("Invalid or fragmented SOCKS UDP response");
            InetSocketAddress origin = readAddress(received);
            if (origin.getPort() != port) throw new IOException("Unexpected UDP response port");
            byte[] ntp = new byte[received.available()];
            received.readFully(ntp);
            validateNtp(ntp, nonce);
            return Math.max(1, (int) ((System.nanoTime() - start) / 1_000_000L));
        } finally {
            close();
        }
    }

    static void validateNtp(byte[] reply, byte[] nonce) throws IOException {
        if (reply.length < 48 || (reply[0] & 7) != 4 || ((reply[0] >> 3) & 7) < 3
                || (reply[0] & 0xc0) == 0xc0 || (reply[1] & 255) == 0 || (reply[1] & 255) > 15
                || !Arrays.equals(Arrays.copyOfRange(reply, 24, 32), nonce))
            throw new IOException("Invalid NTP reply or server rate limit");
        boolean nonzero = false;
        for (int i = 40; i < 48; i++) nonzero |= reply[i] != 0;
        if (!nonzero) throw new IOException("Missing NTP transmit timestamp");
    }

    private static InetSocketAddress readAddress(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        int size = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? input.readUnsignedByte() : -1;
        if (size < 1) throw new IOException("Invalid SOCKS address");
        byte[] address = new byte[size];
        input.readFully(address);
        int port = input.readUnsignedShort();
        // Do not resolve hostnames on the device while decoding a relay response.
        return type == 3 ? InetSocketAddress.createUnresolved(new String(address, java.nio.charset.StandardCharsets.US_ASCII), port)
                : new InetSocketAddress(InetAddress.getByAddress(address), port);
    }

    private void checkCancelled() throws IOException {
        if (cancelled) throw new InterruptedIOException("Cancelled");
    }

    @Override public void close() {
        cancelled = true;
        Socket tcp = control;
        DatagramSocket udp = datagram;
        if (tcp != null) try { tcp.close(); } catch (IOException ignored) { }
        if (udp != null) udp.close();
    }
}
