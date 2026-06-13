package cncode.mcp;

import java.util.List;
import java.util.Map;

public class McpJsonTest {
    public static void main(String[] args) throws Exception {
        buildsRequestsAndParsesToolsAndCalls();
        parsesSseResponse();
    }

    private static void buildsRequestsAndParsesToolsAndCalls() throws Exception {
        String request = McpJson.request(1, "initialize", McpJson.initializeParams());
        if (!request.contains("\"jsonrpc\":\"2.0\"") || !request.contains("\"id\":1") || !request.contains("\"method\":\"initialize\"")) {
            throw new AssertionError("request invalid: " + request);
        }
        String notification = McpJson.notification("notifications/initialized", "{}");
        if (notification.contains("\"id\"") || !notification.contains("notifications/initialized")) {
            throw new AssertionError("notification invalid: " + notification);
        }
        String toolsJson = """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"resolve-library-id","description":"Resolve docs","inputSchema":{"type":"object","properties":{"libraryName":{"type":"string"}}}}]}}
                """;
        List<McpToolDef> tools = McpJson.parseTools(toolsJson);
        if (tools.size() != 1 || !"resolve-library-id".equals(tools.get(0).name()) || !tools.get(0).inputSchema().contains("libraryName")) {
            throw new AssertionError("tools parse invalid: " + tools);
        }
        String params = McpJson.callToolParams("x", Map.of("q", "react"));
        if (!params.contains("\"name\":\"x\"") || !params.contains("\"q\":\"react\"")) {
            throw new AssertionError("call params invalid: " + params);
        }
        McpCallResult result = McpJson.parseCallResult("""
                {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}],"isError":false}}
                """);
        if (result.error() || !"hello".equals(result.text())) {
            throw new AssertionError("call parse invalid: " + result);
        }
        McpCallResult error = McpJson.parseCallResult("""
                {"jsonrpc":"2.0","id":4,"error":{"code":-1,"message":"boom"}}
                """);
        if (!error.error() || !error.text().contains("boom")) {
            throw new AssertionError("error parse invalid: " + error);
        }
    }

    private static void parsesSseResponse() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"ok\":true}}\n\n";
        String parsed = McpJson.parseSse(sse, 9);
        if (!parsed.contains("\"id\":9")) {
            throw new AssertionError("sse parse invalid: " + parsed);
        }
    }
}
