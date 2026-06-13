package cncode.memory;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.StreamHandler;

import java.util.List;

public class MemoryExtractorTest {
    public static void main(String[] args) throws Exception {
        extractsWithToolsDisabled();
        normalizesEmptyExtraction();
    }

    private static void extractsWithToolsDisabled() throws Exception {
        CapturingProvider provider = new CapturingProvider("- 用户偏好：中文简洁回答");
        String result = MemoryExtractor.extract(provider, "fake", List.of(
                new ChatMessage(ChatRole.USER, "记住我喜欢中文简洁回答"),
                new ChatMessage(ChatRole.ASSISTANT, "好的")
        ));
        if (!result.contains("中文简洁") || provider.lastRequest.allowTools() || !"[]".equals(provider.lastRequest.toolsJson())) {
            throw new AssertionError("memory extraction should disable tools and return text");
        }
    }

    private static void normalizesEmptyExtraction() throws Exception {
        String result = MemoryExtractor.extract(new CapturingProvider("无"), "fake", List.of(
                new ChatMessage(ChatRole.USER, "hello"),
                new ChatMessage(ChatRole.ASSISTANT, "hi")
        ));
        if (!result.isBlank()) {
            throw new AssertionError("empty extraction should normalize to blank");
        }
    }

    private static class CapturingProvider implements ChatProvider {
        private final String text;
        private ChatRequest lastRequest;

        CapturingProvider(String text) {
            this.text = text;
        }

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            lastRequest = request;
            handler.onDelta(text);
            handler.onComplete();
        }
    }
}
