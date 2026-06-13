package cncode.agent;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.StreamHandler;
import cncode.tool.Tool;
import cncode.tool.ToolCall;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentLoopTest {
    public static void main(String[] args) {
        directTextFinishesInOneIteration();
        twoToolCallsAcrossIterationsFinish();
        maxIterationsStopsLoop();
        unknownToolResultIsFedBack();
        lengthStopReasonReturnsClearError();
        planOnlyPromptRequiresInteractiveClarification();
        doModeInjectsDoReminder();
        cancellationStopsBeforeProviderCall();
    }

    private static void directTextFinishesInOneIteration() {
        ChatSession session = new ChatSession();
        List<AgentEvent.Type> events = new ArrayList<>();
        AgentLoop agent = newAgent(new ScriptedProvider(List.of(Step.text("hello"))), session, 10);

        agent.run("hi", event -> events.add(event.type()));

        if (!events.contains(AgentEvent.Type.LOOP_COMPLETE) || !events.contains(AgentEvent.Type.DONE)) {
            throw new AssertionError("direct text did not complete: " + events);
        }
        if (session.messages().size() != 2 || !"hello".equals(session.messages().get(1).content())) {
            throw new AssertionError("direct text history invalid: " + session.messages());
        }
    }

    private static void twoToolCallsAcrossIterationsFinish() {
        ChatSession session = new ChatSession();
        List<AgentEvent.Type> events = new ArrayList<>();
        AgentLoop agent = newAgent(new ScriptedProvider(List.of(
                Step.tool("first"),
                Step.tool("second"),
                Step.text("final")
        )), session, 10);

        agent.run("do tools", event -> events.add(event.type()));

        long toolResults = events.stream().filter(type -> type == AgentEvent.Type.TOOL_RESULT).count();
        if (toolResults != 2 || !events.contains(AgentEvent.Type.LOOP_COMPLETE)) {
            throw new AssertionError("multi iteration events invalid: " + events);
        }
        String history = session.messages().toString();
        if (!history.contains("<tool-call") || !history.contains("<tool-result") || !history.contains("final")) {
            throw new AssertionError("tool history invalid: " + history);
        }
    }

    private static void maxIterationsStopsLoop() {
        ChatSession session = new ChatSession();
        List<AgentEvent.Type> events = new ArrayList<>();
        AgentLoop agent = newAgent(new ScriptedProvider(List.of(
                Step.tool("a"),
                Step.tool("b"),
                Step.tool("c")
        )), session, 2);

        agent.run("loop forever", event -> events.add(event.type()));

        if (!events.contains(AgentEvent.Type.ERROR) || agent.state() != AgentRunState.FAILED) {
            throw new AssertionError("max iteration should fail: " + events + ", state=" + agent.state());
        }
    }

    private static void unknownToolResultIsFedBack() {
        ChatSession session = new ChatSession();
        AgentLoop agent = newAgent(new ScriptedProvider(List.of(
                Step.unknownTool(),
                Step.text("handled unknown")
        )), session, 10);

        agent.run("unknown", event -> {
        });

        String history = session.messages().toString();
        if (!history.contains("missing_tool") || !history.contains("handled unknown")) {
            throw new AssertionError("unknown tool history invalid: " + history);
        }
    }

    private static void lengthStopReasonReturnsClearError() {
        ChatSession session = new ChatSession();
        List<AgentEvent.Type> events = new ArrayList<>();
        AgentLoop agent = newAgent(new LengthStopProvider(), session, 10);

        agent.run("long answer", event -> events.add(event.type()));

        if (!events.contains(AgentEvent.Type.ERROR) || agent.state() != AgentRunState.FAILED) {
            throw new AssertionError("length stop should fail: " + events + ", state=" + agent.state());
        }
    }

    private static void planOnlyPromptRequiresInteractiveClarification() {
        ChatSession session = new ChatSession();
        CapturingProvider provider = new CapturingProvider();
        AgentLoop agent = new AgentLoop(
                provider,
                new AppConfig("openai", "fake", "https://example.test/v1", "key"),
                session,
                new ToolRegistry(),
                new AgentLoopConfig(10, Duration.ofSeconds(60), Duration.ofSeconds(3), 1000, true)
        );

        agent.run("plan a feature", event -> {
        });

        String prompt = provider.lastRequest.messages().toString();
        if (!prompt.contains("cncode-options")) {
            throw new AssertionError("plan-only prompt missing interactive options: " + prompt);
        }
    }

    private static void doModeInjectsDoReminder() {
        ChatSession session = new ChatSession();
        CapturingProvider provider = new CapturingProvider();
        AgentLoop agent = new AgentLoop(
                provider,
                new AppConfig("openai", "fake", "https://example.test/v1", "key"),
                session,
                new ToolRegistry(),
                new AgentLoopConfig(10, Duration.ofSeconds(60), Duration.ofSeconds(3), 1000, false)
        );

        agent.run("normal chat", event -> {
        });

        String prompt = provider.lastRequest.messages().toString();
        if (!prompt.contains("Do 执行模式") || !prompt.contains("不要声称当前处于 Plan 模式")) {
            throw new AssertionError("do mode reminder missing: " + prompt);
        }
        if (prompt.contains("cncode-options")) {
            throw new AssertionError("do mode should not include plan-only options reminder: " + prompt);
        }
    }

    private static void cancellationStopsBeforeProviderCall() {
        ChatSession session = new ChatSession();
        CountingProvider provider = new CountingProvider();
        AgentLoop agent = newAgent(provider, session, 10);
        AgentCancellationToken token = new AgentCancellationToken();
        token.cancel();
        List<AgentEvent.Type> events = new ArrayList<>();

        agent.run("cancel now", event -> events.add(event.type()), token);

        if (provider.calls != 0) {
            throw new AssertionError("provider should not be called after cancellation");
        }
        if (!events.contains(AgentEvent.Type.CANCELLED) || !events.contains(AgentEvent.Type.DONE)) {
            throw new AssertionError("cancel events missing: " + events);
        }
        if (agent.state() != AgentRunState.CANCELLED) {
            throw new AssertionError("state should be cancelled: " + agent.state());
        }
    }

    private static AgentLoop newAgent(ChatProvider provider, ChatSession session, int maxIterations) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        return new AgentLoop(
                provider,
                new AppConfig("openai", "fake", "https://example.test/v1", "key"),
                session,
                registry,
                new AgentLoopConfig(maxIterations, Duration.ofSeconds(3), 1000)
        );
    }

    private record Step(String text, ToolCall toolCall) {
        static Step text(String text) {
            return new Step(text, null);
        }

        static Step tool(String value) {
            return new Step("", new ToolCall("call_" + value, "echo_tool", "{\"value\":\"" + value + "\"}"));
        }

        static Step unknownTool() {
            return new Step("", new ToolCall("call_missing", "missing_tool", "{}"));
        }
    }

    private static class ScriptedProvider implements ChatProvider {
        private final List<Step> steps;
        private int index;

        ScriptedProvider(List<Step> steps) {
            this.steps = steps;
        }

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            Step step = index < steps.size() ? steps.get(index++) : Step.text("fallback final");
            if (!step.text().isEmpty()) {
                handler.onDelta(step.text());
            }
            if (step.toolCall() != null) {
                handler.onToolCall(step.toolCall());
            }
            handler.onComplete();
        }
    }

    private static class CapturingProvider implements ChatProvider {
        private ChatRequest lastRequest;

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            lastRequest = request;
            handler.onDelta("ok");
            handler.onComplete();
        }
    }

    private static class LengthStopProvider implements ChatProvider {
        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            handler.onDelta("too long");
            handler.onComplete("length");
        }
    }

    private static class CountingProvider implements ChatProvider {
        private int calls;

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            calls++;
            handler.onDelta("should not happen");
            handler.onComplete();
        }
    }

    private static class EchoTool implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "echo_tool",
                    "Test tool.",
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}"
            );
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.READ;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
            return ToolResult.success(metadata().name(), "echo ok", String.valueOf(arguments.get("value")));
        }
    }
}
