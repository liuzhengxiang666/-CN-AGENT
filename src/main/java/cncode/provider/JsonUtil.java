package cncode.provider;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    public static String extractStringField(String json, String field) throws ProviderException {
        String key = quote(field);
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }
        return readJsonString(json, start);
    }

    private static String readJsonString(String json, int quoteIndex) throws ProviderException {
        StringBuilder builder = new StringBuilder();
        for (int i = quoteIndex + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return builder.toString();
            }
            if (c != '\\') {
                builder.append(c);
                continue;
            }
            if (i + 1 >= json.length()) {
                throw new ProviderException("JSON 字符串转义不完整。");
            }
            char escaped = json.charAt(++i);
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
                    if (i + 4 >= json.length()) {
                        throw new ProviderException("JSON unicode 转义不完整。");
                    }
                    String hex = json.substring(i + 1, i + 5);
                    try {
                        builder.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException error) {
                        throw new ProviderException("JSON unicode 转义非法：" + hex, error);
                    }
                    i += 4;
                }
                default -> throw new ProviderException("JSON 字符串包含未知转义：" + escaped);
            }
        }
        throw new ProviderException("JSON 字符串未闭合。");
    }
}
