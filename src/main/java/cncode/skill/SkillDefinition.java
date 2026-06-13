package cncode.skill;

import java.nio.file.Path;
import java.util.List;

public record SkillDefinition(
        SkillMeta meta,
        String promptBody,
        SkillSource source,
        boolean bodyLoaded,
        List<Path> toolSchemas,
        List<Path> toolScripts
) {
    public SkillDefinition {
        if (meta == null) {
            throw new IllegalArgumentException("skill meta cannot be null");
        }
        promptBody = promptBody == null ? "" : promptBody;
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
        toolScripts = toolScripts == null ? List.of() : List.copyOf(toolScripts);
    }

    public SkillDefinition withBody(String body, List<Path> schemas, List<Path> scripts) {
        return new SkillDefinition(meta, body, source, true, schemas, scripts);
    }
}
