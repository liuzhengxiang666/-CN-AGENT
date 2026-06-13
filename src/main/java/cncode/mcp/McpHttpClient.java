package cncode.mcp;

import cncode.config.McpServerConfig;
import cncode.provider.ProviderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class McpHttpClient implements McpClient {
    private final McpServerConfig config;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AtomicLong ids = new AtomicLong(1);
    private String sessionId = "";
    private boolean alive;

    public McpHttpClient(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        request("initialize", McpJson.initializeParams());
        request("notifications/initialized", "{}");
        alive = true;
    }

    @Override
    public List<McpToolDef> listTools() throws Exception {
        return McpJson.parseTools(request("tools/list", "{}"));
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        return McpJson.parseCallResult(request("tools/call", McpJson.callToolParams(toolName, arguments)));
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void close() {
        alive = false;
    }

    private String request(String method, String params) throws Exception {
        long id = ids.getAndIncrement();
        String body = method.startsWith("notifications/")
                ? McpJson.notification(method, params)
                : McpJson.request(id, method, params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        config.headers().forEach((key, value) -> builder.header(key, McpStdioClient.resolveEnvValue(value)));
        if (!sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProviderException("MCP HTTP 请求失败，状态码：" + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            String parsed = McpJson.parseSse(response.body(), id);
            if (parsed.isBlank()) {
                throw new ProviderException("未在 SSE 中找到 MCP 响应。");
            }
            return parsed;
        }
        return response.body();
    }
}
