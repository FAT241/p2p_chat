package test;

import client.Peer;
import common.Protocol;
import server.IndexServer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real loopback sockets and byte comparisons; no test framework dependency. */
public final class IntegrationTest {
    private static int checks;
    private static final class Events implements Peer.Events {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        public void chat(String room, String sender, String text) { messages.add(room + ":" + sender + ":" + text); }
        public void status(String text) { }
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException ex) { check(true, label); return; }
        throw new AssertionError("Expected failure: " + label);
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("p2p-java-test-");
        try {
            var eventA = new Events(); var eventB = new Events();
            try (var server = new IndexServer("127.0.0.1", 0, text -> {});
                 var alice = new Peer(eventA); var bob = new Peer(eventB); var duplicate = new Peer(new Events())) {
                alice.connect("127.0.0.1", server.port(), "An");
                bob.connect("127.0.0.1", server.port(), "Bình");
                fails(() -> duplicate.connect("127.0.0.1", server.port(), "An"), "duplicate username rejected");
                alice.call("create", "room", "Lớp mạng");
                bob.call("join", "room", "Lớp mạng");
                var members = Protocol.rows(alice.call("members", "room", "Lớp mạng"));
                check(members.size() == 2, "room membership");
                var bobAddress = members.stream().filter(m -> m.get("name").equals("Bình")).findFirst().orElseThrow();
                alice.sendChat(bobAddress, "Lớp mạng", "Xin chào 🌏");
                check("Lớp mạng:An:Xin chào 🌏".equals(eventB.messages.poll(4, TimeUnit.SECONDS)), "Unicode direct P2P chat");

                Path fileA = temporary.resolve("a/tài liệu.bin"), fileB = temporary.resolve("b/tài liệu.bin");
                Files.createDirectories(fileA.getParent()); Files.createDirectories(fileB.getParent());
                byte[] content = new byte[250_123]; new Random(42).nextBytes(content);
                Files.write(fileA, content); Files.writeString(fileB, "other owner");
                alice.share(fileA, "Lớp mạng"); bob.share(fileB, "Lớp mạng");
                var entries = Protocol.rows(bob.call("search", "keyword", "tài liệu"));
                check(entries.size() == 2, "same filename from different owners");
                var entry = entries.stream().filter(row -> row.get("owner").equals("An")).findFirst().orElseThrow();
                Path downloads = temporary.resolve("downloads");
                Path first = bob.download(entry, downloads, value -> {});
                check(Arrays.equals(content, Files.readAllBytes(first)), "binary P2P file transfer");
                Path second = bob.download(entry, downloads, value -> {});
                check(!first.equals(second) && Arrays.equals(content, Files.readAllBytes(first)), "download never overwrites existing file");
                bob.unshare("tài liệu.bin");
                check(Protocol.rows(bob.call("search")).size() == 1, "unshare preserves other owner's file");
                bob.share(fileB, "Lớp mạng");
                bob.call("leave", "room", "Lớp mạng");
                check(Protocol.rows(alice.call("search")).size() == 1, "leaving room removes owned index entries");
                bob.call("join", "room", "Lớp mạng");
                Path empty = temporary.resolve("empty.txt"); Files.write(empty, new byte[0]);
                alice.share(empty, "Lớp mạng");
                var emptyEntry = Protocol.rows(bob.call("search", "keyword", "empty")).getFirst();
                check(Files.size(bob.download(emptyEntry, downloads, value -> {})) == 0, "empty file transfer");
                var bad = new HashMap<>(entry); bad.put("filename", "../escape.txt");
                fails(() -> bob.download(bad, downloads, value -> {}), "path traversal rejected");
                server.close();
                fails(() -> bob.call("rooms"), "index server is offline");
                alice.sendChat(bobAddress, "Lớp mạng", "Server đã tắt");
                check("Lớp mạng:An:Server đã tắt".equals(eventB.messages.poll(4, TimeUnit.SECONDS)), "chat works AFTER server shutdown");
                Path offline = bob.download(entry, downloads, value -> {});
                check(Arrays.equals(content, Files.readAllBytes(offline)), "file download works AFTER server shutdown");
            }

            try (var server = new IndexServer("127.0.0.1", 0, text -> {})) {
                for (int i = 0; i < 3; i++) {
                    try (var peer = new Peer(new Events())) {
                        peer.connect("127.0.0.1", server.port(), "reconnect-" + i);
                        check("true".equals(peer.call("ping").get("ok")), "fresh connection " + i);
                    }
                }
                try (Socket malformed = new Socket("127.0.0.1", server.port())) {
                    var out = new DataOutputStream(malformed.getOutputStream());
                    out.writeInt(0x50325031); out.writeInt(Integer.MAX_VALUE); out.flush();
                    malformed.setSoTimeout(2000);
                    check(malformed.getInputStream().read() == -1, "oversized frame closes connection");
                }
                try (var healthy = new Peer(new Events())) {
                    healthy.connect("127.0.0.1", server.port(), "healthy");
                    check("true".equals(healthy.call("ping").get("ok")), "server survives malformed connection");
                }
            }
            truncatedDownload(temporary);
            System.out.println("ALL " + checks + " CHECKS PASSED");
        } finally {
            // Only this test's newly created temporary directory is removed.
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void truncatedDownload(Path temporary) throws Exception {
        Path folder = temporary.resolve("truncated");
        try (var fake = new ServerSocket(0); var peer = new Peer(new Events())) {
            Thread sender = Thread.ofVirtual().start(() -> {
                try (Socket socket = fake.accept()) {
                    Protocol.read(socket.getInputStream());
                    Protocol.write(socket.getOutputStream(), Protocol.message("type", "file_ok", "size", "100"));
                    socket.getOutputStream().write(new byte[7]);
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var entry = Protocol.message("ip", "127.0.0.1", "port", "" + fake.getLocalPort(), "filename", "partial.bin");
            fails(() -> peer.download(entry, folder, value -> {}), "truncated transfer reported");
            sender.join(3000);
            try (var files = Files.list(folder)) { check(files.findAny().isEmpty(), "partial download cleaned up"); }
        }
    }
}
