package cncode.agent;

import cncode.tool.Tool;
import cncode.tool.ToolCall;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StreamingToolExecutorTest {
    public static void main(String[] args) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new WriteTool());
        StreamingToolExecutor executor = new StreamingToolExecutor(
                registry,
                new ToolExecutionContext(Files.createTempDirectory("cncode-executor"), Duration.ofSeconds(3), 1000)
        );

        List<ToolBatch> batches = executor.partition(List.of(
                new ToolCall("call_1", "echo_tool", "{\"value\":\"one\"}"),
                new ToolCall("call_2", "echo_tool", "{\"value\":\"two\"}"),
                new ToolCall("call_3", "write_tool", "{}"),
                new ToolCall("call_4", "echo_tool", "{\"value\":\"three\"}")
        ));
        if (batches.size() != 3 || !batches.get(0).parallel() || batches.get(1).parallel() || batches.get(2).parallel()) {
            throw new AssertionError("batch partition invalid: " + batches);
        }

        List<AgentEvent.Type> events = new ArrayList<>();
        List<ToolResult> results = executor.executeAll(List.of(
                new ToolCall("call_1", "echo_tool", "{\"value\":\"one\"}"),
                new ToolCall("call_2", "missing_tool", "{}")
        ), event -> events.add(event.type()));

        if (results.size() != 2) {
            throw new AssertionError("result count invalid");
        }
        if (!results.get(0).success() || results.get(1).success()) {
            throw new AssertionError("result success flags invalid: " + results);
        }
        if (!events.equals(List.of(
                AgentEvent.Type.TOOL_START,
                AgentEvent.Type.TOOL_RESULT,
                AgentEvent.Type.TOOL_START,
                AgentEvent.Type.TOOL_RESULT
        ))) {
            throw new AssertionError("event order invalid: " + events);
        }

        List<ToolResult> planOnlyResults = executor.executeAll(
                List.of(new ToolCall("call_3", "write_tool", "{}")),
                event -> {
                },
                new AgentLoopConfig(10, Duration.ofSeconds(3), 1000, true),
                new AgentCancellationToken()
        );
        if (planOnlyResults.get(0).success() || !planOnlyResults.get(0).error().contains("plan-only")) {
            throw new AssertionError("plan-only did not block write tool: " + planOnlyResults);
        }
    }

    private static class EchoTool implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "echo_tool",
                    "测试工具。",
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

    private static class WriteTool implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "write_tool",
                    "测试写工具。",
                    "{\"type\":\"object\",\"properties\":{}}"
            );
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.WRITE;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
            return ToolResult.success(metadata().name(), "write ok", "written");
        }
    }
}
