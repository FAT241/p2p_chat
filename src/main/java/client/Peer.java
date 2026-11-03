package client;

import common.Protocol;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Networking service, independent of Swing. Each Peer represents one login session. */
public final class Peer implements AutoCloseable {
    public static final int MAX_VOICE_BYTES = Protocol.MAX_VOICE_BYTES;
    public record CallReply(String ip, int videoPort, int audioPort) {}
    public static final class CallOffer {
        public final String id, sender, ip;
        public final int peerPort, videoPort, audioPort;
        public final boolean videoEnabled;
        private final CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        CallOffer(Map<String, String> message, String ip) {
            this.ip = ip;
            id = Protocol.required(message, "id");
            sender = Protocol.required(message, "from");
            peerPort = validPort(message, "port");
            videoPort = validPort(message, "vport");
            audioPort = validPort(message, "aport");
            videoEnabled = Boolean.parseBoolean(message.getOrDefault("video", "true"));
        }
        public boolean accept(CallSession media) {
            if (media.isClosed() || !id.equals(media.id)) return false;
            return answer.complete(Protocol.message("type", "call_accept", "id", id,
                "vport", "" + media.video.getLocalPort(), "aport", "" + media.audio.getLocalPort()));
        }
        public void reject(String reason) {
            answer.complete(Protocol.message("type", "call_reject", "id", id, "reason", reason));
        }
        public boolean pending() { return !answer.isDone(); }
    }
    private static int validPort(Map<String, String> message, String key) {
        int value = Integer.parseInt(message.getOrDefault(key, "0"));
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Cổng không hợp lệ: " + key);
        return value;
    }
    public interface Events {
        void chat(String room, String sender, String text);
        void status(String text);
        default void voice(String room, String sender, byte[] wavData) {}
        default void attachment(String room, String sender, String filename, long size, String ip, int port) {}
        default void broadcast(String sender, String text) {}
        default void broadcast(String id, String sender, String text) { broadcast(sender, text); }
        default void incomingCall(CallOffer offer) { offer.reject("Không hỗ trợ cuộc gọi"); }
        default void callEnded(String id) {}
    }
    private final Events events;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Path> shared = new ConcurrentHashMap<>();
    private final Set<Socket> transfers = ConcurrentHashMap.newKeySet();
    private final Map<String, CallOffer> offers = new ConcurrentHashMap<>();
    private final Map<String, Socket> outgoingCalls = new ConcurrentHashMap<>();
    private final Map<String, Long> endedCalls = new ConcurrentHashMap<>();
    private volatile Socket server;
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private String name;

    public Peer(Events events) { this.events = events; }
    public String name() { return name; }
    public int port() { return listener != null ? listener.getLocalPort() : 0; }
    public InetAddress localAddress() { return server.getLocalAddress(); }
    public Map<String, Path> shared() { return Map.copyOf(shared); }

    public void connect(String host, int port, String username) throws IOException {
        if (closed || server != null) throw new IOException("Hãy tạo phiên kết nối mới");
        name = username.strip();
        if (name.isEmpty()) throw new IOException("Nhập tên người dùng");
        try {
            if (InetAddress.getByName(host).isAnyLocalAddress())
                throw new IOException("0.0.0.0/:: là địa chỉ lắng nghe. Dùng 127.0.0.1 trên máy này hoặc IP LAN của server.");
            listener = new ServerSocket(0);
            server = new Socket();
            server.connect(new InetSocketAddress(host, port), 4000);
            server.setSoTimeout(4000);
            call("register", "name", name, "port", "" + listener.getLocalPort());
            workers.submit(this::accept);
        } catch (IOException | RuntimeException ex) { close(); throw ex; }
    }

    public synchronized Map<String, String> call(String command, String... pairs) throws IOException {
        if (closed || server == null || server.isClosed()) throw new IOException("Mất kết nối server; P2P vẫn dùng địa chỉ đã lưu");
        var request = Protocol.message(pairs);
        request.put("cmd", command);
        Map<String, String> reply;
        try {
            Protocol.write(server.getOutputStream(), request);
            reply = Protocol.read(server.getInputStream());
        } catch (IOException ex) {
            try { server.close(); } catch (IOException ignored) { }
            throw ex;
        }
        if (!"true".equals(reply.get("ok"))) throw new IOException(reply.getOrDefault("error", "Yêu cầu thất bại"));
        return reply;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(60_000);
                transfers.add(socket);
                if (closed) socket.close();
                else workers.submit(() -> incoming(socket));
            } catch (IOException | RejectedExecutionException ex) {
                if (!closed) events.status("Lỗi cổng P2P: " + ex.getMessage());
            }
        }
    }

    private void incoming(Socket socket) {
        try (socket) {
            var message = Protocol.read(socket.getInputStream());
            String type = message.getOrDefault("type", "");
            if ("chat".equals(type)) {
                events.chat(Protocol.required(message, "room"), Protocol.required(message, "from"), message.getOrDefault("text", ""));
            } else if ("voice_binary".equals(type)) {
                String room = Protocol.required(message, "room");
                String from = Protocol.required(message, "from");
                int size = Integer.parseInt(message.getOrDefault("size", "-1"));
                if (size < 44 || size > MAX_VOICE_BYTES) throw new IOException("Tin nhắn thoại tối đa 5 phút");
                byte[] data = socket.getInputStream().readNBytes(size);
                if (data.length != size) throw new EOFException("Tin nhắn thoại bị ngắt");
                events.voice(room, from, data);
                Protocol.write(socket.getOutputStream(), Protocol.message("type", "voice_ok"));
            } else if ("voice".equals(type)) {
                String room = Protocol.required(message, "room");
                String from = Protocol.required(message, "from");
                String b64 = message.getOrDefault("audio", "");
                byte[] data = Base64.getDecoder().decode(b64);
                events.voice(room, from, data);
            } else if ("attachment".equals(type)) {
                String room = Protocol.required(message, "room");
                String from = Protocol.required(message, "from");
                String filename = Protocol.required(message, "filename");
                long size = Long.parseLong(message.getOrDefault("size", "0"));
                String ip = message.getOrDefault("ip", socket.getInetAddress().getHostAddress());
                int port = Integer.parseInt(message.getOrDefault("port", "0"));
                events.attachment(room, from, filename, size, ip, port);
            } else if ("broadcast".equals(type)) {
                events.broadcast(message.getOrDefault("id", UUID.randomUUID().toString()),
                    Protocol.required(message, "from"), message.getOrDefault("text", ""));
            } else if ("call_offer".equals(type)) {
                var offer = new CallOffer(message, socket.getInetAddress().getHostAddress());
                offers.put(offer.id, offer);
                try {
                    if (endedCalls.containsKey(offer.id)) offer.reject("Cuộc gọi đã hủy");
                    else events.incomingCall(offer);
                    Map<String, String> response;
                    try { response = offer.answer.get(40, TimeUnit.SECONDS); }
                    catch (TimeoutException ex) {
                        offer.reject("Hết thời gian chờ nhận cuộc gọi");
                        events.callEnded(offer.id);
                        response = offer.answer.join();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Cuộc gọi đã đóng", ex);
                    } catch (ExecutionException ex) { throw new IOException(ex); }
                    Protocol.write(socket.getOutputStream(), response);
                } finally { offers.remove(offer.id, offer); }
            } else if ("call_end".equals(type)) {
                String id = Protocol.required(message, "id");
                long now = System.nanoTime();
                endedCalls.entrySet().removeIf(e -> now - e.getValue() > TimeUnit.MINUTES.toNanos(2));
                endedCalls.put(id, now);
                CallOffer pending = offers.get(id);
                if (pending != null) pending.reject("Đối phương đã hủy cuộc gọi");
                events.callEnded(id);
            } else if ("file".equals(type)) {
                Path path = shared.get(message.get("filename"));
                if (path == null || !Files.isRegularFile(path)) {
                    Protocol.write(socket.getOutputStream(), Protocol.message("type", "file_nak"));
                    return;
                }
                try (var input = Files.newInputStream(path)) {
                    long size = Files.size(path);
                    Protocol.write(socket.getOutputStream(), Protocol.message("type", "file_ok", "size", "" + size));
                    copy(input, socket.getOutputStream(), size, value -> {});
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            if (!closed) events.status("P2P: " + ex.getMessage());
        } finally { transfers.remove(socket); }
    }

    private Socket dial(Map<String, String> endpoint) throws IOException {
        if (closed) throw new IOException("Phiên đã đóng");
        Socket socket = new Socket();
        transfers.add(socket);
        try {
            socket.connect(new InetSocketAddress(endpoint.get("ip"), Integer.parseInt(endpoint.get("port"))), 4000);
            socket.setSoTimeout(60_000);
            if (closed) throw new IOException("Phiên đã đóng");
            return socket;
        } catch (IOException | RuntimeException ex) { socket.close(); transfers.remove(socket); throw ex; }
    }

    public void sendChat(Map<String, String> endpoint, String room, String text) throws IOException {
        Socket socket = dial(endpoint);
        try (socket) {
            if (text.length() > 8000) throw new IOException("Tin nhắn tối đa 8000 ký tự");
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "chat", "room", room, "from", name, "text", text));
        } finally { transfers.remove(socket); }
    }

    public void sendVoice(Map<String, String> endpoint, String room, byte[] wavData) throws IOException {
        if (wavData.length < 44 || wavData.length > MAX_VOICE_BYTES) throw new IOException("Tin nhắn thoại tối đa 5 phút");
        Socket socket = dial(endpoint);
        try (socket) {
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "voice_binary", "room", room,
                "from", name, "size", "" + wavData.length));
            socket.getOutputStream().write(wavData);
            socket.getOutputStream().flush();
            if (!"voice_ok".equals(Protocol.read(socket.getInputStream()).get("type")))
                throw new IOException("Bên nhận chưa xác nhận tin nhắn thoại");
        } finally { transfers.remove(socket); }
    }

    public void sendAttachmentAnnounce(Map<String, String> endpoint, String room, String filename, long size, int myPort) throws IOException {
        Socket socket = dial(endpoint);
        try (socket) {
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "attachment", "room", room, "from", name,
                "filename", filename, "size", "" + size, "port", "" + myPort));
        } finally { transfers.remove(socket); }
    }

    public void sendBroadcast(Map<String, String> endpoint, String text) throws IOException {
        sendBroadcast(endpoint, UUID.randomUUID().toString(), text);
    }
    public void sendBroadcast(Map<String, String> endpoint, String id, String text) throws IOException {
        if (text.length() > 8000) throw new IOException("Thông báo tối đa 8000 ký tự");
        Socket socket = dial(endpoint);
        try (socket) {
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "broadcast", "id", id, "from", name, "text", text));
        } finally { transfers.remove(socket); }
    }

    public CallReply requestCall(Map<String, String> endpoint, CallSession media) throws IOException {
        if (media.isClosed()) throw new IOException("Cuộc gọi đã đóng");
        Socket socket = dial(endpoint);
        outgoingCalls.put(media.id, socket);
        try (socket) {
            if (media.isClosed()) throw new IOException("Cuộc gọi đã hủy");
            socket.setSoTimeout(45_000);
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "call_offer", "id", media.id,
                "from", name, "port", "" + port(), "video", "" + media.videoEnabled,
                "vport", "" + media.video.getLocalPort(), "aport", "" + media.audio.getLocalPort()));
            var reply = Protocol.read(socket.getInputStream());
            if (!media.id.equals(reply.get("id")) || !"call_accept".equals(reply.get("type")))
                throw new IOException(reply.getOrDefault("reason", "Cuộc gọi bị từ chối"));
            return new CallReply(socket.getInetAddress().getHostAddress(), validPort(reply, "vport"), validPort(reply, "aport"));
        } finally { transfers.remove(socket); outgoingCalls.remove(media.id, socket); }
    }
    public void cancelPendingCall(String id) {
        Socket socket = outgoingCalls.remove(id);
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
    public void endCall(Map<String, String> endpoint, String id) throws IOException {
        cancelPendingCall(id);
        // A final hangup may run immediately after this peer's login connection has closed.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.get("ip"), Integer.parseInt(endpoint.get("port"))), 4000);
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "call_end", "id", id));
        }
    }

    public void share(Path path, String room) throws IOException {
        path = path.toAbsolutePath().normalize();
        String filename = path.getFileName().toString();
        Path existing = shared.get(filename);
        if (existing != null && !existing.equals(path)) throw new IOException("Đã chia sẻ file khác trùng tên; hãy bỏ chia sẻ trước");
        if (!Files.isRegularFile(path)) throw new IOException("Không tìm thấy file");
        shared.put(filename, path);
        try { call("share", "room", room, "filename", filename, "size", "" + Files.size(path)); }
        catch (IOException ex) {
            if (existing == null) shared.remove(filename); else shared.put(filename, existing);
            throw ex;
        }
    }

    public void unshare(String filename) throws IOException {
        call("unshare", "filename", filename);
        shared.remove(filename);
    }

    public Path download(Map<String, String> entry, Path folder, LongConsumer progress) throws IOException {
        String filename = entry.getOrDefault("filename", "");
        if (filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.contains("/") || filename.contains("\\") || filename.contains(":"))
            throw new IOException("Tên file không hợp lệ");
        Files.createDirectories(folder);
        Path destination = null;
        Socket socket = dial(entry);
        try (socket) {
            Protocol.write(socket.getOutputStream(), Protocol.message("type", "file", "filename", filename));
            var input = socket.getInputStream();
            var reply = Protocol.read(input);
            if (!"file_ok".equals(reply.get("type"))) throw new IOException("File không còn được chia sẻ");
            long size = Long.parseLong(reply.getOrDefault("size", "-1"));
            if (size < 0) throw new IOException("Kích thước không hợp lệ");
            OutputStream output;
            for (int suffix = 0; ; suffix++) {
                Path candidate = folder.resolve(suffix == 0 ? filename : renamed(filename, suffix));
                try {
                    output = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    destination = candidate;
                    break;
                } catch (FileAlreadyExistsException ignored) { }
            }
            try (OutputStream sink = output) { copy(input, sink, size, n -> progress.accept(size == 0 ? 100 : (long) (100.0 * n / size))); }
            progress.accept(100);
            return destination;
        } catch (IOException | RuntimeException ex) {
            if (destination != null) Files.deleteIfExists(destination);
            throw ex;
        } finally { transfers.remove(socket); }
    }

    private static String renamed(String name, int suffix) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + suffix + ")";
        return name.substring(0, dot) + " (" + suffix + ")" + name.substring(dot);
    }

    private static void copy(InputStream input, OutputStream output, long size, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[65536];
        long copied = 0;
        while (copied < size) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, size - copied));
            if (read < 0) throw new EOFException("Kết nối bị ngắt giữa chừng");
            output.write(buffer, 0, read);
            copied += read;
            progress.accept(copied);
        }
    }

    @Override public void close() {
        closed = true;
        offers.values().forEach(offer -> offer.reject("Đã ngắt kết nối"));
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : transfers) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
