package cncode.skill;

import cncode.tool.ToolRegistry;

import java.util.List;

public class SkillToolPolicyTest {
    public static void main(String[] args) {
        SkillToolPolicy policy = new SkillToolPolicy();
        SkillDefinition ok = skill("ok", List.of("read_file"));
        policy.assertAllowedToolsExist(ok, ToolRegistry.defaults());
        if (!policy.filterFor(ok).test("read_file") || policy.filterFor(ok).test("write_file")) {
            throw new AssertionError("filter failed");
        }
        if (!policy.filterFor(ok).test("load_skill")) {
            throw new AssertionError("load_skill should be exempt");
        }
        try {
            policy.assertAllowedToolsExist(skill("bad", List.of("DefinitelyMissingTool")), ToolRegistry.defaults());
            throw new AssertionError("missing tool should fail");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("DefinitelyMissingTool")) {
                throw new AssertionError("missing tool name not reported: " + expected.getMessage());
            }
        }
    }

    private static SkillDefinition skill(String name, List<String> tools) {
        return new SkillDefinition(
                new SkillMeta(name, "desc", "", List.of(), tools, SkillExecutionMode.INLINE, "inherit", ForkContextMode.NONE),
                "body",
                new SkillSource(SkillSource.Tier.PROJECT, null),
                true,
                List.of(),
                List.of()
        );
    }
}
