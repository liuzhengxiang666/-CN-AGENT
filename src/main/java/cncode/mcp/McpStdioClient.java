package cncode.mcp;

import cncode.config.McpServerConfig;
import cncode.provider.ProviderException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class McpStdioClient implements McpClient {
    private final McpServerConfig config;
    private final AtomicLong ids = new AtomicLong(1);
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private boolean alive;

    public McpStdioClient(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = new HashMap<>();
        String path = System.getenv("PATH");
        if (path != null) {
            env.put("PATH", path);
        }
        env.putAll(resolveEnv(config.env()));
        builder.environment().clear();
        builder.environment().putAll(env);
        process = builder.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception ignored) {
            }
        });
        long id = ids.getAndIncrement();
        send(McpJson.request(id, "initialize", McpJson.initializeParams()));
        readResponse(id);
        send(McpJson.notification("notifications/initialized", "{}"));
        alive = true;
    }

    @Override
    public List<McpToolDef> listTools() throws Exception {
        long id = ids.getAndIncrement();
        send(McpJson.request(id, "tools/list", "{}"));
        return McpJson.parseTools(readResponse(id));
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        long id = ids.getAndIncrement();
        send(McpJson.request(id, "tools/call", McpJson.callToolParams(toolName, arguments)));
        return McpJson.parseCallResult(readResponse(id));
    }

    @Override
    public boolean isAlive() {
        return alive && process != null && process.isAlive();
    }

    @Override
    public void close() {
        alive = false;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private void send(String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private String readResponse(long id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(config.timeoutSeconds()).toNanos();
        String idNeedle = "\"id\":" + id;
        while (System.nanoTime() < deadline) {
            if (!reader.ready()) {
                Thread.sleep(10);
                continue;
            }
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.contains(idNeedle) || line.contains("\"id\":\"" + id + "\"")) {
                return line;
            }
        }
        throw new ProviderException("等待 MCP stdio 响应超时：" + config.name());
    }

    static Map<String, String> resolveEnv(Map<String, String> values) {
        Map<String, String> result = new HashMap<>();
        values.forEach((key, value) -> result.put(key, resolveEnvValue(value)));
        return result;
    }

    static String resolveEnvValue(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        int start;
        while ((start = result.indexOf("${")) >= 0) {
            int end = result.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String name = result.substring(start + 2, end);
            result = result.substring(0, start) + System.getenv().getOrDefault(name, "${" + name + "}") + result.substring(end + 1);
        }
        return result;
    }
}
