package cncode.web;

import cncode.config.AppConfig;

public class WebJsonTest {
    public static void main(String[] args) throws Exception {
        String message = WebJson.extractMessage("{\"message\":\"hello\\nworld\"}");
        if (!"hello\nworld".equals(message)) {
            throw new AssertionError("message parse failed");
        }

        String sse = WebJson.sse("delta", WebJson.textData("text", "a\"b"));
        if (!sse.contains("event: delta") || !sse.contains("\\\"")) {
            throw new AssertionError("sse format failed");
        }

        String status = WebJson.status(new AppConfig("openai", "deepseek-chat", "https://api.deepseek.com", "test-key"), 2, 6, true);
        if (!status.contains("\"tools\":\"enabled (6)\"") || !status.contains("\"planOnly\":true") || status.contains("not enabled")) {
            throw new AssertionError("tools status failed: " + status);
        }
    }
}
