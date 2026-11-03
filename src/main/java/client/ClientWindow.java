package client;

import common.ChatDatabase;
import common.Protocol;
import common.UdpService;
import common.NetworkSupport;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.*;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Giao diện Client Chat P2P phong cách hiện đại (tương tự Zalo/Messenger).
 * Hỗ trợ:
 * - Text chat với Icon biểu cảm (Emoji)
 * - Tin nhắn thoại (Voice note) và Voice Call qua UDP
 * - Cuộc gọi Video và chia sẻ màn hình trực tiếp qua UDP
 * - Đính kèm file (File Attachments) tải P2P qua TCP
 * - Multicast (nhóm phòng + UDP 230.0.0.1) và Broadcast (TCP toàn hệ thống + UDP LAN)
 * - Lưu trữ bền vững vào Cơ sở dữ liệu (ChatDatabase)
 */
public final class ClientWindow extends JFrame {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField host = new JTextField("127.0.0.1", 10);
    private final JTextField port = new JTextField("9000", 5);
    private final JTextField username = new JTextField("", 10);
    private final JTextPane message = new JTextPane();
    private final JTextField keyword = new JTextField(20);
    private final JButton connect = new JButton("Kết nối");
    private final JButton btnAutoDiscover = new JButton("Tìm Server (UDP)");
    private final JLabel status = new JLabel("Chưa kết nối");

    // Khung hiển thị chat định dạng HTML
    private final JTextPane conversation = new JTextPane();

    private final DefaultListModel<String> rooms = new DefaultListModel<>();
    private final DefaultListModel<String> members = new DefaultListModel<>();
    private final DefaultListModel<String> shared = new DefaultListModel<>();
    private final DefaultListModel<String> results = new DefaultListModel<>();
    private final JList<String> roomList = new JList<>(rooms);
    private final JList<String> memberList = new JList<>(members);
    private final JList<String> sharedList = new JList<>(shared);
    private final JList<String> resultList = new JList<>(results);
    private final JComboBox<String> joinedRooms = new JComboBox<>();
    private final JProgressBar progress = new JProgressBar(0, 100);

    // Điều khiển Chat & Đa phương tiện
    private final JButton btnEmoji = new JButton("Biểu cảm");
    private final JButton btnAttach = new JButton("Đính kèm");
    private final JButton btnVoice = new JButton("Ghi âm");
    private final JButton btnVideoCall = new JButton("Gọi Video");
    private final JButton btnBroadcast = new JButton("Broadcast");
    private final JCheckBox chkMulticastUdp = new JCheckBox("UDP Multicast", false);

    // Database tab
    private final DefaultTableModel dbTableModel = new DefaultTableModel(
        new String[]{"ID", "Phòng", "Người gửi", "Thể loại", "Nội dung", "Đính kèm", "Dung lượng", "Thời gian"}, 0);
    private final JTable dbTable = new JTable(dbTableModel);
    private final JTextField dbSearchField = new JTextField(18);

    private final Map<String, List<Map<String, String>>> addresses = new HashMap<>();
    private List<Map<String, String>> searchResults = List.of();

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AudioService audioService = new AudioService();
    private final UdpService udpService = new UdpService();
    private ChatDatabase database;

    private final javax.swing.Timer refreshTimer;
    private final Path downloadFolder = Path.of(System.getProperty("p2p.downloads",
        Path.of(System.getProperty("user.home"), "Downloads", "P2PChat").toString())).toAbsolutePath();

    private volatile Peer peer;
    private volatile boolean connected;
    private CallSession activeCall;
    private Map<String, String> callTarget;
    private VideoCallWindow callWindow;
    private JDialog incomingCallDialog;
    private Peer.CallOffer incomingOffer;
    private final JButton cancelCall = new JButton("Hủy cuộc gọi");
    private final LinkedHashSet<String> receivedIds = new LinkedHashSet<>();
    private String recordingRoom;
    private Peer recordingPeer;
    private List<Map<String, String>> recordingRecipients = List.of();
    private EmojiDialog emojiDialog;
    private final JLabel databasePath = new JLabel();
    private boolean refreshing;
    private volatile int session;

    public ClientWindow() {
        this("");
    }

    public ClientWindow(String initialName) {
        super("P2P Chat — Client (Zalo / Messenger Style)");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 680));
        setSize(1150, 750);
        setLocationByPlatform(true);

        if (initialName != null && !initialName.isBlank()) {
            username.setText(initialName.trim());
        }

        database = ChatDatabase.defaultInstance("local");

        // Top Header
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.setBackground(new Color(245, 246, 248));
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        top.add(new JLabel("IP server:")); top.add(host);
        top.add(new JLabel("Cổng:")); top.add(port);
        top.add(new JLabel("Tên:")); top.add(username);
        top.add(connect);
        top.add(btnAutoDiscover);
        host.setToolTipText("127.0.0.1 khi cùng máy; IP LAN của server khi khác máy. Không dùng 0.0.0.0.");
        top.add(cancelCall); cancelCall.setVisible(false);
        cancelCall.addActionListener(e -> endActiveCall(true));
        connect.addActionListener(event -> toggleConnection());
        btnAutoDiscover.addActionListener(event -> autoDiscoverServer());
        add(top, BorderLayout.NORTH);

        // Tabbed Panel
        var tabs = new JTabbedPane();
        tabs.addTab("Trò chuyện & Gọi điện", chatPanel());
        tabs.addTab("Chia sẻ & Tải file (P2P)", filePanel());
        tabs.addTab("Cơ sở dữ liệu (Database)", databasePanel());
        add(tabs, BorderLayout.CENTER);

        // Status Bar
        status.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        add(status, BorderLayout.SOUTH);

        udpService.onError(text -> SwingUtilities.invokeLater(() -> status.setText(text)));
        audioService.onError(text -> SwingUtilities.invokeLater(() -> status.setText(text)));
        try { udpService.joinMulticast(this::receiveUdp); }
        catch (Exception ex) { status.setText("Multicast chưa sẵn sàng: " + ex.getMessage()); }
        try { udpService.startBroadcastListener(UdpService.BROADCAST_PORT, this::receiveUdp); }
        catch (Exception ex) { status.setText("UDP Broadcast chưa sẵn sàng: " + ex.getMessage()); }

        refreshTimer = new javax.swing.Timer(2000, event -> {
            refresh();
            if (audioService.recordingLimitReached()) finishVoiceRecording();
        });
        refreshTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                session++;
                refreshTimer.stop();
                endActiveCall(true);
                if (emojiDialog != null) emojiDialog.dispose();
                audioService.close();
                udpService.close();
                if (peer != null) peer.close();
                workers.shutdownNow();
            }
        });
    }

    private JPanel chatPanel() {
        var left = new JPanel(new BorderLayout(4, 4));
        left.setPreferredSize(new Dimension(300, 480));

        // Phòng chat
        var roomPanel = new JPanel(new BorderLayout());
        roomPanel.setBorder(BorderFactory.createTitledBorder("Danh sách Phòng"));
        roomPanel.add(new JScrollPane(roomList), BorderLayout.CENTER);
        roomPanel.add(buttons(
            button("Tạo phòng", () -> join(true)),
            button("Tham gia", () -> join(false)),
            button("Làm mới", this::refresh)
        ), BorderLayout.SOUTH);
        roomList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) join(false); }
        });

        // Thành viên
        var memberPanel = new JPanel(new BorderLayout());
        memberPanel.setBorder(BorderFactory.createTitledBorder("Thành viên / Peer LAN"));
        memberPanel.add(new JScrollPane(memberList), BorderLayout.CENTER);

        var memberActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        var btnCallPeer = button("Gọi Video", this::startDirectVideoCall);
        var btnVoiceCallPeer = button("Gọi Thoại", this::startDirectVoiceCall);
        memberActions.add(btnCallPeer);
        memberActions.add(btnVoiceCallPeer);
        memberPanel.add(memberActions, BorderLayout.SOUTH);

        left.add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, roomPanel, memberPanel), BorderLayout.CENTER);

        // Chat View bên phải
        var right = new JPanel(new BorderLayout(4, 4));
        var roomBar = new JPanel(new BorderLayout(8, 4));
        roomBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        roomBar.setBackground(new Color(238, 242, 246));

        var roomTitlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        roomTitlePanel.setOpaque(false);
        roomTitlePanel.add(new JLabel("Đang vào phòng:"));
        roomTitlePanel.add(joinedRooms);
        roomBar.add(roomTitlePanel, BorderLayout.WEST);

        var roomRightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        roomRightControls.setOpaque(false);
        roomRightControls.add(chkMulticastUdp);
        roomRightControls.add(btnBroadcast);
        roomRightControls.add(button("Rời phòng", this::leave));
        roomBar.add(roomRightControls, BorderLayout.EAST);

        joinedRooms.addActionListener(event -> { showRoom(); refresh(); });
        btnBroadcast.addActionListener(event -> promptSendBroadcast());
        right.add(roomBar, BorderLayout.NORTH);

        // Khung nội dung trò chuyện HTML
        EmojiSupport.install(conversation);
        conversation.setBackground(new Color(245, 247, 250));
        conversation.setEditable(false);
        conversation.setMargin(new Insets(8, 8, 8, 8));
        var scrollPane = new JScrollPane(conversation);
        right.add(scrollPane, BorderLayout.CENTER);

        // Khung soạn thảo tin nhắn đa phương tiện
        var bottomCompose = new JPanel(new BorderLayout(6, 6));
        bottomCompose.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Thanh công cụ đính kèm & biểu cảm
        var mediaToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        mediaToolbar.add(btnEmoji);
        mediaToolbar.add(btnAttach);
        mediaToolbar.add(btnVoice);
        mediaToolbar.add(btnVideoCall);

        btnEmoji.setToolTipText("Chèn biểu cảm tại vị trí con trỏ");
        btnEmoji.addActionListener(event -> {
            if (emojiDialog != null && emojiDialog.isDisplayable()) { emojiDialog.toFront(); return; }
            emojiDialog = new EmojiDialog(this, emoji -> {
                message.replaceSelection(emoji);
                message.requestFocusInWindow();
            });
            emojiDialog.setVisible(true);
        });

        btnAttach.addActionListener(event -> sendAttachmentPrompt());
        btnVoice.addActionListener(event -> toggleVoiceRecording());
        btnVideoCall.addActionListener(event -> startDirectVideoCall());

        bottomCompose.add(mediaToolbar, BorderLayout.NORTH);

        var textInputPanel = new JPanel(new BorderLayout(6, 6));
        EmojiSupport.install(message);
        message.setPreferredSize(new Dimension(240, 48));
        message.setToolTipText("Enter để gửi; Shift+Enter để xuống dòng");
        message.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        message.getActionMap().put("send-message", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { send(); }
        });
        message.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        var btnSend = new JButton("Gửi (Enter) ▶");
        btnSend.setFont(new Font(Font.DIALOG, Font.BOLD, 13));
        btnSend.addActionListener(event -> send());

        textInputPanel.add(new JScrollPane(message), BorderLayout.CENTER);
        textInputPanel.add(btnSend, BorderLayout.EAST);
        bottomCompose.add(textInputPanel, BorderLayout.CENTER);

        right.add(bottomCompose, BorderLayout.SOUTH);

        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(310);
        var panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel filePanel() {
        var local = new JPanel(new BorderLayout(6, 6));
        local.setBorder(BorderFactory.createTitledBorder("File bạn đang chia sẻ trong LAN"));
        local.add(new JScrollPane(sharedList), BorderLayout.CENTER);
        local.add(buttons(button("Thêm file chia sẻ…", this::share), button("Bỏ chia sẻ", this::unshare)), BorderLayout.SOUTH);

        var remote = new JPanel(new BorderLayout(6, 6));
        remote.setBorder(BorderFactory.createTitledBorder("Tìm kiếm và tải file P2P từ các Peer khác"));
        remote.add(buttons(new JLabel("Từ khóa:"), keyword, button("Tìm kiếm", this::search)), BorderLayout.NORTH);
        keyword.addActionListener(event -> search());
        remote.add(new JScrollPane(resultList), BorderLayout.CENTER);
        progress.setStringPainted(true);
        remote.add(buttons(button("Tải file đã chọn về máy", this::download), progress), BorderLayout.SOUTH);

        var panel = new JPanel(new BorderLayout(8, 8));
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, local, remote);
        split.setResizeWeight(0.4);
        split.setDividerLocation(220);
        panel.add(split, BorderLayout.CENTER);
        panel.add(new JLabel("Thư mục lưu file: " + downloadFolder), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel databasePanel() {
        var panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Tìm trong Database:"));
        top.add(dbSearchField);
        var btnSearchDb = button("Tìm kiếm", this::refreshDatabaseTable);
        var btnReloadDb = button("Tải lại toàn bộ", () -> { dbSearchField.setText(""); refreshDatabaseTable(); });
        var btnClearDb = button("Xóa sạch DB", () -> {
            if (JOptionPane.showConfirmDialog(this, "Bạn có chắc muốn xóa lịch sử DB?", "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                database.clear();
                refreshDatabaseTable();
            }
        });
        top.add(btnSearchDb);
        top.add(btnReloadDb);
        top.add(btnClearDb);
        dbSearchField.addActionListener(event -> refreshDatabaseTable());
        panel.add(top, BorderLayout.NORTH);

        panel.add(new JScrollPane(dbTable), BorderLayout.CENTER);
        databasePath.setText("Tập tin lịch sử: " + database.getDbFile());
        panel.add(databasePath, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshDatabaseTable() {
        databasePath.setText("Tập tin lịch sử: " + database.getDbFile());
        dbTableModel.setRowCount(0);
        String query = dbSearchField.getText().strip();
        var list = query.isEmpty() ? database.allMessages() : database.search(query);
        for (var msg : list) {
            dbTableModel.addRow(new Object[]{
                msg.id(), msg.room(), msg.sender(), msg.type(), msg.content(),
                msg.attachmentName(), msg.attachmentSize() > 0 ? (msg.attachmentSize() / 1024 + " KB") : "",
                msg.timestamp()
            });
        }
    }

    private static JButton button(String label, Runnable action) {
        var b = new JButton(label);
        b.addActionListener(event -> action.run());
        return b;
    }

    private static JPanel buttons(Component... components) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        for (var c : components) panel.add(c);
        return panel;
    }

    private String room() { return (String) joinedRooms.getSelectedItem(); }
    private boolean requireConnection() {
        if (!connected) status.setText("Hãy kết nối trước");
        return connected;
    }
    private boolean requireRoom() {
        if (!requireConnection()) return false;
        if (room() == null) { status.setText("Hãy tạo hoặc tham gia một phòng chat"); return false; }
        return true;
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void work(Work<T> task, Consumer<T> success) { work(task, success, () -> {}); }
    private <T> void work(Work<T> task, Consumer<T> success, Runnable finished) {
        int generation = session;
        workers.submit(() -> {
            try {
                T result = task.run();
                SwingUtilities.invokeLater(() -> {
                    if (generation == session) { try { success.accept(result); } finally { finished.run(); } }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == session) { status.setText("Lỗi: " + ex.getMessage()); finished.run(); }
                });
            }
        });
    }

    private void autoDiscoverServer() {
        btnAutoDiscover.setEnabled(false);
        status.setText("Đang tìm server trên các card mạng...");
        work(() -> NetworkSupport.discover(2500), servers -> {
            if (servers.isEmpty()) { status.setText("Không tìm thấy server. Kiểm tra server và mạng LAN hoặc nhập IP thủ công."); return; }
            NetworkSupport.ServerAddress selected = servers.getFirst();
            if (servers.size() > 1) {
                selected = (NetworkSupport.ServerAddress) JOptionPane.showInputDialog(this,
                    "Chọn địa chỉ server:\nIP:cổng — Card mạng trên server — Tên máy\nDemo qua Wi-Fi: chọn dòng Wi-Fi. Chạy cùng máy: chọn localhost.", "Địa chỉ server tìm thấy",
                    JOptionPane.QUESTION_MESSAGE, null, servers.toArray(), selected);
            }
            if (selected == null) return;
            host.setText(selected.ip()); port.setText("" + selected.port());
            status.setText("Đã tìm thấy server " + selected + ". Bấm Kết nối.");
        }, () -> btnAutoDiscover.setEnabled(!connected));
    }

    private boolean remember(String id) {
        if (id == null || id.isBlank() || !receivedIds.add(id)) return false;
        while (receivedIds.size() > 4096) receivedIds.remove(receivedIds.iterator().next());
        return true;
    }
    private void receiveUdp(String wire) {
        final Map<String, String> msg;
        try { msg = UdpService.parseEnvelope(wire); } catch (Exception ex) { return; }
        SwingUtilities.invokeLater(() -> {
            if (!connected || peer == null) return;
            String type = msg.get("type"), sender = msg.get("from"), text = msg.get("text");
            if (sender == null || sender.equals(peer.name()) || text == null || text.length() > 8000) return;
            if ("broadcast".equals(type)) { receiveBroadcast(msg.get("id"), sender, text); return; }
            String room = msg.get("room");
            if (!"multicast".equals(type) || room == null || !addresses.containsKey(room) || !remember(msg.get("id"))) return;
            database.recordMessage(room, sender, "multicast", text, "", 0);
            appendMessage(room, sender, text, "multicast", null, 0);
            refreshDatabaseTable();
        });
    }
    private void receiveBroadcast(String id, String sender, String text) {
        if (!remember(id)) return;
        database.recordMessage("BROADCAST", sender, "broadcast", text, "", 0);
        appendMessage("BROADCAST", sender, text, "broadcast", null, 0);
        refreshDatabaseTable();
    }

    private void toggleConnection() {
        if (connected) {
            session++;
            endActiveCall(true);
            try { audioService.stopRecording(); } catch (Exception ignored) {}
            btnVoice.setText("Ghi âm"); btnVoice.setEnabled(true);
            peer.close();
            connected = false;
            host.setEnabled(true); port.setEnabled(true); username.setEnabled(true); btnAutoDiscover.setEnabled(true);
            refreshing = false;
            connect.setText("Kết nối");
            joinedRooms.removeAllItems();
            rooms.clear(); members.clear(); shared.clear(); results.clear(); addresses.clear();
            conversation.setText("");
            searchResults = List.of(); receivedIds.clear();
            status.setText("Đã ngắt kết nối");
            return;
        }
        final int serverPort;
        try {
            serverPort = Integer.parseInt(port.getText().strip());
            if (serverPort < 1 || serverPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) { status.setText("Cổng phải từ 1 đến 65535"); return; }
        String serverHost = host.getText().strip(), name = username.getText().strip();
        if (serverHost.isEmpty() || name.isEmpty()) { status.setText("Nhập server và tên người dùng"); return; }
        session++;
        int generation = session;

        database = ChatDatabase.defaultInstance(name);
        ChatDatabase sessionDatabase = database;
        refreshDatabaseTable();

        Peer candidate = new Peer(new Peer.Events() {
            public void chat(String room, String sender, String text) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == session) {
                        appendMessage(room, sender, text, "text", null, 0);
                        database.recordMessage(room, sender, "text", text, "", 0);
                        refreshDatabaseTable();
                    }
                });
            }
            public void voice(String room, String sender, byte[] wavData) {
                final String file;
                try { file = sessionDatabase.saveVoice(wavData); }
                catch (Exception ex) { status("Không lưu được tin nhắn thoại: " + ex.getMessage()); return; }
                SwingUtilities.invokeLater(() -> {
                    if (generation == session) {
                        String text = "Tin nhắn thoại " + String.format(Locale.ROOT, "%.1f", (wavData.length - 44) / 32000.0) + " giây";
                        appendMessage(room, sender, text, "voice", file, wavData.length);
                        database.recordMessage(room, sender, "voice", text, file, wavData.length);
                        refreshDatabaseTable();
                    }
                });
            }
            public void attachment(String room, String sender, String filename, long size, String ip, int peerPort) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == session) {
                        appendMessage(room, sender, "Đã gửi đính kèm file: " + filename + " (" + (size / 1024) + " KB)", "file", filename, size);
                        database.recordMessage(room, sender, "file", filename, filename, size);
                        refreshDatabaseTable();
                        promptDownloadAttachment(sender, filename, size, ip, peerPort);
                    }
                });
            }
            public void broadcast(String id, String sender, String text) {
                SwingUtilities.invokeLater(() -> { if (generation == session) receiveBroadcast(id, sender, text); });
            }
            public void incomingCall(Peer.CallOffer offer) {
                SwingUtilities.invokeLater(() -> {
                    if (generation != session) { offer.reject("Đã ngắt kết nối"); return; }
                    receiveCall(offer);
                });
            }
            public void callEnded(String id) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == session && activeCall != null && activeCall.id.equals(id)) {
                        endActiveCall(false); status.setText("Đối phương đã kết thúc/hủy cuộc gọi");
                    }
                });
            }
            public void status(String text) {
                SwingUtilities.invokeLater(() -> { if (generation == session) status.setText(text); });
            }
        });
        peer = candidate;
        connect.setEnabled(false);
        status.setText("Đang kết nối…");
        work(() -> { candidate.connect(serverHost, serverPort, name); return candidate; }, value -> {
            connected = true;
            connect.setText("Ngắt kết nối");
            status.setText("Đã kết nối: " + name + " (P2P TCP port: " + candidate.port() + ")");
            host.setEnabled(false); port.setEnabled(false); username.setEnabled(false); btnAutoDiscover.setEnabled(false);
            try { udpService.setNetworkInterface(NetworkInterface.getByInetAddress(candidate.localAddress())); }
            catch (SocketException ex) { status.setText("Không chọn được card mạng multicast: " + ex.getMessage()); }
            database.recordUser(name, candidate.localAddress().getHostAddress(), candidate.port());
            refresh();
        }, () -> connect.setEnabled(true));
    }

    private void promptDownloadAttachment(String sender, String filename, long size, String ip, int peerPort) {
        int choice = JOptionPane.showConfirmDialog(this,
            "Peer [" + sender + "] đính kèm file: " + filename + " (" + (size / 1024) + " KB).\nBạn có muốn tải về thư mục Downloads ngay không?",
            "File đính kèm mới", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            var entry = Map.of("filename", filename, "ip", ip, "port", "" + peerPort);
            work(() -> peer.download(entry, downloadFolder, value -> SwingUtilities.invokeLater(() -> progress.setValue((int) value))),
                path -> status.setText("Đã tải thành công: " + path));
        }
    }

    private void appendMessage(String room, String sender, String text, String type, String extra, long size) {
        if (!"broadcast".equals(type) && !Objects.equals(room, room())) return;
        boolean own = "Tôi".equals(sender) || (peer != null && Objects.equals(peer.name(), sender));
        Color color = own ? new Color(21, 101, 192) : new Color(46, 105, 55);
        String label = "broadcast".equals(type) ? "[Broadcast] " : "multicast".equals(type) ? "[Multicast] " : "";
        EmojiSupport.append(conversation, label + sender + "  [" + LocalTime.now().format(TIME_FORMAT) + "]\n", color, true, 12);
        EmojiSupport.append(conversation, text + "\n\n", new Color(30, 35, 42), false, 15);
        if ("voice".equals(type) && extra != null && !extra.isBlank()) {
            var play = new JButton("Phát tin nhắn thoại");
            ChatDatabase history = database;
            play.addActionListener(e -> work(() -> {
                audioService.playAudio(history.readVoice(extra), null); return true;
            }, ok -> status.setText("Đang phát tin nhắn thoại")));
            conversation.setCaretPosition(conversation.getDocument().getLength());
            conversation.insertComponent(play);
            EmojiSupport.append(conversation, "\n\n", Color.DARK_GRAY, false, 15);
        }
        conversation.setCaretPosition(conversation.getDocument().getLength());
    }

    private record Snapshot(List<Map<String, String>> rooms, List<Map<String, String>> members, List<Map<String, String>> allUsers) {}
    private void refresh() {
        if (!connected || refreshing) return;
        refreshing = true;
        Peer active = peer;
        String selected = room();
        work(() -> new Snapshot(
            Protocol.rows(active.call("rooms")),
            selected == null ? List.of() : Protocol.rows(active.call("members", "room", selected)),
            Protocol.rows(active.call("users"))
        ), snapshot -> {
            String old = roomList.getSelectedValue();
            rooms.clear();
            snapshot.rooms().forEach(row -> rooms.addElement(row.get("room")));
            roomList.setSelectedValue(old, true);
            if (selected != null) addresses.put(selected, snapshot.members());

            // Cập nhật danh sách thành viên
            String selectedMember = memberList.getSelectedValue();
            members.clear();
            for (var u : snapshot.allUsers()) {
                if (!u.get("name").equals(active.name())) {
                    members.addElement(u.get("name") + " (" + u.get("ip") + ":" + u.get("port") + ")");
                }
            }
            memberList.setSelectedValue(selectedMember, true);
        }, () -> refreshing = false);
    }

    private void join(boolean create) {
        if (!requireConnection()) return;
        String selected = create ? JOptionPane.showInputDialog(this, "Tên phòng mới:") : roomList.getSelectedValue();
        if (selected == null || selected.isBlank()) return;
        String room = selected.strip();
        Peer active = peer;
        work(() -> Protocol.rows(active.call(create ? "create" : "join", "room", room)), list -> {
            addresses.put(room, list);
            boolean found = false;
            for (int i = 0; i < joinedRooms.getItemCount(); i++) found |= room.equals(joinedRooms.getItemAt(i));
            if (!found) joinedRooms.addItem(room);
            joinedRooms.setSelectedItem(room);
            showRoom();
            refresh();
            status.setText("Đang ở phòng: " + room);
        });
    }

    private void leave() {
        if (!requireRoom()) return;
        String selected = room(); Peer active = peer;
        work(() -> active.call("leave", "room", selected), reply -> {
            addresses.remove(selected);
            joinedRooms.removeItem(selected);
            refresh();
        });
    }

    private void showRoom() {
        conversation.setText("");
        String selected = room();
        if (selected != null) {
            // Tải lại lịch sử gần đây từ database
            var recent = database.getRecentMessages(selected, 25);
            for (var msg : recent) {
                appendMessage(msg.room(), msg.sender(), msg.content(), msg.type(), msg.attachmentName(), msg.attachmentSize());
            }
        }
    }

    private void send() {
        if (!requireRoom()) return;
        String text = message.getText().strip(), selected = room();
        if (text.isEmpty()) return;
        if (text.length() > 8000) { status.setText("Tin nhắn tối đa 8000 ký tự"); return; }
        Peer active = peer;
        var recipients = List.copyOf(addresses.getOrDefault(selected, List.of()));
        message.setText("");

        appendMessage(selected, "Tôi", text, "text", null, 0);
        database.recordMessage(selected, active.name(), "text", text, "", 0);
        refreshDatabaseTable();

        if (chkMulticastUdp.isSelected()) {
            String id = UUID.randomUUID().toString(); remember(id);
            work(() -> {
                udpService.sendMulticast(UdpService.envelope(Protocol.message("type", "multicast", "id", id,
                    "room", selected, "from", active.name(), "text", text)));
                return "UDP Multicast";
            }, res -> status.setText("Đã gửi tin qua UDP Multicast (230.0.0.1)"));
        } else {
            // Chế độ P2P TCP chuẩn
            work(() -> {
                var failures = new ArrayList<String>();
                for (var recipient : recipients) if (!active.name().equals(recipient.get("name"))) {
                    try { active.sendChat(recipient, selected, text); }
                    catch (Exception ex) { failures.add(recipient.get("name") + ": " + ex.getMessage()); }
                }
                return failures;
            }, failures -> status.setText(failures.isEmpty() ? "Đã gửi P2P TCP" : "Lỗi: " + String.join("; ", failures)));
        }
    }

    private void toggleVoiceRecording() {
        if (audioService.isRecording()) { finishVoiceRecording(); return; }
        if (!requireRoom()) return;
        recordingRoom = room(); recordingPeer = peer;
        recordingRecipients = List.copyOf(addresses.getOrDefault(recordingRoom, List.of()));
        int generation = session;
        btnVoice.setEnabled(false);
        work(() -> {
            if (generation != session || !connected) throw new java.io.IOException("Phiên ghi âm đã đóng");
            audioService.startRecording(level -> SwingUtilities.invokeLater(() -> {
                if (generation == session && audioService.isRecording()) btnVoice.setText("Dừng & gửi (" + level + "%)");
            }));
            if (generation != session || !connected) {
                audioService.stopRecording();
                throw new java.io.IOException("Phiên ghi âm đã đóng");
            }
            return true;
        }, ok -> status.setText("Đang ghi âm, tối đa 5 phút. Bấm Dừng & gửi để gửi."), () -> btnVoice.setEnabled(true));
    }
    private record VoiceResult(String text, String status, String file, int size) {}
    private void finishVoiceRecording() {
        String selected = recordingRoom; Peer active = recordingPeer;
        var recipients = recordingRecipients;
        ChatDatabase history = database;
        btnVoice.setEnabled(false); btnVoice.setText("Đang gửi...");
        work(() -> {
            byte[] wav = audioService.stopRecording();
            if (wav.length <= 44) throw new java.io.IOException("Chưa có dữ liệu ghi âm");
            if (active != peer || !connected) throw new java.io.IOException("Phiên ghi âm đã ngắt kết nối");
            var failed = new ArrayList<String>(); int sent = 0;
            for (var recipient : recipients) if (!active.name().equals(recipient.get("name"))) {
                try { active.sendVoice(recipient, selected, wav); sent++; }
                catch (Exception ex) { failed.add(recipient.get("name") + ": " + ex.getMessage()); }
            }
            if (sent == 0) throw new java.io.IOException(failed.isEmpty() ? "Không có người nhận trong phòng" : String.join("; ", failed));
            String info = "Tin nhắn thoại " + String.format(java.util.Locale.ROOT, "%.1f", (wav.length - 44) / 32000.0) + " giây";
            return new VoiceResult(info, failed.isEmpty() ? "Đã gửi tin nhắn thoại" : "Gửi chưa đủ: " + String.join("; ", failed), history.saveVoice(wav), wav.length);
        }, result -> {
            database.recordMessage(selected, active.name(), "voice", result.text(), result.file(), result.size());
            appendMessage(selected, "Tôi", result.text(), "voice", result.file(), result.size());
            status.setText(result.status()); refreshDatabaseTable();
        }, () -> { btnVoice.setEnabled(true); btnVoice.setText("Ghi âm"); });
    }

    private void sendAttachmentPrompt() {
        if (!requireRoom()) return;
        var chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        String selected = room();
        Peer active = peer;

        work(() -> {
            active.share(path, selected);
            long size = Files.size(path);
            var recipients = List.copyOf(addresses.getOrDefault(selected, List.of()));
            for (var r : recipients) if (!active.name().equals(r.get("name"))) {
                active.sendAttachmentAnnounce(r, selected, path.getFileName().toString(), size, active.port());
            }
            database.recordMessage(selected, active.name(), "file", path.getFileName().toString(), path.getFileName().toString(), size);
            return path;
        }, p -> {
            appendMessage(selected, "Tôi", "Đã đính kèm file: " + p.getFileName(), "file", p.getFileName().toString(), 0);
            status.setText("Đã gửi đính kèm P2P: " + p.getFileName());
            refreshDatabaseTable();
        });
    }

    private void promptSendBroadcast() {
        if (!requireConnection()) return;
        String text = JOptionPane.showInputDialog(this, "Thông báo đến tất cả người dùng:");
        if (text == null || text.isBlank()) return;
        if (text.length() > 8000) { status.setText("Thông báo tối đa 8000 ký tự"); return; }
        Peer active = peer; String id = UUID.randomUUID().toString(); remember(id);
        work(() -> {
            var failures = new ArrayList<String>();
            try { udpService.sendBroadcast(UdpService.envelope(Protocol.message("type", "broadcast", "id", id,
                "from", active.name(), "text", text)), UdpService.BROADCAST_PORT); }
            catch (Exception ex) { failures.add("UDP: " + ex.getMessage()); }
            try {
                for (var user : Protocol.rows(active.call("users"))) if (!active.name().equals(user.get("name"))) {
                    try { active.sendBroadcast(user, id, text); }
                    catch (Exception ex) { failures.add(user.get("name") + ": " + ex.getMessage()); }
                }
            } catch (Exception ex) { failures.add("Danh bạ/TCP: " + ex.getMessage()); }
            return failures;
        }, failures -> {
            database.recordMessage("BROADCAST", active.name(), "broadcast", text, "", 0);
            appendMessage("BROADCAST", active.name(), text, "broadcast", null, 0);
            status.setText(failures.isEmpty() ? "Đã phát Broadcast TCP + UDP" : "Broadcast có lỗi: " + String.join("; ", failures));
            refreshDatabaseTable();
        });
    }
    private void startDirectVideoCall() { startCall(true); }
    private void startDirectVoiceCall() { startCall(false); }
    private void startCall(boolean video) {
        if (!requireConnection()) return;
        if (activeCall != null) { status.setText("Hãy kết thúc cuộc gọi hiện tại trước"); return; }
        String selected = memberList.getSelectedValue();
        if (selected == null) { status.setText("Chọn một người trong danh sách thành viên để gọi"); return; }
        String targetName = selected.substring(0, selected.lastIndexOf(" ("));
        Peer active = peer; int generation = session;
        final CallSession media;
        try { media = new CallSession(UUID.randomUUID().toString(), video); }
        catch (Exception ex) { status.setText("Không mở được cổng media: " + ex.getMessage()); return; }
        activeCall = media; cancelCall.setVisible(true);
        status.setText("Đang gọi " + targetName + "...");
        workers.submit(() -> {
            try {
                var target = Protocol.rows(active.call("users")).stream()
                    .filter(u -> targetName.equals(u.get("name"))).findFirst()
                    .orElseThrow(() -> new java.io.IOException("Người nhận không còn online"));
                SwingUtilities.invokeAndWait(() -> { if (activeCall == media) callTarget = target; });
                Peer.CallReply reply = active.requestCall(target, media);
                SwingUtilities.invokeLater(() -> {
                    if (generation != session || activeCall != media || media.isClosed()) return;
                    openCallWindow(targetName, media, reply);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == session && activeCall == media) {
                        endActiveCall(true); status.setText("Cuộc gọi: " + ex.getMessage());
                    }
                });
            }
        });
    }
    private void receiveCall(Peer.CallOffer offer) {
        if (!offer.pending()) return;
        if (!connected || activeCall != null) { offer.reject("Người nhận đang bận"); return; }
        try { activeCall = new CallSession(offer.id, offer.videoEnabled); }
        catch (Exception ex) { offer.reject("Không mở được cổng media"); return; }
        CallSession media = activeCall; incomingOffer = offer;
        callTarget = Map.of("ip", offer.ip, "port", "" + offer.peerPort);
        var pane = new JOptionPane(offer.sender + (offer.videoEnabled ? " đang gọi video." : " đang gọi thoại.") + "\nNhận cuộc gọi?",
            JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        incomingCallDialog = pane.createDialog(this, "Cuộc gọi đến");
        incomingCallDialog.setModal(false);
        JDialog dialog = incomingCallDialog;
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, event -> {
            if (pane.getValue() == JOptionPane.UNINITIALIZED_VALUE || activeCall != media) return;
            boolean accepted = Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
            incomingCallDialog = null; incomingOffer = null; dialog.dispose();
            if (accepted && offer.accept(media)) {
                openCallWindow(offer.sender, media, new Peer.CallReply(offer.ip, offer.videoPort, offer.audioPort));
            } else { offer.reject("Đã từ chối cuộc gọi"); endActiveCall(false); }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (activeCall == media && incomingOffer != null) { offer.reject("Đã từ chối cuộc gọi"); endActiveCall(false); }
            }
        });
        dialog.setVisible(true);
    }
    private void openCallWindow(String name, CallSession media, Peer.CallReply remote) {
        cancelCall.setVisible(false);
        callWindow = new VideoCallWindow(peer.name(), name, media, remote, () -> {
            if (activeCall == media) endActiveCall(true);
        });
        callWindow.setVisible(true); status.setText("Đã nhận cuộc gọi với " + name);
    }
    private void endActiveCall(boolean notifyPeer) {
        CallSession media = activeCall;
        if (media == null) return;
        Peer active = peer; var target = callTarget;
        activeCall = null; callTarget = null; cancelCall.setVisible(false);
        if (incomingOffer != null) { incomingOffer.reject("Cuộc gọi đã đóng"); incomingOffer = null; }
        if (incomingCallDialog != null) { incomingCallDialog.dispose(); incomingCallDialog = null; }
        media.close();
        if (active != null) active.cancelPendingCall(media.id);
        if (callWindow != null) { var window = callWindow; callWindow = null; window.dispose(); }
        if (notifyPeer && active != null && target != null) Thread.ofVirtual().start(() -> {
            try { active.endCall(target, media.id); } catch (Exception ignored) {}
        });
        status.setText("Đã kết thúc cuộc gọi");
    }

    private void share() {
        if (!requireRoom()) return;
        var chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath(); String selected = room(); Peer active = peer;
        work(() -> { active.share(path, selected); return active.shared(); }, entries -> {
            shared.clear(); entries.keySet().stream().sorted().forEach(shared::addElement);
            status.setText("Đã chia sẻ file: " + path.getFileName());
        });
    }

    private void unshare() {
        if (!requireConnection()) return;
        String filename = sharedList.getSelectedValue(); Peer active = peer;
        if (filename == null) return;
        work(() -> { active.unshare(filename); return filename; }, shared::removeElement);
    }

    private void search() {
        if (!requireConnection()) return;
        String query = keyword.getText().strip(); Peer active = peer;
        work(() -> Protocol.rows(active.call("search", "keyword", query)), list -> {
            searchResults = list; results.clear();
            list.forEach(row -> results.addElement(row.get("filename") + " — " + row.get("size") + " B — " + row.get("owner") + " [" + row.get("room") + "]"));
            status.setText("Tìm thấy " + list.size() + " file");
        });
    }

    private void download() {
        if (!requireConnection()) return;
        int index = resultList.getSelectedIndex();
        if (index < 0 || index >= searchResults.size()) return;
        var entry = Map.copyOf(searchResults.get(index)); Peer active = peer;
        int generation = session;
        progress.setValue(0);
        work(() -> active.download(entry, downloadFolder, value -> SwingUtilities.invokeLater(() -> {
            if (generation == session) progress.setValue((int) value);
        })), path -> status.setText("Đã tải xong: " + path));
    }

    public static void main(String[] args) {
        ClientMain.main(args);
    }
}
