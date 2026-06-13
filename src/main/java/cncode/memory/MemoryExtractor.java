package cncode.memory;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;

import java.util.List;

public final class MemoryExtractor {
    public static final String EXTRACTION_PROMPT = """
            你正在为 CN Code 抽取长期记忆。禁止调用任何工具，禁止执行命令，禁止读写文件。
            只记录未来会话仍有帮助的信息，包括用户偏好、项目约定、重要决策、长期任务线索、用户明确要求记住的内容。
            不要记录临时命令输出、短期调试过程、一次性的错误细节。
            如果没有值得记住的信息，只输出空内容。
            输出 Markdown bullet 列表，不要输出额外解释。
            """;

    private MemoryExtractor() {
    }

    public static String extract(ChatProvider provider, String model, List<ChatMessage> messages) throws ProviderException {
        if (messages == null || messages.size() < 2) {
            return "";
        }
        StringBuilder history = new StringBuilder();
        for (ChatMessage message : messages) {
            history.append("[").append(message.role()).append("]\n").append(message.content()).append("\n\n");
        }
        StringBuilder output = new StringBuilder();
        provider.streamChat(new ChatRequest(model, List.of(
                new ChatMessage(ChatRole.SYSTEM, EXTRACTION_PROMPT),
                new ChatMessage(ChatRole.USER, history.toString())
        ), "[]", false), new StreamHandler() {
            @Override
            public void onDelta(String text) {
                output.append(text);
            }
        });
        return normalize(output.toString());
    }

    private static String normalize(String text) {
        String safe = text == null ? "" : text.strip();
        if (safe.equalsIgnoreCase("none") || safe.equals("无") || safe.equals("没有")) {
            return "";
        }
        return safe;
    }
}
