package common;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network discovery returns concrete source addresses, never wildcard bind addresses. */
public final class NetworkSupport {
    private NetworkSupport() {}
    public record ServerAddress(String ip, int port, String machine, String network) {
        public ServerAddress(String ip, int port) { this(ip, port, "", "Chưa rõ mạng"); }
        public String endpoint() { return ip + ":" + port; }
        @Override public String toString() {
            return endpoint() + " — " + network + " — Máy: " + (machine.isBlank() ? "chưa rõ (server cũ)" : machine);
        }
        public int networkPriority() {
            if (network.startsWith("Wi-Fi")) return 0;
            if (network.startsWith("Ethernet")) return 1;
            if (network.contains("VPN")) return 2;
            if (network.startsWith("VMware") || network.startsWith("VirtualBox")) return 3;
            if (ip.startsWith("127.")) return 5;
            return 4;
        }
    }
    private static String displayText(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
    public static String machineName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) name = System.getenv("HOSTNAME");
        if (name == null || name.isBlank()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (UnknownHostException ex) { name = "Không rõ tên máy"; }
        }
        return displayText(name);
    }
    /** Labels come from adapter names on the server, never from guesses about IP ranges. */
    public static String networkLabel(String name, String description, boolean loopback) {
        if (loopback) return "Cùng máy (localhost)";
        String combined = (name + " " + description).toLowerCase(Locale.ROOT);
        if (combined.contains("vmware") || combined.contains("vmnet")) {
            var matcher = java.util.regex.Pattern.compile("vmnet\\d+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name + " " + description);
            return "VMware" + (matcher.find() ? " " + matcher.group() : "") + " (mạng ảo)";
        }
        if (combined.contains("virtualbox")) return "VirtualBox (mạng ảo)";
        if (combined.contains("radmin") || combined.contains("famatech")) return "Radmin VPN";
        if (combined.contains("tailscale")) return "Tailscale VPN";
        if (combined.contains("wireguard")) return "WireGuard VPN";
        if (combined.contains("vpn") || combined.contains("tap-") || combined.contains("tun"))
            return "VPN (" + displayText(description == null ? name : description) + ")";
        if (combined.contains("hyper-v") || combined.contains("vethernet")) return "Hyper-V (mạng ảo)";
        if (combined.contains("wi-fi") || combined.contains("wifi") || combined.contains("wireless") || combined.contains("wlan")) return "Wi-Fi";
        if (combined.contains("ethernet") || combined.contains("gbe") || combined.contains("eth")) return "Ethernet";
        return displayText(description == null || description.isBlank() ? name : description);
    }
    public static String networkLabel(NetworkInterface nic) throws SocketException {
        return networkLabel(nic.getName(), nic.getDisplayName(), nic.isLoopback());
    }
    public static String discoveryReply(int port) throws IOException {
        var metadata = Protocol.message("machine", machineName());
        for (var nic : ipv4Interfaces()) for (var address : Collections.list(nic.getInetAddresses())) {
            if (address instanceof Inet4Address) metadata.put("network." + address.getHostAddress(), networkLabel(nic));
        }
        var output = new ByteArrayOutputStream(); Protocol.write(output, metadata);
        return "P2P_SERVER|" + port + "|" + Base64.getEncoder().encodeToString(output.toByteArray());
    }
    public static List<NetworkInterface> ipv4Interfaces() throws SocketException {
        var result = new ArrayList<NetworkInterface>();
        for (var nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isUp() && nic.inetAddresses().anyMatch(a -> a instanceof Inet4Address)) result.add(nic);
        }
        return result;
    }
    public static Set<InetAddress> broadcastAddresses() throws IOException {
        var result = new LinkedHashSet<InetAddress>();
        for (var nic : ipv4Interfaces()) for (var address : nic.getInterfaceAddresses()) {
            if (address.getBroadcast() != null) result.add(address.getBroadcast());
        }
        result.add(InetAddress.getByName("255.255.255.255"));
        return result;
    }
    public static List<String> localAddresses() throws SocketException {
        var result = new ArrayList<String>();
        for (var nic : ipv4Interfaces()) for (var address : Collections.list(nic.getInetAddresses())) {
            if (address instanceof Inet4Address && !address.isLoopbackAddress())
                result.add(address.getHostAddress() + " (" + nic.getDisplayName() + ")");
        }
        return result;
    }
    public static ServerAddress parseDiscovery(DatagramPacket packet) throws IOException {
        String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).strip();
        if (!text.startsWith("P2P_SERVER|") || packet.getAddress().isAnyLocalAddress())
            throw new IOException("Phản hồi server không hợp lệ");
        try {
            String[] parts = text.split("\\|", 3);
            int port = Integer.parseInt(parts[1]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            String ip = packet.getAddress().getHostAddress(), machine = "", network = "Chưa rõ mạng";
            if (parts.length == 3) {
                try {
                    var metadata = Protocol.read(new ByteArrayInputStream(Base64.getDecoder().decode(parts[2])));
                    machine = displayText(metadata.get("machine"));
                    String label = displayText(metadata.get("network." + ip));
                    if (!label.isBlank()) network = label;
                } catch (IOException | IllegalArgumentException ignored) { /* Endpoint still usable without optional labels. */ }
            }
            // Legacy server on this exact machine: its adapter information is available locally.
            NetworkInterface local = NetworkInterface.getByInetAddress(packet.getAddress());
            if (local != null) {
                if (machine.isBlank()) machine = machineName();
                if (network.equals("Chưa rõ mạng")) network = networkLabel(local);
            }
            return new ServerAddress(ip, port, machine, network);
        } catch (NumberFormatException ex) { throw new IOException("Cổng server không hợp lệ", ex); }
    }
    public static List<ServerAddress> discover(int timeoutMillis) throws IOException {
        var found = new LinkedHashMap<String, ServerAddress>();
        try (var socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            var destinations = broadcastAddresses();
            destinations.add(InetAddress.getByName("127.0.0.1"));
            for (String command : List.of("P2P_DISCOVER_V2", "P2P_DISCOVER")) {
                byte[] request = command.getBytes(StandardCharsets.UTF_8);
                for (var target : destinations) {
                    try { socket.send(new DatagramPacket(request, request.length, target, UdpService.DISCOVERY_PORT)); }
                    catch (IOException ignored) { /* Try the other interfaces too. */ }
                }
            }
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout((int)Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
                var packet = new DatagramPacket(new byte[65507], 65507);
                try {
                    socket.receive(packet);
                    var address = parseDiscovery(packet);
                    found.merge(address.endpoint(), address, (old, next) -> next.machine().isBlank() ? old : next);
                }
                catch (SocketTimeoutException ex) { break; }
                catch (IOException ignored) { /* Ignore unrelated/malformed UDP packets. */ }
            }
        }
        // Validate reachability: the TCP listener may be bound to only one interface.
        var reachable = new ArrayList<ServerAddress>();
        for (var address : found.values()) {
            try (var tcp = new Socket()) {
                tcp.connect(new InetSocketAddress(address.ip(), address.port()), 400);
                reachable.add(address);
            } catch (IOException ignored) {}
        }
        reachable.sort(Comparator.comparingInt(ServerAddress::networkPriority).thenComparing(ServerAddress::machine).thenComparing(ServerAddress::ip));
        return reachable;
    }
}
