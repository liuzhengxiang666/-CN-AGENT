package cncode.tool;

import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolJson {
    private ToolJson() {
    }

    public static String quote(String value) {
        return JsonUtil.quote(value == null ? "" : value);
    }

    public static Map<String, Object> parseObject(String json) throws ProviderException {
        Map<String, Object> result = new LinkedHashMap<>();
        String text = json == null ? "{}" : json.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new ProviderException("工具参数不是 JSON 对象：" + text);
        }
        int index = 1;
        while (index < text.length() - 1) {
            index = skipWhitespaceAndComma(text, index);
            if (index >= text.length() - 1) {
                break;
            }
            if (text.charAt(index) != '"') {
                throw new ProviderException("工具参数 key 必须是字符串。");
            }
            ReadResult key = readString(text, index);
            index = skipWhitespace(text, key.nextIndex());
            if (index >= text.length() || text.charAt(index) != ':') {
                throw new ProviderException("工具参数缺少冒号。");
            }
            index = skipWhitespace(text, index + 1);
            ReadValue value = readValue(text, index);
            result.put(key.value(), value.value());
            index = value.nextIndex();
        }
        return result;
    }

    public static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public static boolean bool(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static int integer(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static int skipWhitespaceAndComma(String text, int index) {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c) || c == ',') {
                index++;
            } else {
                return index;
            }
        }
        return index;
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static ReadValue readValue(String text, int index) throws ProviderException {
        if (text.charAt(index) == '"') {
            ReadResult string = readString(text, index);
            return new ReadValue(string.value(), string.nextIndex());
        }
        int end = index;
        while (end < text.length() && ",}".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        String raw = text.substring(index, end).trim();
        if ("true".equals(raw)) {
            return new ReadValue(true, end);
        }
        if ("false".equals(raw)) {
            return new ReadValue(false, end);
        }
        if ("null".equals(raw)) {
            return new ReadValue(null, end);
        }
        try {
            return new ReadValue(Integer.parseInt(raw), end);
        } catch (NumberFormatException ignored) {
            return new ReadValue(raw, end);
        }
    }

    private static ReadResult readString(String text, int index) throws ProviderException {
        StringBuilder builder = new StringBuilder();
        for (int i = index + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                return new ReadResult(builder.toString(), i + 1);
            }
            if (c != '\\') {
                builder.append(c);
                continue;
            }
            if (i + 1 >= text.length()) {
                throw new ProviderException("JSON 字符串转义不完整。");
            }
            char escaped = text.charAt(++i);
            switch (escaped) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (i + 4 >= text.length()) {
                        throw new ProviderException("JSON unicode 转义不完整。");
                    }
                    builder.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new ProviderException("未知 JSON 转义：" + escaped);
            }
        }
        throw new ProviderException("JSON 字符串未闭合。");
    }

    private record ReadResult(String value, int nextIndex) {
    }

    private record ReadValue(Object value, int nextIndex) {
    }
}
