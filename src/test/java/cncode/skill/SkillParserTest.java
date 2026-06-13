package cncode.skill;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SkillParserTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("skill-parser");
        Path demo = root.resolve("Demo Skill");
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill
                allowedTools: [read_file, search_code]
                mode: fork
                forkContext: recent
                ---
                Run demo SOP with $ARGUMENTS.
                """, StandardCharsets.UTF_8);

        SkillDefinition skill = new SkillParser().parseDirectory(demo, SkillSource.Tier.PROJECT, true);
        if (!"demo".equals(skill.meta().name()) || !"demo skill".equals(skill.meta().description())) {
            throw new AssertionError("frontmatter parse failed: " + skill);
        }
        if (skill.meta().allowedTools().size() != 2 || skill.meta().mode() != SkillExecutionMode.FORK || skill.meta().forkContext() != ForkContextMode.RECENT) {
            throw new AssertionError("meta parse failed: " + skill.meta());
        }
        if (!skill.promptBody().contains("$ARGUMENTS")) {
            throw new AssertionError("body missing");
        }

        Path fallback = root.resolve("Fallback Skill");
        Files.createDirectories(fallback);
        Files.writeString(fallback.resolve("SKILL.md"), "# Title\n\nFirst useful line\n", StandardCharsets.UTF_8);
        SkillDefinition fallbackSkill = new SkillParser().parseDirectory(fallback, SkillSource.Tier.PROJECT, false);
        if (!"fallback-skill".equals(fallbackSkill.meta().name()) || !"First useful line".equals(fallbackSkill.meta().description())) {
            throw new AssertionError("fallback meta failed: " + fallbackSkill.meta());
        }
    }
}
