package cncode.mcp;

import cncode.config.McpServerConfig;
import cncode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpManager implements AutoCloseable {
    private final List<McpServerConfig> configs;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private int registeredTools;
    private final List<String> errors = new ArrayList<>();

    public McpManager(List<McpServerConfig> configs) {
        this.configs = configs == null ? List.of() : List.copyOf(configs);
    }

    public McpConnectResult registerAllTools(ToolRegistry registry) {
        errors.clear();
        registeredTools = 0;
        for (McpServerConfig config : configs) {
            try {
                validate(config);
                McpClient client = clientFor(config);
                client.connect();
                clients.put(config.name(), client);
                for (McpToolDef tool : client.listTools()) {
                    registry.register(new McpToolWrapper(config.name(), tool, client));
                    registeredTools++;
                }
            } catch (Exception error) {
                errors.add("MCP server '" + config.name() + "': " + error.getMessage());
            }
        }
        return status();
    }

    public McpConnectResult status() {
        return new McpConnectResult(clients.size(), registeredTools, List.copyOf(errors));
    }

    private McpClient clientFor(McpServerConfig config) {
        return config.stdio() ? new McpStdioClient(config) : new McpHttpClient(config);
    }

    private void validate(McpServerConfig config) {
        if (config.name().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (config.stdio() && config.http()) {
            throw new IllegalArgumentException("cannot have both command and url");
        }
        if (!config.stdio() && !config.http()) {
            throw new IllegalArgumentException("must have either command or url");
        }
    }

    @Override
    public void close() {
        for (McpClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }
}
