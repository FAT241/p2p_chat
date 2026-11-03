package client;

import javax.swing.*;

/**
 * Điểm khởi chạy riêng cho Client P2P Chat.
 * Có hàm main độc lập để chạy riêng biệt với Server.
 */
public final class ClientMain {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        SwingUtilities.invokeLater(() -> {
            String initialName = args.length > 0 && !args[0].startsWith("-") ? args[0] : "";
            var window = new ClientWindow(initialName);
            if (args.length > 0 && "--smoke-test".equals(args[0])) {
                window.addNotify();
                window.dispose();
                System.exit(0);
            } else {
                window.setVisible(true);
            }
        });
    }
}
