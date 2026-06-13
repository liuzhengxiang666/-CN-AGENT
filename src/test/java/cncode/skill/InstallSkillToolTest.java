package cncode.skill;

import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public class InstallSkillToolTest {
    public static void main(String[] args) throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("install-skill-home");
        Path root = Files.createTempDirectory("install-skill");
        System.setProperty("user.home", home.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/frontend-design/SKILL.md", exchange -> {
            byte[] bytes = """
                    ---
                    name: frontend-design
                    description: frontend design skill
                    allowedTools: [read_file]
                    ---
                    Create distinctive frontend UI with strong visual quality.
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ToolRegistry registry = ToolRegistry.defaults();
            SkillCatalog catalog = new SkillCatalog();
            catalog.loadCatalog(root, registry);
            registry.register(new InstallSkillTool(catalog, registry));

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/frontend-design/";
            var result = new InstallSkillTool(catalog, registry).execute(
                    new ToolExecutionContext(root, Duration.ofSeconds(10), 12000),
                    Map.of("url", url)
            );
            if (!result.success() || !result.output().contains("/frontend-design")) {
                throw new AssertionError("install failed: " + result);
            }
            if (catalog.get("frontend-design").isEmpty()) {
                throw new AssertionError("catalog did not reload installed skill");
            }
            Path installed = root.resolve(".cncode/skills/frontend-design/SKILL.md");
            if (!Files.readString(installed).contains("frontend design skill")) {
                throw new AssertionError("installed file missing content");
            }
        } finally {
            server.stop(0);
            System.setProperty("user.home", oldHome);
        }
    }
}
