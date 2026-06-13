package cncode.config;

import java.util.List;

public record AppConfig(String protocol, String model, String baseUrl, String apiKey, List<McpServerConfig> mcpServers) {
    public AppConfig(String protocol, String model, String baseUrl, String apiKey) {
        this(protocol, model, baseUrl, apiKey, List.of());
    }

    public AppConfig {
        protocol = requireNotBlank(protocol, "protocol");
        model = requireNotBlank(model, "model");
        baseUrl = requireNotBlank(baseUrl, "base_url");
        apiKey = requireNotBlank(apiKey, "api_key");
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("配置字段缺失或为空：" + field);
        }
        return value.trim();
    }
}
