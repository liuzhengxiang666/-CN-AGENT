package cncode.tool;

import cncode.provider.ProviderException;

import java.util.Map;

public class ToolExecutor {
    private final ToolRegistry registry;
    private final ToolExecutionContext context;

    public ToolExecutor(ToolRegistry registry, ToolExecutionContext context) {
        this.registry = registry;
        this.context = context;
    }

    public ToolResult execute(ToolCall call) {
        return registry.find(call.name())
                .map(tool -> executeTool(tool, call))
                .orElseGet(() -> ToolResult.failure(call.name(), "工具不存在", "未知工具：" + call.name()));
    }

    private ToolResult executeTool(Tool tool, ToolCall call) {
        try {
            Map<String, Object> arguments = ToolJson.parseObject(call.argumentsJson());
            return tool.execute(context, arguments);
        } catch (ProviderException error) {
            return ToolResult.failure(tool.metadata().name(), "工具参数解析失败", error.getMessage());
        } catch (Exception error) {
            return ToolResult.failure(tool.metadata().name(), "工具执行异常", error.getMessage());
        }
    }
}
