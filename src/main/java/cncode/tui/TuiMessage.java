package cncode.tui;

public class TuiMessage {
    private final TuiMessageRole role;
    private final StringBuilder content;

    public TuiMessage(TuiMessageRole role, String content) {
        if (role == null) {
            throw new IllegalArgumentException("TUI 消息角色不能为空");
        }
        this.role = role;
        this.content = new StringBuilder(content == null ? "" : content);
    }

    public TuiMessageRole role() {
        return role;
    }

    public synchronized String content() {
        return content.toString();
    }

    public synchronized void append(String text) {
        if (text != null) {
            content.append(text);
        }
    }
}
