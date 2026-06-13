package cncode.skill;

import cncode.tool.ToolRegistry;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

public final class SkillToolPolicy {
    public static final String LOAD_SKILL_TOOL = "load_skill";
    public static final String INSTALL_SKILL_TOOL = "install_skill";

    public void assertAllowedToolsExist(SkillDefinition skill, ToolRegistry registry) {
        for (String name : skill.meta().allowedTools()) {
            if (registry.find(name).isEmpty() && !LOAD_SKILL_TOOL.equals(name) && !INSTALL_SKILL_TOOL.equals(name)) {
                throw new IllegalStateException("Skill " + skill.meta().name() + " references missing tool: " + name);
            }
        }
    }

    public Predicate<String> filterFor(SkillDefinition skill) {
        if (skill.meta().allowedTools().isEmpty()) {
            return ignored -> true;
        }
        Set<String> allowed = new LinkedHashSet<>(skill.meta().allowedTools());
        allowed.add(LOAD_SKILL_TOOL);
        allowed.add(INSTALL_SKILL_TOOL);
        return allowed::contains;
    }
}
