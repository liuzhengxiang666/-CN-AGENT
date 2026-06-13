package cncode.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConfigLoader {
    public AppConfig loadDefault() throws ConfigException {
        Path path = Path.of(System.getProperty("user.home"), ".cncode", "config.yaml");
        return load(path);
    }

    public AppConfig load(Path path) throws ConfigException {
        if (!Files.exists(path)) {
            throw new ConfigException("找不到配置文件：" + path + "。请创建 ~/.cncode/config.yaml。");
        }

        Map<String, String> values = parseSimpleYaml(path);
        String protocol = required(values, "protocol");
        String model = required(values, "model");
        String baseUrl = required(values, "base_url");
        String apiKey = required(values, "api_key");

        try {
            return new AppConfig(protocol, model, baseUrl, apiKey, parseMcpServers(values));
        } catch (IllegalArgumentException error) {
            throw new ConfigException(error.getMessage(), error);
        }
    }

    private List<McpServerConfig> parseMcpServers(Map<String, String> values) {
        Map<String, Map<String, String>> grouped = new HashMap<>();
        String prefix = "mcp.server.";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot >= rest.length() - 1) {
                continue;
            }
            String name = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            grouped.computeIfAbsent(name, ignored -> new HashMap<>()).put(field, entry.getValue());
        }
        List<McpServerConfig> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, String> fields = entry.getValue();
            Map<String, String> env = subMap(fields, "env.");
            Map<String, String> headers = subMap(fields, "headers.");
            servers.add(new McpServerConfig(
                    entry.getKey(),
                    fields.getOrDefault("command", ""),
                    splitCsv(fields.getOrDefault("args", "")),
                    env,
                    fields.getOrDefault("url", ""),
                    headers,
                    parseInt(fields.getOrDefault("timeout_seconds", "30"), 30)
            ));
        }
        return servers;
    }

    private Map<String, String> subMap(Map<String, String> values, String prefix) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<String, String> parseSimpleYaml(Path path) throws ConfigException {
        Map<String, String> values = new HashMap<>();
        try {
            int lineNumber = 0;
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                String line = stripComment(rawLine).trim();
                if (line.isEmpty()) {
                    continue;
                }
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    throw new ConfigException("配置格式错误，第 " + lineNumber + " 行缺少冒号。");
                }
                String key = stripBom(line.substring(0, separator).trim());
                String value = unquote(line.substring(separator + 1).trim());
                values.put(key, value);
            }
            return values;
        } catch (IOException error) {
            throw new ConfigException("读取配置文件失败：" + path, error);
        }
    }

    // 只解析本项目需要的四个顶层标量字段，避免为 MVP 引入额外依赖。
    private String stripComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private String required(Map<String, String> values, String field) throws ConfigException {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new ConfigException("配置字段缺失或为空：" + field);
        }
        return value.trim();
    }
}
