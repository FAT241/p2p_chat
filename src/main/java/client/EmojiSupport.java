package client;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.*;
import java.util.regex.Pattern;

/** Apply font fallback to whole grapheme clusters (including emoji variation/ZWJ sequences). */
public final class EmojiSupport {
    private static final Pattern CLUSTER = Pattern.compile("\\X");
    private static final java.util.List<Font> FONTS = new ArrayList<>();
    private static final Map<String, String> CACHE = new HashMap<>();
    static {
        var installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : new String[]{"Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji", "Apple Color Emoji", Font.DIALOG})
            if (installed.contains(name) || Font.DIALOG.equals(name)) FONTS.add(new Font(name, Font.PLAIN, 18));
    }
    private EmojiSupport() {}
    public static synchronized String family(String cluster) {
        return CACHE.computeIfAbsent(cluster, value -> {
            if (value.codePoints().allMatch(c -> c < 0x2300)) return Font.DIALOG;
            for (Font font : FONTS) {
                if (value.codePoints().filter(c -> c != 0xFE0F && c != 0xFE0E && c != 0x200D)
                    .allMatch(font::canDisplay)) return font.getFamily();
            }
            return Font.DIALOG;
        });
    }
    public static Font font(String emoji, int size) { return new Font(family(emoji), Font.PLAIN, size); }
    public static void style(StyledDocument doc) {
        try {
            var matcher = CLUSTER.matcher(doc.getText(0, doc.getLength()));
            while (matcher.find()) {
                var attributes = new SimpleAttributeSet();
                StyleConstants.setFontFamily(attributes, family(matcher.group()));
                doc.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), attributes, false);
            }
        } catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }
    public static void install(JTextPane pane) {
        pane.setFont(new Font(Font.DIALOG, Font.PLAIN, 15));
        for (boolean backward : new boolean[]{true, false}) {
            String action = backward ? "delete-previous-cluster" : "delete-next-cluster";
            pane.getInputMap().put(KeyStroke.getKeyStroke(backward ? "BACK_SPACE" : "DELETE"), action);
            pane.getActionMap().put(action, new AbstractAction() {
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (!pane.isEditable() || !pane.isEnabled()) return;
                    int start = pane.getSelectionStart(), end = pane.getSelectionEnd();
                    if (start == end) {
                        var matcher = CLUSTER.matcher(pane.getText());
                        while (matcher.find()) {
                            boolean match = backward ? matcher.start() < start && matcher.end() >= start
                                : matcher.start() <= start && matcher.end() > start;
                            if (match) { start = matcher.start(); end = matcher.end(); break; }
                        }
                    }
                    if (end > start) { pane.select(start, end); pane.replaceSelection(""); }
                }
            });
        }
        pane.getDocument().addDocumentListener(new DocumentListener() {
            private boolean queued;
            private void update() {
                if (queued) return;
                queued = true;
                SwingUtilities.invokeLater(() -> { queued = false; style(pane.getStyledDocument()); });
            }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) {}
        });
    }
    public static void append(JTextPane pane, String text, Color color, boolean bold, int size) {
        var attr = new SimpleAttributeSet();
        StyleConstants.setForeground(attr, color); StyleConstants.setBold(attr, bold); StyleConstants.setFontSize(attr, size);
        try { pane.getStyledDocument().insertString(pane.getDocument().getLength(), text, attr); }
        catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }
    public static String description(String emoji) {
        return emoji.codePoints().filter(c -> c != 0xFE0F && c != 0x200D)
            .mapToObj(c -> Objects.toString(Character.getName(c), "Biểu cảm"))
            .reduce((a, b) -> a + " · " + b).orElse("Biểu cảm");
    }
}
