package cncode.mcp;

import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;
import cncode.tool.ToolJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class McpJson {
    private McpJson() {
    }

    public static String request(long id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":" + JsonUtil.quote(method)
                + (paramsJson == null || paramsJson.isBlank() ? "" : ",\"params\":" + paramsJson) + "}";
    }

    public static String notification(String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":" + JsonUtil.quote(method)
                + (paramsJson == null || paramsJson.isBlank() ? "" : ",\"params\":" + paramsJson) + "}";
    }

    public static String initializeParams() {
        return "{\"protocolVersion\":\"2024-11-05\",\"clientInfo\":{\"name\":\"cncode\",\"version\":\"0.1.0\"},\"capabilities\":{}}";
    }

    public static String callToolParams(String toolName, Map<String, Object> args) {
        return "{\"name\":" + JsonUtil.quote(toolName) + ",\"arguments\":" + objectJson(args) + "}";
    }

    public static void throwIfError(String json) throws ProviderException {
        int error = json.indexOf("\"error\"");
        if (error >= 0) {
            String message = JsonUtil.extractStringField(json.substring(error), "message");
            throw new ProviderException("MCP error: " + (message == null ? json : message));
        }
    }

    public static List<McpToolDef> parseTools(String json) throws ProviderException {
        throwIfError(json);
        List<McpToolDef> tools = new ArrayList<>();
        int toolsIndex = json.indexOf("\"tools\"");
        if (toolsIndex < 0) {
            return tools;
        }
        int index = json.indexOf('{', toolsIndex);
        while (index >= 0) {
            int end = findMatching(json, index, '{', '}');
            if (end < 0) {
                break;
            }
            String item = json.substring(index, end + 1);
            String name = JsonUtil.extractStringField(item, "name");
            if (name != null) {
                String description = JsonUtil.extractStringField(item, "description");
                String schema = extractObjectField(item, "inputSchema");
                tools.add(new McpToolDef(name, description == null ? "" : description, schema.isBlank() ? "{\"type\":\"object\",\"properties\":{}}" : schema));
            }
            index = json.indexOf('{', end + 1);
        }
        return tools;
    }

    public static McpCallResult parseCallResult(String json) throws ProviderException {
        try {
            throwIfError(json);
        } catch (ProviderException error) {
            return new McpCallResult(true, error.getMessage());
        }
        boolean isError = json.contains("\"isError\":true");
        StringBuilder output = new StringBuilder();
        int index = 0;
        while ((index = json.indexOf("\"type\":\"text\"", index)) >= 0) {
            int textKey = json.indexOf("\"text\"", index);
            if (textKey < 0) {
                break;
            }
            String text = JsonUtil.extractStringField(json.substring(textKey), "text");
            if (text != null) {
                output.append(text).append('\n');
            }
            index = textKey + 6;
        }
        String text = output.toString().strip();
        return new McpCallResult(isError, text.isBlank() ? "(no output)" : text);
    }

    public static String parseSse(String body, long id) {
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring(5).trim();
            if (data.equals("[DONE]") || data.isBlank()) {
                continue;
            }
            if (data.contains("\"id\":" + id) || data.contains("\"id\":\"" + id + "\"")) {
                return data;
            }
        }
        return "";
    }

    static String objectJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(JsonUtil.quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Number) {
                builder.append(value);
            } else {
                builder.append(ToolJson.quote(String.valueOf(value)));
            }
        }
        return builder.append('}').toString();
    }

    static String extractObjectField(String json, String field) {
        String key = JsonUtil.quote(field);
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }
        int start = json.indexOf('{', keyIndex + key.length());
        if (start < 0) {
            return "";
        }
        int end = findMatching(json, start, '{', '}');
        return end < 0 ? "" : json.substring(start, end + 1);
    }

    private static int findMatching(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
