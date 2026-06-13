package cncode.session;

import cncode.chat.ChatMessage;

import java.util.List;

public record SessionRestoreResult(boolean success, List<ChatMessage> messages, String message) {
    public SessionRestoreResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        message = message == null ? "" : message;
    }

    public static SessionRestoreResult success(List<ChatMessage> messages) {
        return new SessionRestoreResult(true, messages, "会话恢复成功");
    }

    public static SessionRestoreResult failure(String message) {
        return new SessionRestoreResult(false, List.of(), message);
    }
}
