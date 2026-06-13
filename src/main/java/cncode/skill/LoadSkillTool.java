package cncode.skill;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.util.Map;
import java.util.function.Predicate;

public final class LoadSkillTool implements Tool {
    private final SkillCatalog catalog;
    private final ActiveSkillState activeSkills;
    private final ToolRegistry registry;

    public LoadSkillTool(SkillCatalog catalog, ActiveSkillState activeSkills, ToolRegistry registry) {
        this.catalog = catalog;
        this.activeSkills = activeSkills;
        this.registry = registry;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                SkillToolPolicy.LOAD_SKILL_TOOL,
                "Load a CN Code skill by name and activate its full SOP in environment context.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill name to load\"}},\"required\":[\"name\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String name = String.valueOf(arguments.getOrDefault("name", "")).strip();
        if (name.isBlank()) {
            return ToolResult.failure(metadata().name(), "Skill name is required", "Usage: load_skill {\"name\":\"<skill-name>\"}");
        }
        SkillDefinition skill = catalog.getFull(name).orElse(null);
        if (skill == null) {
            return ToolResult.failure(metadata().name(), "Unknown skill", "Unknown skill: " + name);
        }
        try {
            SkillToolPolicy policy = new SkillToolPolicy();
            policy.assertAllowedToolsExist(skill, registry);
            activeSkills.activate(skill.meta().name(), skill.promptBody(), skill.meta().allowedTools());
            return ToolResult.success(
                    metadata().name(),
                    "Loaded skill " + skill.meta().name(),
                    "Successfully loaded skill: " + skill.meta().name() + "\nMode: " + skill.meta().mode().name().toLowerCase()
            );
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "Skill load failed", error.getMessage());
        }
    }

    public Predicate<String> toolFilter() {
        return activeSkills.toolFilter();
    }

    public String toString() {
        return ToolJson.quote(metadata().name());
    }
}
