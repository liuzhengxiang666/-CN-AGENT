package cncode.skill;

import java.nio.file.Path;

public record SkillSource(Tier tier, Path path) {
    public enum Tier {
        BUILTIN,
        USER,
        PROJECT
    }
}
