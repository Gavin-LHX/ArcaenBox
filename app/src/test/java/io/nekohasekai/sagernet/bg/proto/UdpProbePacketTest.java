package io.nekohasekai.sagernet.bg.proto;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class UdpProbePacketTest {
    static byte[] reply(UdpProbePacket packet) {
        byte[] q=packet.request;
        if(q.length==29) {
            byte[] r=Arrays.copyOf(q,q.length+16);r[2]=(byte)0x81;r[3]=(byte)0x80;r[7]=1;
            byte[] a={(byte)0xc0,12,0,1,0,1,0,0,0,30,0,4,1,1,1,1};System.arraycopy(a,0,r,q.length,16);return r;
        }
        if(q.length==20) {
            byte[] r=Arrays.copyOf(q,32);r[0]=1;r[3]=12;r[21]=0x20;r[23]=8;r[25]=1;r[27]=80;r[31]=1;return r;
        }
        byte[] text="MCPE;fixture;1;1;0;20".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] r=new byte[35+text.length];r[0]=0x1c;System.arraycopy(q,1,r,1,8);System.arraycopy(q,9,r,17,16);
        r[34]=(byte)text.length;System.arraycopy(text,0,r,35,text.length);return r;
    }
    @Test public void successfulDnsStunAndBedrockRepliesRequireMatchingRequests() throws Exception {
        for(String mode:new String[]{"dns","stun","mcbe"}) {
            UdpProbePacket packet=new UdpProbePacket(mode);byte[] r=reply(packet);packet.validate(r);
            UdpProbePacket other=new UdpProbePacket(mode);
            try {other.validate(r);fail("Unrelated "+mode+" packet accepted");}catch(IOException expected) { }
            try {packet.validate(Arrays.copyOf(r,r.length-1));fail("Truncated "+mode+" accepted");}catch(IOException expected) { }
        }
    }
    @Test public void errorsAndEmptyRepliesAreNotLatencySuccess() throws Exception {
        UdpProbePacket dns=new UdpProbePacket("dns");byte[] d=reply(dns);d[3]|=3;
        try {dns.validate(d);fail();}catch(IOException expected) { }
        UdpProbePacket stun=new UdpProbePacket("stun");byte[] s=reply(stun);s[1]=0x11;
        try {stun.validate(s);fail();}catch(IOException expected) { }
        s=Arrays.copyOf(stun.request,20);s[0]=1;
        try {stun.validate(s);fail("STUN reply without mapped address accepted");}catch(IOException expected) { }
    }
    @Test public void protocolDefaultsAndLegacyCustomPortsArePreserved() {
        assertEquals(18892,UdpTestTarget.parse("127.0.0.1",18892).port);
        assertEquals(53,UdpTestTarget.parse("dns:1.1.1.1",18892).port);
        assertEquals(19302,UdpTestTarget.parse("stun:stun.l.google.com:19302",123).port);
        assertEquals(19132,UdpTestTarget.parse("mcbe:example.com",123).port);
        UdpTestTarget target=UdpTestTarget.parse("dns:[2606:4700:4700::1111]:5353",123);
        assertEquals(5353,target.port);assertEquals("2606:4700:4700::1111",target.host);
        for(String value:new String[]{"", "dns:", "https://example.com", "dns:a:70000", "stun:bad host", "dns:[::1]garbage", "quic:host"}) {
            try {UdpTestTarget.parse(value,123);fail(value);}catch(IllegalArgumentException expected) { }
        }
    }
}
