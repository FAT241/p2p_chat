package server;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class ServerWindow extends JFrame {
    private final JTextField host = new JTextField("0.0.0.0", 12);
    private final JTextField port = new JTextField("9000", 6);
    private final JTextArea log = new JTextArea();
    private final JButton toggle = new JButton("Bật server");
    private IndexServer server;

    public ServerWindow() {
        super("P2P Chat — Server danh bạ");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 440);
        setLocationByPlatform(true);
        var top = new JPanel();
        top.add(new JLabel("Địa chỉ lắng nghe")); top.add(host);
        host.setToolTipText("0.0.0.0: lắng nghe mọi card mạng. Client dùng IP LAN cụ thể hoặc 127.0.0.1.");
        top.add(new JLabel("Cổng")); top.add(port); top.add(toggle);
        add(top, BorderLayout.NORTH);
        log.setEditable(false);
        log.setMargin(new Insets(8, 8, 8, 8));
        add(new JScrollPane(log));
        add(new JLabel("Server chỉ quản lý người dùng, phòng và chỉ mục file."), BorderLayout.SOUTH);
        toggle.addActionListener(event -> toggle());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { if (server != null) server.close(); }
        });
    }

    private void toggle() {
        if (server != null) {
            server.close(); server = null;
            toggle.setText("Bật server"); host.setEnabled(true); port.setEnabled(true);
            append("Đã dừng server");
            return;
        }
        final int number;
        try {
            number = Integer.parseInt(port.getText().strip());
            if (number < 1 || number > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) { append("Cổng phải từ 1 đến 65535"); return; }
        String address = host.getText().strip();
        toggle.setEnabled(false);
        new SwingWorker<IndexServer, Void>() {
            protected IndexServer doInBackground() throws Exception { return new IndexServer(address, number, ServerWindow.this::append); }
            protected void done() {
                try {
                    server = get();
                    if (!isDisplayable()) { server.close(); return; }
                    toggle.setText("Dừng server"); host.setEnabled(false); port.setEnabled(false);
                } catch (Exception ex) { append("Không thể bật server: " + ex.getMessage()); }
                finally { toggle.setEnabled(true); }
            }
        }.execute();
    }
    private void append(String text) {
        SwingUtilities.invokeLater(() -> { log.append(text + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }

    public static void main(String[] args) throws Exception {
        ServerMain.main(args);
    }
}
