package cncode.session;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.provider.JsonUtil;

import java.time.Instant;

public record SessionRecord(int index, ChatRole role, String content, String timestamp, String previousHash, String hash) {
    public static SessionRecord create(int index, ChatMessage message, String previousHash, String hash) {
        return new SessionRecord(index, message.role(), message.content(), Instant.now().toString(), previousHash, hash);
    }

    public ChatMessage toMessage() {
        return new ChatMessage(role, content);
    }

    public String toJson() {
        return "{"
                + "\"index\":" + index + ","
                + "\"role\":" + JsonUtil.quote(role.name()) + ","
                + "\"content\":" + JsonUtil.quote(content) + ","
                + "\"timestamp\":" + JsonUtil.quote(timestamp) + ","
                + "\"previousHash\":" + JsonUtil.quote(previousHash) + ","
                + "\"hash\":" + JsonUtil.quote(hash)
                + "}";
    }
}
