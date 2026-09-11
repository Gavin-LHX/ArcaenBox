package io.nekohasekai.sagernet.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

public final class ResourceFiles {
    public static final long LIMIT = 100L * 1024 * 1024;
    private ResourceFiles() {}

    public static boolean validName(String name) {
        return name != null && name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}\\.(db|srs|json)")
                && !name.contains("..");
    }

    public static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            total += n;
            if (total > LIMIT) throw new IOException("Resource exceeds 100 MiB");
            output.write(buffer, 0, n);
        }
        if (total == 0) throw new IOException("Empty resource");
    }

    public static void validate(File file, String name) throws IOException {
        if (!validName(name) || file.length() == 0 || file.length() > LIMIT) throw new IOException("Invalid resource");
        if (name.endsWith(".json")) {
            if (file.length() > 16 * 1024 * 1024) throw new IOException("Source rule set exceeds 16 MiB");
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                int version = root.get("version").getAsInt();
                if (version < 1 || version > 5 || !root.get("rules").isJsonArray()) throw new IOException("Invalid rule set");
            } catch (RuntimeException e) { throw new IOException("Invalid source rule set", e); }
        } else if (name.endsWith(".srs")) {
            try (InputStream input = new FileInputStream(file)) {
                if (input.read() != 'S' || input.read() != 'R' || input.read() != 'S') throw new IOException("Not SRS");
                int version = input.read();
                if (version < 1 || version > 5) throw new IOException("Unsupported SRS version");
                // Validate compressed data before replacing a usable local rule set.
                try (InflaterInputStream compressed = new InflaterInputStream(input)) {
                    copy(compressed, new OutputStream() { public void write(int b) {} public void write(byte[] b, int o, int n) {} });
                }
            }
        } else {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (!name.equals("geosite.db")) {
                    int length = (int) Math.min(131072, input.length());
                    byte[] tail = new byte[length];
                    input.seek(input.length() - length);
                    input.readFully(tail);
                    if (new String(tail, StandardCharsets.ISO_8859_1).contains("\u00ab\u00cd\u00efMaxMind.com")) return;
                    if (name.equals("geoip.db")) throw new IOException("Not a sing-box GeoIP database");
                }
                input.seek(0);
                if (input.read() != 0) throw new IOException("Not a sing-box Geosite database");
                long count = unsigned(input);
                if (count < 1 || count > 100000) throw new IOException("Invalid Geosite index");
                long[] offsets = new long[(int) count], sizes = new long[(int) count];
                for (int i = 0; i < count; i++) {
                    skipString(input);
                    offsets[i] = unsigned(input);
                    sizes[i] = unsigned(input);
                    if (sizes[i] > 10000000) throw new IOException("Invalid Geosite entry count");
                }
                long metadata = input.getFilePointer();
                for (int i = 0; i < count; i++) {
                    long start = metadata + offsets[i];
                    if (start < metadata || start > input.length()) throw new IOException("Invalid Geosite offset");
                    input.seek(start);
                    for (long n = 0; n < sizes[i]; n++) {
                        int type = input.read();
                        if (type < 0 || type > 3) throw new IOException("Invalid Geosite item");
                        skipString(input);
                    }
                }
            }
        }
    }

    private static void skipString(RandomAccessFile file) throws IOException {
        long size = unsigned(file);
        long next = file.getFilePointer() + size;
        if (size > 65536 || next > file.length()) throw new IOException("Invalid Geosite string");
        file.seek(next);
    }

    private static long unsigned(RandomAccessFile file) throws IOException {
        long value = 0;
        for (int i = 0; i < 5; i++) {
            int b = file.readUnsignedByte();
            value |= (long) (b & 127) << (7 * i);
            if ((b & 128) == 0) return value;
        }
        throw new IOException("Invalid Geosite varint");
    }
}
