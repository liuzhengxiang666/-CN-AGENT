package cncode.skill;

import cncode.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SkillCatalogTest {
    public static void main(String[] args) throws Exception {
        String oldHome = System.getProperty("user.home");
        Path root = Files.createTempDirectory("skill-catalog");
        Path home = Files.createTempDirectory("skill-home");
        System.setProperty("user.home", home.toString());
        try {
            writeSkill(home.resolve(".cncode/skills/demo"), "demo", "user demo", "User body");
            writeSkill(root.resolve(".cncode/skills/demo"), "demo", "project demo", "Project body");
            writeSkill(root.resolve(".cncode/skills/extra"), "extra", "extra skill", "Extra body");

            SkillCatalog catalog = new SkillCatalog();
            catalog.loadCatalog(root, ToolRegistry.defaults());
            if (!catalog.listText().contains("commit") || !catalog.listText().contains("extra")) {
                throw new AssertionError("builtin/project skills missing: " + catalog.listText());
            }
            SkillDefinition demo = catalog.getFull("demo").orElseThrow();
            if (!"project demo".equals(demo.meta().description()) || !demo.promptBody().contains("Project body")) {
                throw new AssertionError("project should override user: " + demo);
            }
            writeSkill(root.resolve(".cncode/skills/new-skill"), "new-skill", "new skill", "New body");
            catalog.reload(ToolRegistry.defaults());
            if (catalog.get("new-skill").isEmpty()) {
                throw new AssertionError("reload did not discover new skill");
            }
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private static void writeSkill(Path dir, String name, String description, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body), StandardCharsets.UTF_8);
    }
}
