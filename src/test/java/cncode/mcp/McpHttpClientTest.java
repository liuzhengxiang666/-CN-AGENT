package cncode.mcp;

import cncode.config.McpServerConfig;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class McpHttpClientTest {
    public static void main(String[] args) throws Exception {
        httpClientListsAndCallsTools();
    }

    private static void httpClientListsAndCallsTools() throws Exception {
        AtomicInteger initializeCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            String contentType = "application/json";
            if (body.contains("\"method\":\"initialize\"")) {
                initializeCount.incrementAndGet();
                response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"instructions\":\"ok\"}}";
                exchange.getResponseHeaders().set("Mcp-Session-Id", "s1");
            } else if (body.contains("notifications/initialized")) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}";
            } else if (body.contains("tools/list")) {
                response = "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"hello\",\"description\":\"Hello\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}\n\n";
                contentType = "text/event-stream";
            } else {
                response = "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"called\"}],\"isError\":false}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            McpHttpClient client = new McpHttpClient(new McpServerConfig("docs", "", List.of(), Map.of(), "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Map.of(), 5));
            client.connect();
            List<McpToolDef> tools = client.listTools();
            if (tools.size() != 1 || !"hello".equals(tools.get(0).name())) {
                throw new AssertionError("tools invalid: " + tools);
            }
            McpCallResult result = client.callTool("hello", Map.of());
            if (result.error() || !"called".equals(result.text())) {
                throw new AssertionError("call invalid: " + result);
            }
            if (initializeCount.get() != 1) {
                throw new AssertionError("connection should be reused");
            }
        } finally {
            server.stop(0);
        }
    }
}
