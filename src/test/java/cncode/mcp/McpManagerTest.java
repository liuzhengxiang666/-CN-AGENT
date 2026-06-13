package cncode.mcp;

import cncode.config.McpServerConfig;
import cncode.tool.ToolCall;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolExecutor;
import cncode.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class McpManagerTest {
    public static void main(String[] args) throws Exception {
        registersToolsAndKeepsGoingAfterFailure();
    }

    private static void registersToolsAndKeepsGoingAfterFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (body.contains("tools/list")) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"hello-world\",\"description\":\"Hello\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}";
            } else if (body.contains("tools/call")) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"mcp ok\"}],\"isError\":false}}";
            } else {
                response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ToolRegistry registry = new ToolRegistry();
            McpManager manager = new McpManager(List.of(
                    new McpServerConfig("context-7", "", List.of(), Map.of(), "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Map.of(), 5),
                    new McpServerConfig("bad", "", List.of(), Map.of(), "", Map.of(), 5)
            ));
            McpConnectResult status = manager.registerAllTools(registry);
            if (status.servers() != 1 || status.tools() != 1 || status.errors().isEmpty()) {
                throw new AssertionError("manager status invalid: " + status);
            }
            if (registry.find("mcp__context_7__hello_world").isEmpty()) {
                throw new AssertionError("mcp tool was not registered");
            }
            String toolsJson = registry.toOpenAiToolsJson();
            if (!toolsJson.contains("mcp__context_7__hello_world")) {
                throw new AssertionError("mcp tool missing from schema: " + toolsJson);
            }
            var result = new ToolExecutor(registry, new ToolExecutionContext(java.nio.file.Path.of("").toAbsolutePath(), Duration.ofSeconds(5), 1000))
                    .execute(new ToolCall("call_1", "mcp__context_7__hello_world", "{}"));
            if (!result.success() || !result.output().contains("mcp ok")) {
                throw new AssertionError("mcp wrapper execute invalid: " + result);
            }
        } finally {
            server.stop(0);
        }
    }
}
