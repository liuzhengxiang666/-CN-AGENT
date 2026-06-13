package cncode.mcp;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolResult;

import java.util.Map;

public class McpToolWrapper implements Tool {
    private final String serverName;
    private final McpToolDef def;
    private final McpClient client;
    private final ToolMetadata metadata;

    public McpToolWrapper(String serverName, McpToolDef def, McpClient client) {
        this.serverName = sanitize(serverName);
        this.def = def;
        this.client = client;
        this.metadata = new ToolMetadata(
                "mcp__" + this.serverName + "__" + sanitize(def.name()),
                "[MCP:" + serverName + "] " + (def.description() == null || def.description().isBlank() ? def.name() : def.description()),
                def.inputSchema() == null || def.inputSchema().isBlank() ? "{\"type\":\"object\",\"properties\":{}}" : def.inputSchema()
        );
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            McpCallResult result = client.callTool(def.name(), arguments);
            if (result.error()) {
                return ToolResult.failure(metadata.name(), "MCP 工具返回错误", result.text());
            }
            return ToolResult.success(metadata.name(), "MCP 工具调用完成", result.text());
        } catch (Exception error) {
            return ToolResult.failure(metadata.name(), "MCP 工具调用失败", error.getMessage());
        }
    }

    public static String sanitize(String value) {
        return (value == null ? "" : value).replaceAll("[^A-Za-z0-9_]", "_");
    }
}
