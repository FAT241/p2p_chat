package common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Lịch sử theo người dùng, lưu trong PostgreSQL. */
public final class ChatDatabase {
    public record MessageRecord(long id, String room, String sender, String type, String content,
                                String attachmentName, long attachmentSize, String timestamp) {}
    public record UserRecord(String username, String ip, int port, String lastSeen) {}

    public static final class StorageException extends RuntimeException {
        private StorageException(Exception cause) {
            super("Lỗi PostgreSQL: " + cause.getMessage(), cause);
        }
    }

    private final String owner;
    private final Path legacyFile;

    private ChatDatabase(String owner, Path legacyFile) {
        this.owner = owner;
        this.legacyFile = legacyFile;
        initialize();
    }

    public static ChatDatabase defaultInstance(String scope) {
        String name = scope == null || scope.isBlank() ? "common" : scope.trim();
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (!safe.equals(name)) safe += "_" + Integer.toHexString(name.hashCode());
        return new ChatDatabase(name, Path.of(System.getProperty("p2p.data.dir", "."), "p2p_chat_" + safe + ".db"));
    }

    private static Connection connect() throws SQLException {
        String url = System.getenv().getOrDefault("P2P_DB_URL", "jdbc:postgresql://127.0.0.1:5432/p2p_chat");
        var properties = new Properties();
        properties.setProperty("user", System.getenv().getOrDefault("P2P_DB_USER", "postgres"));
        properties.setProperty("password", System.getenv().getOrDefault("P2P_DB_PASSWORD", "0905786526"));
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        return DriverManager.getConnection(url, properties);
    }

    private void initialize() {
        try (var connection = connect(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            // Serialize schema creation when several demo clients connect together.
            statement.execute("SELECT pg_advisory_xact_lock(192773241)");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    owner_name text NOT NULL, room text NOT NULL, sender text NOT NULL,
                    type text NOT NULL, content text NOT NULL, attachment_name text NOT NULL,
                    attachment_size bigint NOT NULL, sent_at timestamp NOT NULL DEFAULT localtimestamp
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS chat_messages_owner_id ON chat_messages (owner_name, id)");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS chat_users (
                    owner_name text NOT NULL, username text NOT NULL, ip text NOT NULL,
                    port integer NOT NULL, last_seen timestamp NOT NULL DEFAULT localtimestamp,
                    PRIMARY KEY (owner_name, username)
                )
                """);
            statement.execute("CREATE TABLE IF NOT EXISTS chat_imports (owner_name text PRIMARY KEY)");
            importLegacy(connection);
            connection.commit();
        } catch (SQLException | IOException | NumberFormatException ex) {
            throw new StorageException(ex);
        }
    }

    private void importLegacy(Connection connection) throws SQLException, IOException {
        if (!Files.isRegularFile(legacyFile)) return;
        try (var marker = prepare(connection, "INSERT INTO chat_imports VALUES (?) ON CONFLICT DO NOTHING", owner)) {
            if (marker.executeUpdate() == 0) return;
        }
        try (var reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\|", -1);
                if (fields.length >= 8 && "MSG".equals(fields[0])) {
                    try (var insert = prepare(connection, """
                        INSERT INTO chat_messages (owner_name, room, sender, type, content, attachment_name, attachment_size, sent_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(NULLIF(?, '')::timestamp, localtimestamp))
                        """, owner, unescape(fields[2]), unescape(fields[3]), unescape(fields[4]),
                        unescape(fields[5]), unescape(fields[6]), Long.parseLong(fields[7]),
                        fields.length > 8 ? fields[8] : "")) {
                        insert.executeUpdate();
                    }
                } else if (fields.length >= 5 && "USER".equals(fields[0])) {
                    try (var insert = prepare(connection, UPSERT_USER, owner, unescape(fields[1]), fields[2],
                        Integer.parseInt(fields[3]), fields[4])) {
                        insert.executeUpdate();
                    }
                }
            }
        }
    }

    private static final String UPSERT_USER = """
        INSERT INTO chat_users (owner_name, username, ip, port, last_seen)
        VALUES (?, ?, ?, ?, COALESCE(NULLIF(?, '')::timestamp, localtimestamp))
        ON CONFLICT (owner_name, username) DO UPDATE SET
            ip = EXCLUDED.ip, port = EXCLUDED.port, last_seen = EXCLUDED.last_seen
        """;

    public void recordMessage(String room, String sender, String type, String content,
                              String attachmentName, long attachmentSize) {
        update("""
            INSERT INTO chat_messages (owner_name, room, sender, type, content, attachment_name, attachment_size)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, owner, room, sender, type, content, attachmentName == null ? "" : attachmentName, attachmentSize);
    }

    public void recordUser(String username, String ip, int port) {
        update(UPSERT_USER, owner, username, ip, port, "");
    }

    public List<MessageRecord> getRecentMessages(String room, int limit) {
        var result = readMessages("""
            AND (CAST(? AS text) IS NULL OR room = ? OR type = 'broadcast')
            ORDER BY id DESC LIMIT ?
            """, room, room, limit);
        return result.reversed();
    }

    public List<MessageRecord> search(String query) {
        if (query == null || query.isBlank()) return allMessages();
        // Keep Java's Unicode case matching and literal substring semantics.
        String term = query.toLowerCase(Locale.ROOT);
        return allMessages().stream().filter(msg -> msg.content().toLowerCase(Locale.ROOT).contains(term)
            || msg.sender().toLowerCase(Locale.ROOT).contains(term)
            || msg.attachmentName().toLowerCase(Locale.ROOT).contains(term)).toList();
    }

    public List<MessageRecord> allMessages() {
        return readMessages("ORDER BY id");
    }

    private List<MessageRecord> readMessages(String clause, Object... filters) {
        try (var connection = connect(); var statement = connection.prepareStatement("""
            SELECT id, room, sender, type, content, attachment_name, attachment_size,
                to_char(sent_at, 'YYYY-MM-DD HH24:MI:SS')
            FROM chat_messages WHERE owner_name = ?
            """ + clause)) {
            statement.setString(1, owner);
            for (int i = 0; i < filters.length; i++) statement.setObject(i + 2, filters[i]);
            var messages = new ArrayList<MessageRecord>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    messages.add(new MessageRecord(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getLong(7), rows.getString(8)));
                }
            }
            return messages;
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    public Map<String, UserRecord> allUsers() {
        try (var connection = connect(); var statement = prepare(connection, """
            SELECT username, ip, port, to_char(last_seen, 'YYYY-MM-DD HH24:MI:SS')
            FROM chat_users WHERE owner_name = ?
            """, owner); var rows = statement.executeQuery()) {
            var users = new HashMap<String, UserRecord>();
            while (rows.next()) {
                users.put(rows.getString(1), new UserRecord(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4)));
            }
            return users;
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    public void clear() {
        try (var connection = connect();
             var messages = prepare(connection, "DELETE FROM chat_messages WHERE owner_name = ?", owner);
             var users = prepare(connection, "DELETE FROM chat_users WHERE owner_name = ?", owner)) {
            connection.setAutoCommit(false);
            messages.executeUpdate();
            users.executeUpdate();
            connection.commit();
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void update(String sql, Object... values) {
        try (var connection = connect(); var statement = prepare(connection, sql, values)) {
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        } catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }

    private Path voiceFolder() {
        return legacyFile.resolveSibling(legacyFile.getFileName() + ".media");
    }

    public String saveVoice(byte[] wav) throws IOException {
        Path folder = voiceFolder();
        Files.createDirectories(folder);
        String name = UUID.randomUUID() + ".wav";
        Files.write(folder.resolve(name), wav, StandardOpenOption.CREATE_NEW);
        return name;
    }

    public byte[] readVoice(String name) throws IOException {
        if (name == null || !name.matches("[a-fA-F0-9-]{36}\\.wav")) throw new IOException("Không có dữ liệu âm thanh đã lưu");
        Path file = voiceFolder().resolve(name);
        if (Files.size(file) > Protocol.MAX_VOICE_BYTES) throw new IOException("File âm thanh quá lớn");
        return Files.readAllBytes(file);
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
