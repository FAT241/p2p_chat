package common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UDP sockets are owned and closed explicitly; each worker captures its own socket. */
public final class UdpService implements AutoCloseable {
    public static final String MULTICAST_IP = "230.0.0.1";
    public static final int MULTICAST_PORT = 9876;
    public static final int DISCOVERY_PORT = 9005;
    public static final int BROADCAST_PORT = 9006;
    private static final int MAX_DATAGRAM = 65507;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<DatagramSocket> sockets = ConcurrentHashMap.newKeySet();
    private MulticastSocket multicastSocket;
    private DatagramSocket videoSendSocket, videoReceiveSocket;
    private volatile NetworkInterface networkInterface;
    private volatile boolean closed;
    private Consumer<String> errors = message -> System.err.println(message);

    public void onError(Consumer<String> callback) { errors = callback; }
    public void setNetworkInterface(NetworkInterface value) throws SocketException {
        // A server on 127.0.0.1 still needs multicast sent over the LAN interface.
        networkInterface = value != null && value.isUp() && !value.isLoopback() && value.supportsMulticast() ? value : null;
    }
    private void report(Exception ex) { if (!closed) errors.accept("UDP: " + ex.getMessage()); }
    private byte[] encode(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_DATAGRAM) throw new IOException("Bản tin UDP vượt 65507 byte");
        return data;
    }
    public void sendBroadcast(String message, int port) throws IOException {
        byte[] data = encode(message);
        try (var socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            IOException failure = null; int sent = 0;
            for (InetAddress address : NetworkSupport.broadcastAddresses()) {
                try { socket.send(new DatagramPacket(data, data.length, address, port)); sent++; }
                catch (IOException ex) { failure = ex; }
            }
            if (sent == 0) throw failure != null ? failure : new IOException("Không có mạng broadcast");
        }
    }
    public synchronized DatagramSocket startBroadcastListener(int port, Consumer<String> callback) throws IOException {
        if (closed) throw new IOException("UDP đã đóng");
        var socket = new DatagramSocket(null);
        try { socket.setReuseAddress(true); socket.bind(new InetSocketAddress(port)); }
        catch (IOException ex) { socket.close(); throw ex; }
        sockets.add(socket);
        receiveText(socket, callback);
        return socket;
    }
    private void receiveText(DatagramSocket socket, Consumer<String> callback) {
        executor.submit(() -> {
            byte[] buffer = new byte[MAX_DATAGRAM];
            while (!socket.isClosed()) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    callback.accept(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                } catch (IOException ex) { if (!socket.isClosed()) report(ex); break; }
                catch (RuntimeException ex) { report(ex); }
            }
        });
    }
    public synchronized void joinMulticast(Consumer<String> callback) throws IOException {
        if (closed) throw new IOException("UDP đã đóng");
        if (multicastSocket != null && !multicastSocket.isClosed()) return;
        var socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MULTICAST_PORT));
            var group = new InetSocketAddress(InetAddress.getByName(MULTICAST_IP), MULTICAST_PORT);
            int joined = 0;
            for (var nic : NetworkSupport.ipv4Interfaces()) {
                if (!nic.supportsMulticast()) continue;
                try { socket.joinGroup(group, nic); joined++; }
                catch (IOException ex) { /* Another usable interface may still work. */ }
            }
            if (joined == 0) throw new IOException("Không tham gia được multicast trên card mạng nào");
            multicastSocket = socket; sockets.add(socket); receiveText(socket, callback);
        } catch (IOException ex) { socket.close(); throw ex; }
    }
    public void sendMulticast(String message) throws IOException {
        byte[] data = encode(message);
        try (var socket = new MulticastSocket()) {
            socket.setTimeToLive(1);
            if (networkInterface != null) socket.setNetworkInterface(networkInterface);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(MULTICAST_IP), MULTICAST_PORT));
        }
    }
    /** Encode structured text, preserving pipes, newlines and Unicode in names/rooms. */
    public static String envelope(Map<String, String> message) throws IOException {
        var out = new ByteArrayOutputStream(); Protocol.write(out, message);
        return "P2P2|" + Base64.getEncoder().encodeToString(out.toByteArray());
    }
    public static Map<String, String> parseEnvelope(String text) throws IOException {
        if (!text.startsWith("P2P2|")) throw new IOException("Không phải bản tin P2P2");
        try { return Protocol.read(new ByteArrayInputStream(Base64.getDecoder().decode(text.substring(5)))); }
        catch (IllegalArgumentException ex) { throw new IOException("Bản tin UDP không hợp lệ", ex); }
    }
    public synchronized int startVideoReceiver(int port, Consumer<BufferedImage> callback) throws IOException {
        return startVideoReceiver(new DatagramSocket(port), callback);
    }
    public synchronized int startVideoReceiver(DatagramSocket socket, Consumer<BufferedImage> callback) throws IOException {
        stopVideoReceiver();
        if (closed || socket.isClosed()) { socket.close(); throw new IOException("Cổng video đã đóng"); }
        videoReceiveSocket = socket; sockets.add(socket);
        executor.submit(() -> {
            byte[] buffer = new byte[MAX_DATAGRAM];
            while (!socket.isClosed()) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                    var frame = ImageIO.read(new ByteArrayInputStream(packet.getData(), packet.getOffset(), packet.getLength()));
                    if (frame != null) callback.accept(frame);
                } catch (IOException ex) { if (!socket.isClosed()) report(ex); }
                catch (RuntimeException ex) { report(ex); }
            }
        });
        return socket.getLocalPort();
    }
    public synchronized void stopVideoReceiver() {
        if (videoReceiveSocket != null) { videoReceiveSocket.close(); sockets.remove(videoReceiveSocket); videoReceiveSocket = null; }
    }
    public synchronized void startVideoStream(InetAddress ip, int port, int fps, Supplier<BufferedImage> source) throws IOException {
        stopVideoStream();
        if (closed) throw new IOException("UDP đã đóng");
        var socket = new DatagramSocket(); videoSendSocket = socket; sockets.add(socket);
        int delay = Math.max(20, 1000 / Math.max(1, fps));
        executor.submit(() -> {
            while (!socket.isClosed()) {
                try {
                    BufferedImage frame = source.get();
                    if (frame != null) {
                        // Reduce oversized frames instead of silently dropping every frame.
                        byte[] bytes;
                        do {
                            var out = new ByteArrayOutputStream(); ImageIO.write(frame, "jpg", out); bytes = out.toByteArray();
                            if (bytes.length <= MAX_DATAGRAM) break;
                            var smaller = new BufferedImage(Math.max(1, frame.getWidth() * 3 / 4), Math.max(1, frame.getHeight() * 3 / 4), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = smaller.createGraphics(); g.drawImage(frame, 0, 0, smaller.getWidth(), smaller.getHeight(), null); g.dispose(); frame = smaller;
                        } while (true);
                        socket.send(new DatagramPacket(bytes, bytes.length, ip, port));
                    }
                    Thread.sleep(delay);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                catch (Exception ex) { if (!socket.isClosed()) report(ex); break; }
            }
        });
    }
    public synchronized void stopVideoStream() {
        if (videoSendSocket != null) { videoSendSocket.close(); sockets.remove(videoSendSocket); videoSendSocket = null; }
    }
    @Override public synchronized void close() {
        closed = true;
        for (var socket : sockets) socket.close();
        sockets.clear(); executor.shutdownNow();
    }
}
