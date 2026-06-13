package cncode.web;

import cncode.config.AppConfig;
import cncode.mcp.McpConnectResult;
import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;

public final class WebJson {
    private WebJson() {
    }

    public static String extractMessage(String body) throws ProviderException {
        String message = JsonUtil.extractStringField(body, "message");
        if (message == null || message.isBlank()) {
            throw new ProviderException("message 字段缺失或为空");
        }
        return message;
    }

    public static String status(AppConfig config, int messageCount, int toolCount, boolean planOnly) {
        return status(config, messageCount, toolCount, planOnly, new McpConnectResult(0, 0, java.util.List.of()));
    }

    public static String status(AppConfig config, int messageCount, int toolCount, boolean planOnly, McpConnectResult mcp) {
        return "{"
                + "\"protocol\":" + JsonUtil.quote(config.protocol()) + ","
                + "\"model\":" + JsonUtil.quote(config.model()) + ","
                + "\"messageCount\":" + messageCount + ","
                + "\"tools\":" + JsonUtil.quote("enabled (" + toolCount + ")") + ","
                + "\"planOnly\":" + planOnly + ","
                + "\"mcpServers\":" + mcp.servers() + ","
                + "\"mcpTools\":" + mcp.tools() + ","
                + "\"mcpErrors\":" + JsonUtil.quote(String.join("; ", mcp.errors()))
                + "}";
    }

    public static String sse(String event, String jsonData) {
        return "event: " + event + "\n"
                + "data: " + jsonData + "\n\n";
    }

    public static String textData(String field, String value) {
        return "{" + JsonUtil.quote(field) + ":" + JsonUtil.quote(value) + "}";
    }
}
