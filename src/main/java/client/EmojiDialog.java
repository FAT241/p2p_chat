package client;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Hộp thoại chọn Icon biểu cảm (Emoji Picker) phong cách hiện đại.
 */
public final class EmojiDialog extends JDialog {
    private static final String[][] EMOJI_GROUPS = {
        {"Cảm xúc", "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋", "😜", "🤪", "😎", "🥳", "😴", "😭", "😱", "😡", "🤔", "🤫", "🤗", "🤖"},
        {"Cử chỉ & Trái tim", "👍", "👎", "👏", "🙌", "🤝", "✌️", "🤞", "🤙", "👌", "💪", "🙏", "💖", "❤️", "💔", "💕", "🔥", "⭐", "✨", "💯", "🎉", "🚀", "💡", "⚠️", "❗", "❓", "✅", "❌"},
        {"Mạng & Đa phương tiện", "💻", "📱", "🖥️", "🌐", "⚡", "📡", "🔒", "🔑", "📎", "📁", "🎵", "📹", "🎙️", "📢", "💬", "🟢", "🔴", "🟡", "🕒", "📦", "☕", "🍕", "🎮", "📚", "🏆"}
    };

    public EmojiDialog(Window owner, Consumer<String> onSelect) {
        super(owner, "Biểu tượng cảm xúc (Emoji)", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(440, 320);
        setMinimumSize(new Dimension(390, 280));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        var tabs = new JTabbedPane();

        for (var group : EMOJI_GROUPS) {
            String title = group[0];
            var grid = new JPanel(new GridLayout(0, 7, 4, 4));
            grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            for (int i = 1; i < group.length; i++) {
                String emoji = group[i];
                var btn = new JButton(emoji);
                btn.setFont(EmojiSupport.font(emoji, 23));
                btn.setPreferredSize(new Dimension(46, 44));
                btn.setToolTipText(EmojiSupport.description(emoji));
                btn.getAccessibleContext().setAccessibleName(EmojiSupport.description(emoji));
                btn.setMargin(new Insets(2, 2, 2, 2));
                btn.setFocusPainted(true);
                btn.addActionListener(event -> {
                    onSelect.accept(emoji);
                    dispose();
                });
                grid.add(btn);
            }
            tabs.addTab(title, new JScrollPane(grid));
        }

        add(tabs, BorderLayout.CENTER);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
}
