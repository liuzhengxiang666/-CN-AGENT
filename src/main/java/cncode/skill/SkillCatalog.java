package cncode.skill;

import cncode.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SkillCatalog {
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final List<SkillLoadIssue> issues = new ArrayList<>();
    private final SkillParser parser = new SkillParser();
    private Path workDir;

    public synchronized void loadCatalog(Path workDir, ToolRegistry registry) {
        this.workDir = (workDir == null ? Path.of("") : workDir).toAbsolutePath().normalize();
        skills.clear();
        issues.clear();
        for (SkillDefinition builtin : BuiltinSkills.load()) {
            register(builtin);
        }
        loadTier(userSkillsDir(), SkillSource.Tier.USER);
        loadTier(this.workDir.resolve(".cncode").resolve("skills"), SkillSource.Tier.PROJECT);
        validateTools(registry);
    }

    public synchronized void reload(ToolRegistry registry) {
        loadCatalog(workDir == null ? Path.of("").toAbsolutePath() : workDir, registry);
    }

    public synchronized void register(SkillDefinition skill) {
        skills.put(skill.meta().name(), skill);
    }

    public synchronized Optional<SkillDefinition> get(String name) {
        return Optional.ofNullable(skills.get(SkillMeta.normalizeName(name)));
    }

    public synchronized Optional<SkillDefinition> getFull(String name) {
        String key = SkillMeta.normalizeName(name);
        SkillDefinition current = skills.get(key);
        if (current == null) {
            return Optional.empty();
        }
        SkillSource source = current.source();
        if (source == null || source.path() == null) {
            return Optional.of(current);
        }
        try {
            SkillDefinition loaded = parser.parseDirectory(source.path(), source.tier(), true);
            skills.put(key, loaded);
            return Optional.of(loaded);
        } catch (IOException error) {
            issues.add(new SkillLoadIssue(source.path(), "Failed to reload skill " + key + ": " + error.getMessage()));
            return Optional.of(current);
        }
    }

    public synchronized List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    public synchronized List<SkillLoadIssue> issues() {
        return List.copyOf(issues);
    }

    public synchronized String buildSummary() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Available skills. Use load_skill or slash commands to load full SOP when needed:\n");
        for (SkillDefinition skill : skills.values()) {
            builder.append("- /")
                    .append(skill.meta().name())
                    .append(": ")
                    .append(skill.meta().description())
                    .append("\n");
        }
        return builder.toString().strip();
    }

    public synchronized String listText() {
        if (skills.isEmpty()) {
            return "No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md";
        }
        StringBuilder builder = new StringBuilder();
        for (SkillDefinition skill : skills.values()) {
            builder.append("/")
                    .append(skill.meta().name())
                    .append(" - ")
                    .append(skill.meta().description())
                    .append(" (")
                    .append(sourceLabel(skill))
                    .append(")\n");
        }
        appendIssues(builder);
        return builder.toString().strip();
    }

    public synchronized String detailText(String name) {
        SkillDefinition skill = get(name).orElse(null);
        if (skill == null) {
            return "Unknown skill: " + name;
        }
        return """
                Skill: %s
                Description: %s
                Source: %s
                Mode: %s
                Model: %s
                Fork context: %s
                Allowed tools: %s
                When to use: %s
                """.formatted(
                skill.meta().name(),
                skill.meta().description(),
                sourceLabel(skill),
                skill.meta().mode().name().toLowerCase(),
                skill.meta().model(),
                skill.meta().forkContext().name().toLowerCase(),
                skill.meta().allowedTools().isEmpty() ? "(none)" : String.join(", ", skill.meta().allowedTools()),
                skill.meta().whenToUse().isBlank() ? "(none)" : skill.meta().whenToUse()
        ).strip();
    }

    private void loadTier(Path dir, SkillSource.Tier tier) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                try {
                    register(parser.parseDirectory(child, tier, false));
                } catch (Exception error) {
                    issues.add(new SkillLoadIssue(child, error.getMessage()));
                }
            }
        } catch (IOException error) {
            issues.add(new SkillLoadIssue(dir, error.getMessage()));
        }
    }

    private void validateTools(ToolRegistry registry) {
        if (registry == null) {
            return;
        }
        SkillToolPolicy policy = new SkillToolPolicy();
        for (SkillDefinition skill : skills.values()) {
            policy.assertAllowedToolsExist(skill, registry);
        }
    }

    private Path userSkillsDir() {
        return Path.of(System.getProperty("user.home", "")).resolve(".cncode").resolve("skills");
    }

    private String sourceLabel(SkillDefinition skill) {
        if (skill.source() == null) {
            return "unknown";
        }
        if (skill.source().path() == null) {
            return skill.source().tier().name().toLowerCase();
        }
        return skill.source().tier().name().toLowerCase() + ": " + skill.source().path();
    }

    private void appendIssues(StringBuilder builder) {
        if (issues.isEmpty()) {
            return;
        }
        builder.append("\nIssues:");
        for (SkillLoadIssue issue : issues) {
            builder.append("\n- ").append(issue.path()).append(": ").append(issue.message());
        }
    }
}
