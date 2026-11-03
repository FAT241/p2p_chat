package server;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Điểm khởi chạy riêng cho Server danh bạ P2P Chat.
 * Có hàm main độc lập để chạy riêng biệt với Client.
 */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        var options = Arrays.asList(args);
        if (options.contains("--help")) {
            System.out.println("P2P Chat Server:");
            System.out.println("  (mặc định)         Mở giao diện Server GUI (Swing)");
            System.out.println("  --cli              Chạy server dòng lệnh không cần GUI");
            System.out.println("  --host <địa chỉ>   Địa chỉ lắng nghe (mặc định 0.0.0.0)");
            System.out.println("  --port <cổng>      Cổng lắng nghe (mặc định 9000)");
            return;
        }

        if (options.contains("--cli") || options.contains("--server-cli")) {
            String host = value(options, "--host", "0.0.0.0");
            int port = Integer.parseInt(value(options, "--port", "9000"));
            var server = new IndexServer(host, port, System.out::println);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            System.out.println("P2P IndexServer đang chạy trên " + host + ":" + port + " (nhấn Ctrl+C để dừng)...");
            new CountDownLatch(1).await();
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        SwingUtilities.invokeLater(() -> {
            var window = new ServerWindow();
            if (options.contains("--smoke-test")) {
                window.addNotify();
                window.dispose();
                System.exit(0);
            } else {
                window.setVisible(true);
            }
        });
    }

    private static String value(List<String> args, String key, String fallback) {
        int index = args.indexOf(key);
        if (index < 0) return fallback;
        if (index + 1 == args.size()) throw new IllegalArgumentException("Missing value for " + key);
        return args.get(index + 1);
    }
}
