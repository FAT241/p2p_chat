package common;

import java.io.*;
import java.util.*;

/** Versioned, bounded UTF-8 key/value frames. File bytes follow a file_ok frame. */
public final class Protocol {
    public static final int MAX_VOICE_BYTES = 16_000 * 2 * 300 + 44;
    private static final int MAGIC = 0x50325031; // P2P1
    private static final int MAX_FRAME = 1_048_576;
    private Protocol() {}

    public static Map<String, String> message(String... pairs) {
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    public static void write(OutputStream output, Map<String, String> message) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var payload = new DataOutputStream(bytes);
        payload.writeInt(message.size());
        for (var entry : message.entrySet()) {
            payload.writeUTF(entry.getKey());
            payload.writeUTF(entry.getValue());
        }
        if (bytes.size() > MAX_FRAME) throw new IOException("Bản tin quá lớn");
        var out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeInt(bytes.size());
        bytes.writeTo(out);
        out.flush();
    }

    public static Map<String, String> read(InputStream input) throws IOException {
        var in = new DataInputStream(input);
        if (in.readInt() != MAGIC) throw new IOException("Sai phiên bản giao thức");
        int length = in.readInt();
        if (length < 4 || length > MAX_FRAME) throw new IOException("Sai kích thước bản tin");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Bản tin bị ngắt");
        var payload = new DataInputStream(new ByteArrayInputStream(bytes));
        int count = payload.readInt();
        if (count < 0 || count > 16000) throw new IOException("Quá nhiều trường");
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) result.put(payload.readUTF(), payload.readUTF());
        if (payload.available() != 0) throw new IOException("Dữ liệu dư trong bản tin");
        return result;
    }

    public static Map<String, String> rows(List<Map<String, String>> rows) {
        var message = message("ok", "true", "count", Integer.toString(rows.size()));
        for (int i = 0; i < rows.size(); i++) {
            String prefix = i + ".";
            rows.get(i).forEach((key, value) -> message.put(prefix + key, value));
        }
        return message;
    }

    public static List<Map<String, String>> rows(Map<String, String> message) {
        var rows = new ArrayList<Map<String, String>>();
        int count = Integer.parseInt(message.getOrDefault("count", "0"));
        if (count < 0 || count > 16000) throw new IllegalArgumentException("Sai số kết quả");
        for (int i = 0; i < count; i++) {
            String prefix = i + ".";
            var row = new LinkedHashMap<String, String>();
            message.forEach((key, value) -> { if (key.startsWith(prefix)) row.put(key.substring(prefix.length()), value); });
            rows.add(row);
        }
        return rows;
    }

    public static String required(Map<String, String> message, String key) {
        String value = message.getOrDefault(key, "").strip();
        if (value.isEmpty() || value.length() > 200) throw new IllegalArgumentException("Thiếu hoặc quá dài: " + key);
        return value;
    }
}
