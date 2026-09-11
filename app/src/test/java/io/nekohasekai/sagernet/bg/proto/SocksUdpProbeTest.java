package io.nekohasekai.sagernet.bg.proto;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SocksUdpProbeTest {
    @Test public void udpTravelsThroughAssociateAndMatchesNonce() throws Exception {
        try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<?> server = workers.submit(() -> {
                    try (Socket socket = tcp.accept()) {
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        assertArrayEquals(new byte[]{5,1,0}, in.readNBytes(3));
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream()); out.write(new byte[]{5,0});
                        assertArrayEquals(new byte[]{5,3,0,1,0,0,0,0,0,0}, in.readNBytes(10));
                        out.write(new byte[]{5,0,0,1,127,0,0,1}); out.writeShort(udp.getLocalPort());
                        DatagramPacket request = new DatagramPacket(new byte[2048],2048); udp.setSoTimeout(2000); udp.receive(request);
                        byte[] data = request.getData(); assertEquals(3, data[3]);
                        int header = 5+(data[4]&255)+2;
                        assertEquals("ntp.example", new String(data,5,data[4]&255,java.nio.charset.StandardCharsets.US_ASCII));
                        byte[] reply = new byte[58]; reply[3]=1; reply[4]=1; reply[5]=1; reply[6]=1; reply[7]=1; reply[9]=123;
                        reply[10]=0x24; reply[11]=1; reply[57]=1;
                        System.arraycopy(data,header+40,reply,34,8);
                        udp.send(new DatagramPacket(reply,reply.length,request.getSocketAddress()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                int latency = new SocksUdpProbe().measure(tcp.getLocalPort(),"ntp.example",123,3000);
                assertTrue(latency > 0 && latency < 3000); server.get(3,TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
        }
    }

    @Test public void cancellationClosesWaitingHandshake() throws Exception {
        try (ServerSocket listener = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor(); SocksUdpProbe probe = new SocksUdpProbe();
            try {
                Future<?> measurement = executor.submit(() -> {
                    try { probe.measure(listener.getLocalPort(),"ntp.example",123,10000); fail("Expected cancellation"); }
                    catch (IOException expected) { }
                });
                try (Socket accepted = listener.accept()) { probe.close(); measurement.get(2,TimeUnit.SECONDS); }
            } finally { probe.close(); executor.shutdownNow(); }
        }
    }

    @Test(expected = IOException.class) public void invalidNtpRepliesAreRejected() throws Exception {
        SocksUdpProbe.validateNtp(new byte[48],new byte[8]);
    }
    @Test(expected = IOException.class) public void differentNonceIsRejected() throws Exception {
        byte[] data = new byte[48]; data[0]=0x24; data[1]=1; data[47]=1;
        SocksUdpProbe.validateNtp(data,new byte[]{1,2,3,4,5,6,7,8});
    }
}
