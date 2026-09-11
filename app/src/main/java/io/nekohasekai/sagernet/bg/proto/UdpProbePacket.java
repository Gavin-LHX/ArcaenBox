package io.nekohasekai.sagernet.bg.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** Independent request IDs prevent unrelated packets from counting as successful probes. */
public final class UdpProbePacket {
    private static final byte[] MAGIC = {0,-1,-1,0,-2,-2,-2,-2,-3,-3,-3,-3,0x12,0x34,0x56,0x78};
    public final byte[] request;
    private final String mode;
    public UdpProbePacket(String mode) throws IOException {
        this.mode=mode;
        SecureRandom random=new SecureRandom();
        switch(mode) {
            case "ntp":
                request=new byte[48];random.nextBytes(request);Arrays.fill(request,0,40,(byte)0);request[0]=0x23;break;
            case "dns":
                request=new byte[]{0,0,1,0,0,1,0,0,0,0,0,0,7,'e','x','a','m','p','l','e',3,'c','o','m',0,0,1,0,1};
                byte[] id=new byte[2];random.nextBytes(id);System.arraycopy(id,0,request,0,2);break;
            case "stun":
                request=new byte[20];random.nextBytes(request);request[0]=0;request[1]=1;request[2]=request[3]=0;
                request[4]=0x21;request[5]=0x12;request[6]=(byte)0xa4;request[7]=0x42;break;
            case "mcbe":
                request=new byte[33];random.nextBytes(request);request[0]=1;System.arraycopy(MAGIC,0,request,9,16);break;
            default: throw new IOException("Unsupported UDP probe protocol");
        }
    }

    public void validate(byte[] reply) throws IOException {
        boolean valid=false;
        switch(mode) {
            case "ntp": SocksUdpProbe.validateNtp(reply,Arrays.copyOfRange(request,40,48));return;
            case "dns":
                // The echoed question plus a complete A answer must match, not merely the QR bit.
                valid=reply.length>=request.length && reply[0]==request[0] && reply[1]==request[1]
                    && (reply[2]&0xfa)==0x80 && (reply[3]&15)==0 && u16(reply,4)==1 && u16(reply,6)>0
                    && Arrays.equals(Arrays.copyOfRange(reply,12,request.length),Arrays.copyOfRange(request,12,request.length));
                if (valid) {
                    int offset=request.length;boolean address=false;
                    for(int i=0;i<u16(reply,6);i++) {
                        offset=skipName(reply,offset);
                        if(offset+10>reply.length) throw new IOException("Truncated DNS answer");
                        int length=u16(reply,offset+8);
                        if(offset+10+length>reply.length) throw new IOException("Truncated DNS data");
                        address |= u16(reply,offset)==1 && u16(reply,offset+2)==1 && length==4;
                        offset+=10+length;
                    }
                    valid=address;
                }
                break;
            case "stun":
                valid=reply.length>=20 && u16(reply,0)==0x101 && u16(reply,2)==reply.length-20
                    && (u16(reply,2)&3)==0 && Arrays.equals(Arrays.copyOfRange(reply,4,20),Arrays.copyOfRange(request,4,20));
                if(valid) {
                    boolean mapped=false;int offset=20;
                    while(offset+4<=reply.length) {
                        int type=u16(reply,offset),length=u16(reply,offset+2);
                        if(offset+4+length>reply.length) throw new IOException("Truncated STUN attribute");
                        if(type==1 || type==0x20) {
                            mapped |= length==8 && reply[offset+5]==1 || length==20 && reply[offset+5]==2;
                        }
                        offset+=4+((length+3)&~3);
                    }
                    valid=mapped && offset==reply.length;
                }
                break;
            case "mcbe":
                valid=reply.length>=40 && reply[0]==0x1c
                    && Arrays.equals(Arrays.copyOfRange(reply,1,9),Arrays.copyOfRange(request,1,9))
                    && Arrays.equals(Arrays.copyOfRange(reply,17,33),MAGIC)
                    && u16(reply,33)==reply.length-35
                    && new String(reply,35,reply.length-35,StandardCharsets.UTF_8).startsWith("MCPE;");
                break;
        }
        if(!valid) throw new IOException("Invalid "+mode.toUpperCase(java.util.Locale.ROOT)+" response");
    }
    private static int u16(byte[] data,int offset) {return ((data[offset]&255)<<8)|(data[offset+1]&255);}
    private static int skipName(byte[] data,int offset) throws IOException {
        while(offset<data.length) {
            int length=data[offset++]&255;
            if(length==0)return offset;
            if((length&0xc0)==0xc0) {
                if(offset>=data.length || ((length&63)<<8 | (data[offset]&255))>=data.length) break;
                return offset+1;
            }
            if(length>63 || offset+length>data.length)break;
            offset+=length;
        }
        throw new IOException("Invalid DNS answer name");
    }
}
