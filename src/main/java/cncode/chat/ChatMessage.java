package cncode.chat;

public record ChatMessage(ChatRole role, String content) {
    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }
}
