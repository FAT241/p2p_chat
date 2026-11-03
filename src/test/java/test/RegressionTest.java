package test;

import client.*;
import common.*;
import server.IndexServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;

/** Real socket regression cases for media signaling, long messages and discovery. No hardware capture. */
public final class RegressionTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label); checks++;
    }
    static class Events implements Peer.Events {
        final BlockingQueue<String> chat = new LinkedBlockingQueue<>();
        final BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> voices = new LinkedBlockingQueue<>();
        final BlockingQueue<Peer.CallOffer> calls = new LinkedBlockingQueue<>();
        final BlockingQueue<String> ends = new LinkedBlockingQueue<>();
        public void chat(String r, String s, String text) { chat.add(text); }
        public void status(String text) {}
        public void voice(String r, String s, byte[] bytes) { voices.add(bytes); }
        public void broadcast(String id, String s, String text) { broadcasts.add(id + ":" + text); }
        public void incomingCall(Peer.CallOffer offer) { calls.add(offer); }
        public void callEnded(String id) { ends.add(id); }
    }
    private static Map<String, String> endpoint(Peer peer) {
        return Map.of("ip", "127.0.0.1", "port", "" + peer.port());
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("p2p-regression-");
        try {
            tcpAndCalls(); udp(); discovery(); history(temporary);
            System.out.println("ALL " + checks + " REGRESSION CHECKS PASSED");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private static void tcpAndCalls() throws Exception {
        var peers = new ArrayList<Peer>(); var events = new ArrayList<Events>();
        try (var server = new IndexServer("127.0.0.1", 0, t -> {});
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i=0;i<6;i++) {
                var e = new Events(); var p = new Peer(e); events.add(e); peers.add(p);
                p.connect("127.0.0.1", server.port(), "Người dùng " + i);
                p.call("join", "room", "Phòng | mạng 😀");
            }
            check(Protocol.rows(peers.getFirst().call("members", "room", "Phòng | mạng 😀")).size()==6, "six simultaneous peers");
            for (int a=0;a<6;a++) for(int b=0;b<6;b++) if(a!=b)
                peers.get(a).sendChat(endpoint(peers.get(b)), "Phòng | mạng 😀", "Tiếng Việt 😀❤️👍🏽👩‍💻 " + a);
            int received=0;
            for (var e:events) for(int i=0;i<5;i++) if(e.chat.poll(3,TimeUnit.SECONDS)!=null)received++;
            check(received==30,"six peers receive 30/30 Unicode messages");
            String id=UUID.randomUUID().toString();
            for(int i=1;i<6;i++)peers.getFirst().sendBroadcast(endpoint(peers.get(i)),id,"Thông báo 😀");
            for(int i=1;i<6;i++)check((id+":Thông báo 😀").equals(events.get(i).broadcasts.poll(3,TimeUnit.SECONDS)),"TCP broadcast recipient "+i);
            for(int seconds:new int[]{1,2,5,120,300}) {
                byte[] pcm=new byte[32000*seconds];new Random(seconds).nextBytes(pcm);
                byte[] wav=AudioService.pcmToWav(pcm,AudioService.FORMAT);
                peers.getFirst().sendVoice(endpoint(peers.get(1)),"Phòng | mạng 😀",wav);
                check(Arrays.equals(wav,events.get(1).voices.poll(3,TimeUnit.SECONDS)),"WAV exact bytes "+seconds+" seconds");
            }
            try {
                peers.getFirst().sendVoice(endpoint(peers.get(1)),"room",new byte[Peer.MAX_VOICE_BYTES+1]);
                throw new AssertionError("oversized voice accepted");
            } catch(IOException expected) { check(true,"oversized voice rejected before transfer"); }
            try(var truncated = new Socket("127.0.0.1",peers.get(1).port())) {
                Protocol.write(truncated.getOutputStream(),Protocol.message("type","voice_binary","room","r","from","a","size","1000"));
                truncated.getOutputStream().write(new byte[20]); truncated.shutdownOutput();
                truncated.setSoTimeout(2000); check(truncated.getInputStream().read()==-1,"truncated voice connection closed");
            }
            check(events.get(1).voices.poll(150,TimeUnit.MILLISECONDS)==null,"truncated voice never delivered as complete");
            Peer caller=peers.get(0), callee=peers.get(1); Events receiver=events.get(1);
            try(var a = new CallSession(UUID.randomUUID().toString(), true)) {
                var pending=tasks.submit(()->caller.requestCall(endpoint(callee),a));
                var offer=receiver.calls.poll(3,TimeUnit.SECONDS);
                check(offer!=null && offer.sender.equals("Người dùng 0") && offer.ip.equals("127.0.0.1"),"call source address and spaced username");
                check(offer.videoPort==a.video.getLocalPort() && offer.audioPort==a.audio.getLocalPort(),"offer advertises reserved caller ports");
                try(var b = new CallSession(offer.id, true)) {
                    check(offer.accept(b),"receiver accepts call");
                    var reply=pending.get(3,TimeUnit.SECONDS);
                    check(reply.videoPort()==b.video.getLocalPort() && reply.audioPort()==b.audio.getLocalPort(),"accept returns actual receiver ports");
                    sendAndReceive(a.audio,b.audio,reply.audioPort(),"caller PCM");
                    sendAndReceive(b.audio,a.audio,offer.audioPort,"callee PCM");
                    try(var audio = new AudioService()) {
                        int reserved=a.audio.getLocalPort(); audio.setMuted(true);audio.setMuted(false);
                        check(a.audio.getLocalPort()==reserved && !a.audio.isClosed(),"mute state leaves negotiated media port intact");
                    }
                    try(var ua=new UdpService();var ub=new UdpService()) {
                        var qa=new LinkedBlockingQueue<BufferedImage>();var qb=new LinkedBlockingQueue<BufferedImage>();
                        ua.startVideoReceiver(a.video,qa::add);ub.startVideoReceiver(b.video,qb::add);
                        ua.startVideoStream(InetAddress.getLoopbackAddress(),reply.videoPort(),10,()->new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB));
                        ub.startVideoStream(InetAddress.getLoopbackAddress(),offer.videoPort,10,()->new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB));
                        check(qa.poll(3,TimeUnit.SECONDS)!=null && qb.poll(3,TimeUnit.SECONDS)!=null,"negotiated bidirectional UDP video frames");
                    }
                    caller.endCall(endpoint(callee),a.id);
                    check(a.id.equals(receiver.ends.poll(3,TimeUnit.SECONDS)),"remote hangup signaling");
                }
            }
            try(var a=new CallSession(UUID.randomUUID().toString(),false)) {
                var pending=tasks.submit(()->caller.requestCall(endpoint(callee),a));
                var offer=receiver.calls.poll(3,TimeUnit.SECONDS);
                check(offer!=null && !offer.videoEnabled,"voice-only flag preserved");
                offer.reject("Bận");
                try { pending.get(3,TimeUnit.SECONDS);throw new AssertionError("rejection ignored"); }
                catch(ExecutionException ex){check(ex.getCause() instanceof IOException,"declined call reported");}
            }
            try(var a=new CallSession(UUID.randomUUID().toString(),true)) {
                var pending=tasks.submit(()->caller.requestCall(endpoint(callee),a));
                var offer=receiver.calls.poll(3,TimeUnit.SECONDS);check(offer!=null,"pending call arrives");
                caller.endCall(endpoint(callee),a.id);
                check(a.id.equals(receiver.ends.poll(3,TimeUnit.SECONDS)),"cancel pending call reaches receiver");
                try { pending.get(3,TimeUnit.SECONDS);throw new AssertionError("cancel ignored"); }
                catch(ExecutionException ex){check(true,"cancel unblocks caller without waiting 45 seconds");}
                try(var b=new CallSession(a.id,true)){check(!offer.accept(b),"late acceptance of canceled offer rejected");}
            }
            try(var a=new CallSession(UUID.randomUUID().toString(),true)) {
                caller.endCall(endpoint(callee),a.id);check(a.id.equals(receiver.ends.poll(3,TimeUnit.SECONDS)),"early cancel recorded");
                try { caller.requestCall(endpoint(callee),a);throw new AssertionError("ended offer accepted"); }
                catch(IOException ex){check(true,"cancel arriving before offer prevents ringing");}
            }
        } finally {for(var p:peers)p.close();}
    }
    private static void sendAndReceive(DatagramSocket source,DatagramSocket destination,int port,String text)throws Exception {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        source.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),port));
        destination.setSoTimeout(2000);var packet=new DatagramPacket(new byte[1024],1024);destination.receive(packet);
        check(text.equals(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8)),"negotiated audio UDP "+text);
        destination.setSoTimeout(0);
    }
    private static boolean takeMatching(BlockingQueue<String> queue,String expected)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end){String value=queue.poll(100,TimeUnit.MILLISECONDS);if(expected.equals(value))return true;}
        return false;
    }
    private static void udp() throws Exception {
        var services=new ArrayList<UdpService>();var multicast=new ArrayList<BlockingQueue<String>>();var broadcast=new ArrayList<BlockingQueue<String>>();
        var bound=new ArrayList<DatagramSocket>();
        int broadcastPort;try(var free=new DatagramSocket(0)){broadcastPort=free.getLocalPort();}
        try {
            for(int i=0;i<6;i++) {
                var u=new UdpService();services.add(u);
                var m=new LinkedBlockingQueue<String>();var b=new LinkedBlockingQueue<String>();multicast.add(m);broadcast.add(b);
                u.joinMulticast(m::add);bound.add(u.startBroadcastListener(broadcastPort,b::add));
            }
            String text="ắ".repeat(7990)+"😀❤️|fin";
            String wire=UdpService.envelope(Protocol.message("type","multicast","id",UUID.randomUUID().toString(),"from","Người | gửi","room","Phòng | 😀","text",text));
            check(UdpService.parseEnvelope(wire).get("text").equals(text),"structured UDP preserves emoji, delimiters and long text");
            services.getFirst().sendMulticast(wire);
            for(int i=0;i<6;i++)check(takeMatching(multicast.get(i),wire),"full long Unicode multicast peer "+i);
            services.getFirst().sendBroadcast(wire,broadcastPort);
            for(int i=0;i<6;i++)check(takeMatching(broadcast.get(i),wire),"UDP broadcast listener peer "+i);
        } finally {for(var u:services)u.close();}
        check(bound.stream().allMatch(DatagramSocket::isClosed),"service close releases broadcast listener sockets");
        try(var u=new UdpService()) {
            int port=u.startVideoReceiver(0,img->{});u.stopVideoReceiver();
            try(var rebound=new DatagramSocket(port)){check(rebound.getLocalPort()==port,"video stop releases port for next call");}
            var frames=new LinkedBlockingQueue<BufferedImage>();int receiver=u.startVideoReceiver(0,frames::add);
            var large=new BufferedImage(900,700,BufferedImage.TYPE_INT_RGB);var random=new Random(42);
            for(int y=0;y<700;y++)for(int x=0;x<900;x++)large.setRGB(x,y,random.nextInt());
            u.startVideoStream(InetAddress.getLoopbackAddress(),receiver,5,()->large);
            check(frames.poll(5,TimeUnit.SECONDS)!=null,"oversized JPEG is resized and received");
        }
    }
    private static void discovery() throws Exception {
        try(var server=new IndexServer("0.0.0.0",0,t->{});var peer=new Peer(new Events())) {
            var found=NetworkSupport.discover(1000);
            check(found.stream().anyMatch(s->s.port()==server.port()),"discovery finds wildcard-bound server");
            check(found.stream().noneMatch(s->s.ip().equals("0.0.0.0")),"discovery returns concrete reachable IPs");
            check(found.stream().filter(s->s.port()==server.port()).allMatch(s->!s.machine().isBlank() && !s.network().equals("Chưa rõ mạng")),"discovery labels actual server machine and adapters");
            check(found.stream().map(NetworkSupport.ServerAddress::endpoint).distinct().count()==found.size(),"legacy and V2 discovery replies deduplicated by endpoint");
            try {peer.connect("0.0.0.0",server.port(),"invalid");throw new AssertionError("wildcard accepted");}
            catch(IOException ex){check(ex.getMessage().contains("127.0.0.1"),"client rejects wildcard with actionable instruction");}
        }
        byte[] bytes="P2P_SERVER|9000".getBytes(StandardCharsets.UTF_8);
        try {NetworkSupport.parseDiscovery(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("0.0.0.0"),9005));throw new AssertionError("wildcard reply accepted");}
        catch(IOException ex){check(true,"wildcard discovery reply rejected");}
        var old = NetworkSupport.parseDiscovery(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("198.51.100.20"),9005));
        check(old.machine().isBlank() && old.network().equals("Chưa rõ mạng"),"remote legacy server labels remain unknown instead of using client adapter");
        var metadata=Protocol.message("machine","Máy phòng | thực hành", "network.198.51.100.20","Wi-Fi", "network.192.0.2.1","Radmin VPN");
        var encoded=new ByteArrayOutputStream();Protocol.write(encoded,metadata);
        byte[] rich=("P2P_SERVER|9000|"+Base64.getEncoder().encodeToString(encoded.toByteArray())).getBytes(StandardCharsets.UTF_8);
        var labeled=NetworkSupport.parseDiscovery(new DatagramPacket(rich,rich.length,InetAddress.getByName("198.51.100.20"),9005));
        check(labeled.machine().equals("Máy phòng | thực hành") && labeled.network().equals("Wi-Fi"),"remote UTF-8 machine and matching source-interface metadata");
        check(labeled.ip().equals("198.51.100.20") && labeled.toString().contains("198.51.100.20:9000 — Wi-Fi — Máy:"),"server selection label retains actual packet source endpoint");
        check(NetworkSupport.networkLabel("wlan0","RZ616 Wi-Fi 6E 160MHz",false).equals("Wi-Fi"),"Wi-Fi adapter label");
        check(NetworkSupport.networkLabel("eth0","VMware Virtual Ethernet Adapter for VMnet8",false).contains("VMnet8"),"VMware adapter labeled before Ethernet");
        check(NetworkSupport.networkLabel("eth1","VirtualBox Host-Only Ethernet Adapter",false).startsWith("VirtualBox"),"VirtualBox adapter label");
        check(NetworkSupport.networkLabel("eth2","Famatech Radmin VPN Ethernet Adapter",false).equals("Radmin VPN"),"Radmin adapter labeled before Ethernet");
        check(NetworkSupport.networkLabel("lo","Software Loopback",true).contains("localhost"),"loopback label explains same-machine connection");
        check(labeled.networkPriority()<new NetworkSupport.ServerAddress("127.0.0.1",9000,"PC","Cùng máy (localhost)").networkPriority(),"Wi-Fi sorted ahead of localhost for LAN demo");
    }
    private static void history(Path folder) throws Exception {
        var file=folder.resolve("history.db");var db=new ChatDatabase(file);
        String body="C:\\new\\path | 😀❤️👩‍💻\nTiếng Việt";
        db.recordMessage("room","An | Bình","text",body,"",0);db.recordUser("An | Bình","127.0.0.1",9000);
        byte[] wav=AudioService.pcmToWav(new byte[160000],AudioService.FORMAT);String voice=db.saveVoice(wav);
        db.recordMessage("room","An","voice","5 seconds",voice,wav.length);
        var loaded=new ChatDatabase(file);
        check(loaded.allMessages().getFirst().content().equals(body),"history preserves literal escapes and emoji after reload");
        check(loaded.allUsers().containsKey("An | Bình"),"user name unescaped on reload");
        check(Arrays.equals(wav,loaded.readVoice(loaded.allMessages().get(1).attachmentName())),"voice note replay data persists after restart");
    }
}
