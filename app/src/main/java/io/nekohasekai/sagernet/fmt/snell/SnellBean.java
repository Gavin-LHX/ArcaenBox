package io.nekohasekai.sagernet.fmt.snell;

import androidx.annotation.NonNull;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SnellBean extends AbstractBean {
    public String psk;
    public Integer version;
    public Boolean udp;
    public Boolean reuse;
    public String obfs;
    public String obfsHost;
    public String mode;

    @Override public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (psk == null) psk = "";
        if (version == null) version = 4;
        if (udp == null) udp = true;
        if (reuse == null) reuse = false;
        if (obfs == null) obfs = "none";
        if (obfsHost == null) obfsHost = "";
        if (mode == null) mode = "default";
    }

    @Override public String network() { return Boolean.TRUE.equals(udp) ? "tcp,udp" : "tcp"; }

    @Override public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(psk);
        output.writeInt(version);
        output.writeBoolean(udp);
        output.writeBoolean(reuse);
        output.writeString(obfs);
        output.writeString(obfsHost);
        output.writeString(mode);
    }

    @Override public void deserialize(ByteBufferInput input) {
        int format = input.readInt();
        if (format < 0 || format > 1) throw new IllegalArgumentException("Unsupported Snell profile format");
        super.deserialize(input);
        psk = input.readString();
        version = input.readInt();
        udp = input.readBoolean();
        reuse = input.readBoolean();
        obfs = input.readString();
        obfsHost = input.readString();
        mode = format >= 1 ? input.readString() : "default";
    }

    @NonNull @Override public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull @Override public SnellBean newInstance() { return new SnellBean(); }
        @Override public SnellBean[] newArray(int size) { return new SnellBean[size]; }
    };
}
