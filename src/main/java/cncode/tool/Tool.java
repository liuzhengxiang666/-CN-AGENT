package cncode.tool;

import java.util.Map;

public interface Tool {
    ToolMetadata metadata();

    ToolCategory category();

    ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}
