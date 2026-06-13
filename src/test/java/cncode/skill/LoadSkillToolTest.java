package cncode.skill;

import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LoadSkillToolTest {
    public static void main(String[] args) throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("load-skill-home");
        Path root = Files.createTempDirectory("load-skill");
        System.setProperty("user.home", home.toString());
        try {
            Path demo = root.resolve(".cncode/skills/demo");
            Files.createDirectories(demo);
            Files.writeString(demo.resolve("SKILL.md"), """
                    ---
                    name: demo
                    description: demo skill
                    allowedTools: [read_file]
                    ---
                    Demo SOP
                    """, StandardCharsets.UTF_8);
            ToolRegistry registry = ToolRegistry.defaults();
            SkillCatalog catalog = new SkillCatalog();
            catalog.loadCatalog(root, registry);
            ActiveSkillState state = new ActiveSkillState();
            LoadSkillTool tool = new LoadSkillTool(catalog, state, registry);
            registry.register(tool);
            var result = tool.execute(ToolExecutionContext.defaultContext(), Map.of("name", "demo"));
            if (!result.success() || !state.buildContext().contains("Demo SOP")) {
                throw new AssertionError("load skill failed: " + result);
            }
            if (!tool.toolFilter().test("load_skill") || tool.toolFilter().test("write_file")) {
                throw new AssertionError("tool filter invalid");
            }
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
