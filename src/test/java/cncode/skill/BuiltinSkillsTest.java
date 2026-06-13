package cncode.skill;

public class BuiltinSkillsTest {
    public static void main(String[] args) {
        var skills = BuiltinSkills.load();
        if (skills.stream().noneMatch(skill -> "commit".equals(skill.meta().name()))) {
            throw new AssertionError("commit builtin missing");
        }
        if (skills.stream().noneMatch(skill -> "review".equals(skill.meta().name()))) {
            throw new AssertionError("review builtin missing");
        }
        SkillDefinition test = skills.stream().filter(skill -> "test".equals(skill.meta().name())).findFirst().orElseThrow();
        if (test.meta().mode() != SkillExecutionMode.FORK || test.meta().forkContext() != ForkContextMode.RECENT) {
            throw new AssertionError("test builtin should cover fork mode");
        }
    }
}
