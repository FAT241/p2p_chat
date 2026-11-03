package common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cơ sở dữ liệu lưu trữ lịch sử tin nhắn, danh bạ người dùng và phòng chat.
 * Tự động tạo và lưu trữ bền vững vào file database (chat_history.db).
 */
public final class ChatDatabase {
    public record MessageRecord(
        long id,
        String room,
        String sender,
        String type,           // "text", "voice", "file", "broadcast", "video"
        String content,
        String attachmentName,
        long attachmentSize,
        String timestamp
    ) {}

    public record UserRecord(String username, String ip, int port, String lastSeen) {}

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path dbFile;
    private final List<MessageRecord> messages = new ArrayList<>();
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final Set<String> rooms = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextId = new AtomicLong(1);

    public ChatDatabase(Path dbFile) {
        this.dbFile = dbFile.toAbsolutePath().normalize();
        load();
    }

    public static ChatDatabase defaultInstance(String scope) {
        String name = scope == null || scope.isBlank() ? "common" : scope.trim();
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (!safe.equals(name)) safe += "_" + Integer.toHexString(name.hashCode());
        return new ChatDatabase(Path.of(System.getProperty("p2p.data.dir", "."), "p2p_chat_" + safe + ".db"));
    }

    private synchronized void load() {
        if (!Files.exists(dbFile)) return;
        try (var reader = Files.newBufferedReader(dbFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 8 && "MSG".equals(parts[0])) {
                    long id = Long.parseLong(parts[1]);
                    if (id >= nextId.get()) nextId.set(id + 1);
                    String room = unescape(parts[2]);
                    String sender = unescape(parts[3]);
                    String type = unescape(parts[4]);
                    String content = unescape(parts[5]);
                    String attName = unescape(parts[6]);
                    long attSize = Long.parseLong(parts[7]);
                    String time = parts.length > 8 ? parts[8] : "";
                    messages.add(new MessageRecord(id, room, sender, type, content, attName, attSize, time));
                    rooms.add(room);
                } else if (parts.length >= 5 && "USER".equals(parts[0])) {
                    String user = unescape(parts[1]);
                    users.put(user, new UserRecord(user, parts[2], Integer.parseInt(parts[3]), parts[4]));
                }
            }
        } catch (Exception ex) {
            System.err.println("Lỗi đọc database: " + ex.getMessage());
        }
    }

    private synchronized void append(String line) {
        try {
            if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
            Files.writeString(dbFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("Lỗi ghi database: " + ex.getMessage());
        }
    }

    public synchronized MessageRecord recordMessage(String room, String sender, String type, String content,
                                                   String attachmentName, long attachmentSize) {
        long id = nextId.getAndIncrement();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        var record = new MessageRecord(id, room, sender, type, content,
            attachmentName == null ? "" : attachmentName, attachmentSize, time);
        messages.add(record);
        rooms.add(room);

        String line = String.format("MSG|%d|%s|%s|%s|%s|%s|%d|%s",
            id, escape(room), escape(sender), escape(type), escape(content),
            escape(attachmentName == null ? "" : attachmentName), attachmentSize, time);
        append(line);
        return record;
    }

    public synchronized void recordUser(String username, String ip, int port) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        users.put(username, new UserRecord(username, ip, port, time));
        append(String.format("USER|%s|%s|%d|%s", escape(username), ip, port, time));
    }

    public synchronized List<MessageRecord> getRecentMessages(String room, int limit) {
        var result = new ArrayList<MessageRecord>();
        for (var msg : messages) {
            if (room == null || room.equals(msg.room()) || "broadcast".equals(msg.type())) {
                result.add(msg);
            }
        }
        if (result.size() > limit) {
            return new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    public synchronized List<MessageRecord> search(String query) {
        if (query == null || query.isBlank()) return List.copyOf(messages);
        String q = query.toLowerCase(Locale.ROOT);
        var result = new ArrayList<MessageRecord>();
        for (var msg : messages) {
            if (msg.content().toLowerCase(Locale.ROOT).contains(q) ||
                msg.sender().toLowerCase(Locale.ROOT).contains(q) ||
                msg.attachmentName().toLowerCase(Locale.ROOT).contains(q)) {
                result.add(msg);
            }
        }
        return result;
    }

    public synchronized List<MessageRecord> allMessages() {
        return List.copyOf(messages);
    }

    public synchronized Map<String, UserRecord> allUsers() {
        return Map.copyOf(users);
    }

    public synchronized Set<String> allRooms() {
        return Set.copyOf(rooms);
    }

    public synchronized void clear() {
        messages.clear();
        users.clear();
        rooms.clear();
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException ignored) {}
    }

    public Path getDbFile() {
        return dbFile;
    }

    public String saveVoice(byte[] wav) throws IOException {
        Path folder = dbFile.resolveSibling(dbFile.getFileName() + ".media");
        Files.createDirectories(folder);
        String name = UUID.randomUUID() + ".wav";
        Files.write(folder.resolve(name), wav, StandardOpenOption.CREATE_NEW);
        return name;
    }
    public byte[] readVoice(String name) throws IOException {
        if (name == null || !name.matches("[a-fA-F0-9-]{36}\\.wav")) throw new IOException("Không có dữ liệu âm thanh đã lưu");
        Path file = dbFile.resolveSibling(dbFile.getFileName() + ".media").resolve(name);
        if (Files.size(file) > Protocol.MAX_VOICE_BYTES) throw new IOException("File âm thanh quá lớn");
        return Files.readAllBytes(file);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        var result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 'p' -> result.append('|');
                    case '\\' -> result.append('\\');
                    default -> result.append('\\').append(next);
                }
            } else result.append(c);
        }
        return result.toString();
    }
}
