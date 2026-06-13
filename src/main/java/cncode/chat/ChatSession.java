package cncode.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatSession {
    private final List<ChatMessage> messages = new ArrayList<>();

    public void addUserMessage(String content) {
        messages.add(new ChatMessage(ChatRole.USER, content));
    }

    public void addAssistantMessage(String content) {
        messages.add(new ChatMessage(ChatRole.ASSISTANT, content));
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public void replaceMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages == null ? List.of() : newMessages);
    }
}
