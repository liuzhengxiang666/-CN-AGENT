package cncode.provider;

import cncode.chat.ChatMessage;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, String toolsJson, boolean allowTools) {
    public ChatRequest(String model, List<ChatMessage> messages) {
        this(model, messages, "[]", false);
    }

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        messages = List.copyOf(messages);
        toolsJson = toolsJson == null || toolsJson.isBlank() ? "[]" : toolsJson;
    }
}
