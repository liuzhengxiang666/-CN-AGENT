package cncode.mcp;

import java.util.List;

public record McpConnectResult(int servers, int tools, List<String> errors) {
}
