package server;

import common.Protocol;
import common.NetworkSupport;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Index/presence only: chat and file contents never pass through this server. */
public final class IndexServer implements AutoCloseable {
    private record User(Socket socket, String ip, String port) {}
    private record FileKey(String owner, String name) {}
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Set<String>> rooms = new TreeMap<>();
    private final Map<String, Map<FileKey, Long>> files = new HashMap<>();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Consumer<String> log;
    private final ServerSocket listener;
    private DatagramSocket udpDiscoverySocket;
    private volatile boolean closed;

    public IndexServer(String host, int port, Consumer<String> log) throws IOException {
        this.log = log;
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(host, port));
        } catch (IOException ex) { listener.close(); workers.close(); throw ex; }
        workers.submit(this::accept);
        startUdpDiscoveryResponder(port());
        log.accept("Đang lắng nghe " + host + ":" + port() + " (TCP) và Discovery (UDP 9005)");
        log.accept("Client cùng máy: 127.0.0.1:" + port() + "; 0.0.0.0 chỉ là địa chỉ lắng nghe.");
        try {
            for (String address : common.NetworkSupport.localAddresses())
                log.accept("Địa chỉ trên máy server: " + address + "; cổng TCP " + port());
        } catch (SocketException ex) { log.accept("Không đọc được danh sách card mạng: " + ex.getMessage()); }
    }

    private void startUdpDiscoveryResponder(int tcpPort) {
        try {
            udpDiscoverySocket = new DatagramSocket(null);
            udpDiscoverySocket.setReuseAddress(true);
            udpDiscoverySocket.bind(new InetSocketAddress(9005));
            workers.submit(() -> {
                byte[] buf = new byte[1024];
                while (!closed && udpDiscoverySocket != null && !udpDiscoverySocket.isClosed()) {
                    try {
                        var packet = new DatagramPacket(buf, buf.length);
                        udpDiscoverySocket.receive(packet);
                        String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                        if ("P2P_DISCOVER".equals(msg) || "P2P_DISCOVER_V2".equals(msg)) {
                            String reply = "P2P_DISCOVER_V2".equals(msg) ? NetworkSupport.discoveryReply(tcpPort) : "P2P_SERVER|" + tcpPort;
                            byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                            var replyPacket = new DatagramPacket(replyBytes, replyBytes.length, packet.getAddress(), packet.getPort());
                            udpDiscoverySocket.send(replyPacket);
                        }
                    } catch (IOException ignored) {}
                }
            });
        } catch (Exception ex) {
            log.accept("Cảnh báo: Không thể mở UDP port 9005 cho discovery (" + ex.getMessage() + ")");
        }
    }

    public int port() { return listener.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(120_000);
                connections.add(socket);
                if (closed) socket.close();
                else workers.submit(() -> handle(socket));
            } catch (IOException | RejectedExecutionException ex) {
                if (!closed) log.accept("Lỗi nhận kết nối: " + ex.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        String current = null;
        try (socket) {
            while (!closed) {
                var request = Protocol.read(socket.getInputStream());
                Map<String, String> response;
                try {
                    synchronized (this) {
                        if ("register".equals(request.get("cmd"))) {
                            if (current != null) throw new IllegalArgumentException("Đã đăng nhập");
                            String name = Protocol.required(request, "name");
                            int port = Integer.parseInt(request.getOrDefault("port", "0"));
                            if (port < 1 || port > 65535) throw new IllegalArgumentException("Cổng P2P không hợp lệ");
                            if (users.containsKey(name)) throw new IllegalArgumentException("Tên đã được sử dụng");
                            users.put(name, new User(socket, socket.getInetAddress().getHostAddress(), "" + port));
                            current = name;
                            response = Protocol.message("ok", "true");
                            log.accept(name + " đã kết nối");
                        } else {
                            if (current == null) throw new IllegalArgumentException("Chưa đăng nhập");
                            response = dispatch(current, request);
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    response = Protocol.message("ok", "false", "error", ex.getMessage());
                }
                Protocol.write(socket.getOutputStream(), response);
                if ("bye".equals(request.get("cmd"))) break;
            }
        } catch (IOException ex) {
            // EOF, timeout and shutdown all remove presence below.
        } finally {
            connections.remove(socket);
            synchronized (this) {
                if (current != null) {
                    users.remove(current);
                    for (String room : new ArrayList<>(rooms.keySet())) leave(current, room);
                    log.accept(current + " đã ngắt kết nối");
                }
            }
        }
    }

    private Map<String, String> dispatch(String user, Map<String, String> request) {
        String room = request.getOrDefault("room", "");
        switch (request.getOrDefault("cmd", "")) {
            case "create", "join" -> {
                room = Protocol.required(request, "room");
                if ("create".equals(request.get("cmd")) && rooms.containsKey(room)) throw new IllegalArgumentException("Phòng đã tồn tại");
                rooms.computeIfAbsent(room, key -> new TreeSet<>()).add(user);
                files.computeIfAbsent(room, key -> new HashMap<>());
                return members(room);
            }
            case "leave" -> leave(user, room);
            case "rooms" -> {
                var rows = new ArrayList<Map<String, String>>();
                rooms.forEach((name, members) -> rows.add(Protocol.message("room", name, "count", "" + members.size())));
                return Protocol.rows(rows);
            }
            case "users" -> {
                var rows = new ArrayList<Map<String, String>>();
                users.forEach((name, u) -> rows.add(Protocol.message("name", name, "ip", u.ip(), "port", u.port())));
                return Protocol.rows(rows);
            }
            case "members" -> { return members(room); }
            case "share" -> {
                if (!rooms.getOrDefault(room, Set.of()).contains(user)) throw new IllegalArgumentException("Hãy tham gia phòng trước");
                String name = Protocol.required(request, "filename");
                long size = Long.parseLong(request.getOrDefault("size", "-1"));
                if (size < 0) throw new IllegalArgumentException("Kích thước không hợp lệ");
                files.get(room).put(new FileKey(user, name), size);
            }
            case "unshare" -> {
                String name = Protocol.required(request, "filename");
                files.values().forEach(index -> index.remove(new FileKey(user, name)));
            }
            case "search" -> {
                String keyword = request.getOrDefault("keyword", "").toLowerCase(Locale.ROOT);
                var rows = new ArrayList<Map<String, String>>();
                files.forEach((r, index) -> index.forEach((file, size) -> {
                    User owner = users.get(file.owner());
                    if (owner != null && file.name().toLowerCase(Locale.ROOT).contains(keyword)) {
                        rows.add(Protocol.message("room", r, "filename", file.name(), "size", "" + size,
                            "owner", file.owner(), "ip", owner.ip(), "port", owner.port()));
                    }
                }));
                rows.sort(Comparator.comparing(row -> row.get("filename")));
                return Protocol.rows(rows);
            }
            case "bye", "ping" -> { }
            default -> throw new IllegalArgumentException("Lệnh không hợp lệ");
        }
        return Protocol.message("ok", "true");
    }

    private Map<String, String> members(String room) {
        var rows = new ArrayList<Map<String, String>>();
        for (String name : rooms.getOrDefault(room, Set.of())) {
            User u = users.get(name);
            if (u != null) rows.add(Protocol.message("name", name, "ip", u.ip(), "port", u.port()));
        }
        return Protocol.rows(rows);
    }

    private void leave(String user, String room) {
        Set<String> members = rooms.get(room);
        if (members == null) return;
        members.remove(user);
        files.get(room).keySet().removeIf(key -> key.owner().equals(user));
        if (members.isEmpty()) { rooms.remove(room); files.remove(room); }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        if (udpDiscoverySocket != null) try { udpDiscoverySocket.close(); } catch (Exception ignored) { }
        for (Socket socket : connections) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
