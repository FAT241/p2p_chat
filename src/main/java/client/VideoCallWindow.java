package client;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import common.UdpService;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.util.concurrent.atomic.*;

/** One accepted call owns one pair of reserved media sockets. */
public final class VideoCallWindow extends JFrame {
    private final CallSession media;
    private final Peer.CallReply remote;
    private final UdpService udp = new UdpService();
    private final AudioService audio = new AudioService();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cameraEnabled = new AtomicBoolean(true);
    private final AtomicBoolean screenEnabled = new AtomicBoolean();
    private final AtomicInteger frameCount = new AtomicInteger();
    private volatile BufferedImage localFrame, remoteFrame;
    private volatile Webcam webcam;
    private volatile Robot robot;
    private final JLabel status = new JLabel("Đang mở thiết bị...");
    private final JLabel fps = new JLabel("0 FPS");
    private final JProgressBar micLevel = new JProgressBar(0, 100);
    private final JButton micButton = new JButton("Tắt mic");
    private final JButton cameraButton = new JButton("Tắt camera");
    private final JButton screenButton = new JButton("Chia sẻ màn hình");
    private final JPanel canvas;
    private final Timer timer;

    public VideoCallWindow(String localName, String peerName, CallSession media, Peer.CallReply remote, Runnable onClosed) {
        super((media.videoEnabled ? "Gọi video — " : "Gọi thoại — ") + peerName);
        this.media = media; this.remote = remote;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(850, 580); setMinimumSize(new Dimension(620, 420)); setLocationRelativeTo(null);
        var header = new JPanel(new BorderLayout(12, 4));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        header.add(new JLabel(peerName + " — " + remote.ip()), BorderLayout.WEST);
        micLevel.setStringPainted(true); micLevel.setString("Micro");
        var indicators = new JPanel(new FlowLayout()); indicators.add(micLevel); indicators.add(fps);
        header.add(indicators, BorderLayout.EAST); add(header, BorderLayout.NORTH);
        canvas = new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var g = (Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (remoteFrame != null) drawFit(g, remoteFrame, 0, 0, getWidth(), getHeight());
                else {
                    g.setColor(Color.WHITE); g.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
                    String label = media.videoEnabled ? "Đang chờ hình ảnh từ " + peerName : "Cuộc gọi thoại với " + peerName;
                    g.drawString(label, 24, getHeight() / 2);
                }
                if (localFrame != null) {
                    drawFit(g, localFrame, getWidth() - 208, getHeight() - 164, 192, 144);
                    g.setColor(Color.WHITE); g.drawString(localName, getWidth() - 202, getHeight() - 24);
                }
                g.dispose();
            }
        };
        canvas.setBackground(new Color(24, 28, 36)); add(canvas, BorderLayout.CENTER);
        var controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        cameraButton.setEnabled(media.videoEnabled); screenButton.setEnabled(media.videoEnabled);
        cameraButton.addActionListener(e -> {
            if (webcam == null || !webcam.isOpen()) { showStatus("Không có camera khả dụng. Có thể chọn chia sẻ màn hình."); return; }
            screenEnabled.set(false); screenButton.setText("Chia sẻ màn hình");
            boolean active = !cameraEnabled.get(); cameraEnabled.set(active);
            cameraButton.setText(active ? "Tắt camera" : "Bật camera");
            if (!active) { localFrame = null; canvas.repaint(); }
        });
        screenButton.addActionListener(e -> {
            boolean active = !screenEnabled.get(); screenEnabled.set(active);
            screenButton.setText(active ? "Dừng chia sẻ màn hình" : "Chia sẻ màn hình");
            showStatus(active ? "Đang chia sẻ màn hình" : "Đã dừng chia sẻ màn hình");
            if (!active) { localFrame = null; canvas.repaint(); }
        });
        micButton.addActionListener(e -> {
            boolean muted = !audio.isMuted(); audio.setMuted(muted);
            micButton.setText(muted ? "Bật mic" : "Tắt mic");
            if (muted) micLevel.setValue(0);
        });
        var hangup = new JButton("Kết thúc"); hangup.addActionListener(e -> dispose());
        controls.add(cameraButton); controls.add(screenButton); controls.add(micButton); controls.add(hangup);
        var bottom = new JPanel(new BorderLayout()); bottom.add(controls, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12)); bottom.add(status, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        timer = new Timer(1000, e -> fps.setText(frameCount.getAndSet(0) + " FPS | UDP")); timer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { closeResources(); onClosed.run(); }
        });
        udp.onError(this::showStatus); audio.onError(this::showStatus);
        Thread.ofVirtual().start(this::startMedia);
    }
    private void showStatus(String text) { SwingUtilities.invokeLater(() -> { if (!closed.get()) status.setText(text); }); }
    private void startMedia() {
        try {
            InetAddress ip = InetAddress.getByName(remote.ip());
            if (closed.get()) return;
            udp.startVideoReceiver(media.video, frame -> { remoteFrame = frame; frameCount.incrementAndGet(); canvas.repaint(); });
            if (media.videoEnabled) udp.startVideoStream(ip, remote.videoPort(), 15, this::captureFrame);
            try {
                audio.startVoiceCall(ip, remote.audioPort(), media.audio, level -> SwingUtilities.invokeLater(() -> {
                    if (!closed.get()) micLevel.setValue(level);
                }));
                showStatus("Đã kết nối âm thanh hai chiều");
            } catch (Exception ex) { showStatus(ex.getMessage()); }
            if (media.videoEnabled && !closed.get()) openCamera();
        } catch (Exception ex) { showStatus("Không mở được luồng media: " + ex.getMessage()); }
    }
    private void openCamera() {
        Webcam candidate = null;
        try {
            candidate = Webcam.getDefault();
            if (candidate == null) { showStatus("Không tìm thấy webcam. Có thể bật chia sẻ màn hình."); return; }
            candidate.setViewSize(WebcamResolution.QVGA.getSize()); candidate.open();
            if (closed.get()) { candidate.close(); return; }
            webcam = candidate;
            if (closed.get()) { candidate.close(); webcam = null; }
        } catch (Exception | LinkageError ex) {
            if (candidate != null && candidate.isOpen()) candidate.close();
            showStatus("Không mở được webcam: " + ex.getMessage() + ". Có thể chọn chia sẻ màn hình.");
        }
    }
    private BufferedImage captureFrame() {
        if (closed.get()) return null;
        BufferedImage frame = null;
        try {
            if (screenEnabled.get()) {
                if (robot == null) robot = new Robot();
                BufferedImage capture = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
                frame = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
                var g = frame.createGraphics(); drawFit(g, capture, 0, 0, 320, 240); g.dispose();
            } else if (cameraEnabled.get() && webcam != null && webcam.isOpen()) frame = webcam.getImage();
        } catch (Exception ex) { screenEnabled.set(false); showStatus("Không lấy được hình ảnh: " + ex.getMessage()); }
        localFrame = frame; canvas.repaint(); return frame;
    }
    private static void drawFit(Graphics2D g, BufferedImage frame, int x, int y, int w, int h) {
        double scale = Math.min((double)w / frame.getWidth(), (double)h / frame.getHeight());
        int dw = (int)(frame.getWidth() * scale), dh = (int)(frame.getHeight() * scale);
        g.drawImage(frame, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
    }
    private void closeResources() {
        if (!closed.compareAndSet(false, true)) return;
        timer.stop(); media.close(); udp.close();
        Thread.ofVirtual().start(() -> {
            audio.close(); Webcam camera = webcam;
            if (camera != null && camera.isOpen()) camera.close();
        });
    }
}
