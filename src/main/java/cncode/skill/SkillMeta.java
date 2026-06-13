package cncode.skill;

import java.util.List;

public record SkillMeta(
        String name,
        String description,
        String whenToUse,
        List<String> tags,
        List<String> allowedTools,
        SkillExecutionMode mode,
        String model,
        ForkContextMode forkContext
) {
    public SkillMeta {
        name = normalizeName(name);
        description = description == null ? "" : description.strip();
        whenToUse = whenToUse == null ? "" : whenToUse.strip();
        tags = tags == null ? List.of() : List.copyOf(tags);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        mode = mode == null ? SkillExecutionMode.INLINE : mode;
        model = model == null || model.isBlank() ? "inherit" : model.strip();
        forkContext = forkContext == null ? ForkContextMode.NONE : forkContext;
    }

    public static String normalizeName(String value) {
        String safe = value == null ? "" : value.strip().toLowerCase().replaceAll("\\s+", "-");
        return safe.replaceAll("[^a-z0-9_.-]", "-").replaceAll("-+", "-");
    }
}
