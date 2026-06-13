package cncode.skill;

import java.nio.file.Path;

public record SkillLoadIssue(Path path, String message) {
}
