package cncode.repl;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.StreamHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class ChatReplTest {
    public static void main(String[] args) {
        ChatProvider fakeProvider = new ChatProvider() {
            @Override
            public void streamChat(ChatRequest request, StreamHandler handler) {
                handler.onDelta("你好");
                handler.onDelta("，CN Code");
                handler.onComplete();
            }
        };

        ByteArrayInputStream input = new ByteArrayInputStream("你好\nexit\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
        ChatSession session = new ChatSession();
        AppConfig config = new AppConfig("openai", "test-model", "https://example.test/v1", "key");

        new ChatRepl(fakeProvider, config, session, input, output).run();

        String text = outputBytes.toString(StandardCharsets.UTF_8);
        if (!text.contains("CN Code 已启动")) {
            throw new AssertionError("未显示欢迎语");
        }
        if (!text.contains("你好，CN Code")) {
            throw new AssertionError("未按流式片段输出回复");
        }
        if (!text.contains("再见。")) {
            throw new AssertionError("未正常退出");
        }
        if (session.messages().size() != 2) {
            throw new AssertionError("会话历史未保存用户和助手消息");
        }
    }
}
