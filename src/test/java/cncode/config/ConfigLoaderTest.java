package cncode.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigLoaderTest {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("cncode-config", ".yaml");
        Files.writeString(file, """
                protocol: openai
                model: test-model
                base_url: https://example.test/v1
                api_key: secret
                mcp.server.context7.command: npx
                mcp.server.context7.args: -y,@upstash/context7-mcp
                mcp.server.context7.env.NODE_OPTIONS: --no-warnings
                mcp.server.docs.url: http://localhost:3000/mcp
                mcp.server.docs.headers.Authorization: Bearer ${TOKEN}
                mcp.server.docs.timeout_seconds: 12
                """, StandardCharsets.UTF_8);

        AppConfig config = new ConfigLoader().load(file);
        assertEquals("openai", config.protocol());
        assertEquals("test-model", config.model());
        assertEquals("https://example.test/v1", config.baseUrl());
        assertEquals("secret", config.apiKey());
        if (config.mcpServers().size() != 2) {
            throw new AssertionError("mcp server count invalid: " + config.mcpServers());
        }
        var context7 = config.mcpServers().stream().filter(server -> "context7".equals(server.name())).findFirst().orElseThrow();
        assertEquals("npx", context7.command());
        if (!context7.args().contains("@upstash/context7-mcp") || !"--no-warnings".equals(context7.env().get("NODE_OPTIONS"))) {
            throw new AssertionError("stdio mcp config invalid: " + context7);
        }
        var docs = config.mcpServers().stream().filter(server -> "docs".equals(server.name())).findFirst().orElseThrow();
        assertEquals("http://localhost:3000/mcp", docs.url());
        assertEquals("Bearer ${TOKEN}", docs.headers().get("Authorization"));
        if (docs.timeoutSeconds() != 12) {
            throw new AssertionError("timeout invalid: " + docs.timeoutSeconds());
        }

        Files.deleteIfExists(file);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("期望 " + expected + "，实际 " + actual);
        }
    }
}
