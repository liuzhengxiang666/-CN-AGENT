package cncode.agent;

import cncode.chat.ChatMessage;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.StreamHandler;
import cncode.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

public class StreamCollectorTest {
    public static void main(String[] args) throws Exception {
        collectsTextThinkingToolCallsAndStopReason();
    }

    private static void collectsTextThinkingToolCallsAndStopReason() throws Exception {
        StreamCollector collector = new StreamCollector(new FakeProvider());
        List<AgentEvent.Type> events = new ArrayList<>();

        CollectedStream stream = collector.collect(
                new ChatRequest("fake", List.<ChatMessage>of(), "[]", true),
                event -> events.add(event.type())
        );

        if (!"你好".equals(stream.text())) {
            throw new AssertionError("text invalid: " + stream.text());
        }
        if (stream.toolCalls().size() != 1 || !"read_file".equals(stream.toolCalls().getFirst().name())) {
            throw new AssertionError("tool calls invalid: " + stream.toolCalls());
        }
        if (!"tool_calls".equals(stream.stopReason())) {
            throw new AssertionError("stop reason invalid: " + stream.stopReason());
        }
        if (!events.contains(AgentEvent.Type.DELTA) || !events.contains(AgentEvent.Type.THINKING_DELTA)) {
            throw new AssertionError("stream events missing: " + events);
        }
    }

    private static class FakeProvider implements ChatProvider {
        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            handler.onThinkingDelta("思考中");
            handler.onDelta("你好");
            handler.onToolCall(new ToolCall("call_1", "read_file", "{\"path\":\"AGENTS.md\"}"));
            handler.onComplete("tool_calls");
        }
    }
}
