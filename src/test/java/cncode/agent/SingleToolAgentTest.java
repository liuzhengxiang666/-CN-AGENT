package cncode.agent;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.StreamHandler;
import cncode.tool.ToolCall;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class SingleToolAgentTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cncode-agent");
        Files.writeString(root.resolve("AGENTS.md"), "hello agent");

        ChatProvider provider = new FakeToolProvider();
        ChatSession session = new ChatSession();
        SingleToolAgent agent = new SingleToolAgent(
                provider,
                new AppConfig("openai", "fake", "https://example.test/v1", "key"),
                session,
                ToolRegistry.defaults(),
                new ToolExecutionContext(root, Duration.ofSeconds(3), 1000)
        );

        List<AgentEvent.Type> events = new ArrayList<>();
        agent.run("read AGENTS.md", event -> events.add(event.type()));

        if (!events.contains(AgentEvent.Type.TOOL_START) || !events.contains(AgentEvent.Type.TOOL_RESULT) || !events.contains(AgentEvent.Type.DELTA)) {
            throw new AssertionError("agent events missing: " + events);
        }
        if (session.messages().size() < 4) {
            throw new AssertionError("session history not updated");
        }
    }

    private static class FakeToolProvider implements ChatProvider {
        private int calls = 0;

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            calls++;
            if (calls == 1) {
                handler.onToolCall(new ToolCall("call_1", "read_file", "{\"path\":\"AGENTS.md\"}"));
                handler.onComplete();
            } else {
                handler.onDelta("final answer");
                handler.onComplete();
            }
        }
    }
}
