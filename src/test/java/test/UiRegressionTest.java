package test;

import client.*;
import common.*;
import server.IndexServer;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Swing interactions on EDT with real peer sockets; renders only this app's UI for visual QA. */
@SuppressWarnings("unchecked")
public final class UiRegressionTest {
    private static int checks;
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);System.out.println("PASS "+label);checks++;}
    @FunctionalInterface interface Query<T>{T run()throws Exception;}
    private static <T>T edt(Query<T> query)throws Exception {
        var result=new FutureTask<T>(()->query.run());SwingUtilities.invokeAndWait(result);return result.get();
    }
    @SuppressWarnings("unchecked") private static <T>T field(Object obj,String name)throws Exception {
        var field=obj.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(obj);
    }
    private static void invoke(Object obj,String name)throws Exception {
        var method=obj.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(obj);
    }
    private static void until(Query<Boolean> condition,String label)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){if(edt(condition)){check(true,label);return;}Thread.sleep(30);}
        throw new AssertionError(label);
    }
    private static List<JButton> buttons(Container root){
        var result=new ArrayList<JButton>();for(var child:root.getComponents()){
            if(child instanceof JButton button)result.add(button);
            if(child instanceof Container c)result.addAll(buttons(c));
        }return result;
    }
    private static void render(Container component,Path file)throws Exception {
        component.validate();
        if(component instanceof JFrame frame) component=frame.getContentPane();
        if(component instanceof JDialog dialog) component=dialog.getContentPane();
        var image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();component.printAll(graphics);graphics.dispose();ImageIO.write(image,"png",file.toFile());
    }
    public static void main(String[] args)throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Path temp=Files.createTempDirectory("p2p-ui-regression-");System.setProperty("p2p.data.dir",temp.toString());
        Path output=Path.of("build","qa").toAbsolutePath();Files.createDirectories(output);
        ClientWindow first=null,second=null;
        try(var server=new IndexServer("127.0.0.1",0,s->{});var sender=new Peer(new Peer.Events(){public void chat(String r,String s,String t){}public void status(String t){}});var udp=new UdpService()) {
            sender.connect("127.0.0.1",server.port(),"An Bình");sender.call("join","room","Phòng kiểm tra");
            first=edt(()->new ClientWindow("Người nhận"));second=edt(()->new ClientWindow("Ngoài phòng"));
            ClientWindow a=first,b=second;
            edt(()->{for(var ui:List.of(a,b)){
                ((JTextField)field(ui,"port")).setText(""+server.port());ui.addNotify();ui.validate();invoke(ui,"toggleConnection");
            }return null;});
            until(()->(boolean)field(a,"connected") && (boolean)field(b,"connected"),"two client windows connect");
            Peer pa=field(a,"peer");var members=Protocol.rows(pa.call("join","room","Phòng kiểm tra"));
            edt(()->{Map<String,List<Map<String,String>>> addresses=field(a,"addresses");addresses.put("Phòng kiểm tra",members);
                ((JComboBox<String>)field(a,"joinedRooms")).addItem("Phòng kiểm tra");return null;});
            String id=UUID.randomUUID().toString();String body="Xin chào 😀 😂 ❤️ 👍🏽 👩‍💻 <b>chữ thường</b>\nTiếng Việt có dấu";
            String wire=UdpService.envelope(Protocol.message("type","multicast","id",id,"room","Phòng kiểm tra","from","An Bình","text",body));
            udp.sendMulticast(wire);udp.sendMulticast(wire);
            until(()->((ChatDatabase)field(a,"database")).allMessages().size()==1,"multicast delivered once to joined room");
            edt(()->{check(((ChatDatabase)field(b,"database")).allMessages().isEmpty(),"multicast hidden from nonmember");
                check(((JTextPane)field(a,"conversation")).getText().contains("<b>chữ thường</b>"),"message displayed literally without HTML parsing");return null;});
            String bid=UUID.randomUUID().toString();
            sender.sendBroadcast(Map.of("ip","127.0.0.1","port",""+pa.port()),bid,"Thông báo toàn mạng 🚀");
            String broadcast=UdpService.envelope(Protocol.message("type","broadcast","id",bid,"from","An Bình","text","Thông báo toàn mạng 🚀"));
            udp.sendBroadcast(broadcast,UdpService.BROADCAST_PORT);udp.sendBroadcast(broadcast,UdpService.BROADCAST_PORT);
            until(()->((ChatDatabase)field(a,"database")).allMessages().size()==2 && ((ChatDatabase)field(b,"database")).allMessages().size()==1,"TCP + UDP broadcast deduplicated in both clients");
            var sentUdp=new LinkedBlockingQueue<String>();udp.joinMulticast(sentUdp::add);
            edt(()->{((JCheckBox)field(a,"chkMulticastUdp")).setSelected(true);
                ((JTextPane)field(a,"message")).setText("GUI multicast gửi từ kết nối loopback 😀");invoke(a,"send");return null;});
            String transmitted=sentUdp.poll(3,TimeUnit.SECONDS);
            check(transmitted!=null && UdpService.parseEnvelope(transmitted).get("text").equals("GUI multicast gửi từ kết nối loopback 😀"),"GUI sends multicast when server address is loopback");
            edt(()->{
                JTextPane composer=field(a,"message");composer.setText("Xin chào bạn");composer.select(4,8);
                ((JButton)field(a,"btnEmoji")).doClick();EmojiDialog dialog=field(a,"emojiDialog");
                render(dialog,output.resolve("emoji-picker.png"));
                JButton emoji=buttons(dialog).stream().filter(button->"😀".equals(button.getText())).findFirst().orElseThrow();emoji.doClick();
                check("Xin 😀 bạn".equals(composer.getText()),"emoji replaces selection at caret instead of appending");
                check(!dialog.isDisplayable(),"emoji picker disposed after selection");
                composer.setText("A👍🏽B");composer.setCaretPosition(5);
                composer.getActionMap().get("delete-previous-cluster").actionPerformed(null);
                check(composer.getText().equals("AB"),"backspace removes whole skin-tone emoji");
                composer.setText("A👩‍💻B");composer.setCaretPosition(1);
                composer.getActionMap().get("delete-next-cluster").actionPerformed(null);
                check(composer.getText().equals("AB"),"delete removes whole ZWJ emoji");
                var emojiGroups=EmojiDialog.class.getDeclaredField("EMOJI_GROUPS");emojiGroups.setAccessible(true);
                int missing=0;for(var group:(String[][])emojiGroups.get(null))for(int i=1;i<group.length;i++){
                    var font=EmojiSupport.font(group[i],23);
                    if(!group[i].codePoints().filter(c->c!=0xFE0F&&c!=0x200D).allMatch(font::canDisplay))missing++;
                }
                check(missing==0,"all picker emoji have font glyphs on this machine");
                composer.setText("Gửi biểu cảm 😀 😍 😎 ❤️ 👍🏽 👩‍💻 — tiếng Việt");
                return null;
            });
            edt(()->{EmojiSupport.style(((JTextPane)field(a,"conversation")).getStyledDocument());
                render(a,output.resolve("chat-window.png"));return null;});
            // Incoming calls can be declined or canceled without ever opening camera/microphone.
            try(var tasks=Executors.newVirtualThreadPerTaskExecutor();var call=new CallSession(UUID.randomUUID().toString(),false)) {
                var pending=tasks.submit(()->sender.requestCall(Map.of("ip","127.0.0.1","port",""+pa.port()),call));
                until(()->field(a,"incomingCallDialog")!=null,"incoming call dialog displayed");
                sender.endCall(Map.of("ip","127.0.0.1","port",""+pa.port()),call.id);
                until(()->field(a,"incomingCallDialog")==null && field(a,"activeCall")==null,"remote cancel dismisses dialog and releases call state");
                try{pending.get(3,TimeUnit.SECONDS);throw new AssertionError("cancel ignored");}catch(ExecutionException expected){}
            }
            // Calling a spaced username must reach the selected peer; receiver's default handler declines.
            until(()->((DefaultListModel<String>)field(a,"members")).size()>=1,"member list refreshed");
            edt(()->{JList<String> list=field(a,"memberList");for(int i=0;i<list.getModel().getSize();i++)
                if(list.getModel().getElementAt(i).startsWith("An Bình (")){list.setSelectedIndex(i);break;}
                invoke(a,"startDirectVoiceCall");return null;});
            until(()->field(a,"activeCall")==null && ((JLabel)field(a,"status")).getText().contains("Không hỗ trợ cuộc gọi"),"UI calling spaced username reaches peer and handles rejection");
            edt(()->{invoke(a,"toggleConnection");return null;});
            udp.sendMulticast(UdpService.envelope(Protocol.message("type","multicast","id",UUID.randomUUID().toString(),"room","Phòng kiểm tra","from","An Bình","text","sau ngắt kết nối")));
            Thread.sleep(200);
            edt(()->{check(((ChatDatabase)field(a,"database")).allMessages().size()==3,"disconnected client ignores UDP and own multicast not duplicated");return null;});
            System.out.println("ALL "+checks+" UI CHECKS PASSED");System.out.println("UI_PREVIEWS="+output);
        } finally {
            ClientWindow a=first,b=second;
            edt(()->{if(a!=null)a.dispose();if(b!=null)b.dispose();return null;});
            edt(()->null);
            try(var files=Files.walk(temp)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        }
    }
}
