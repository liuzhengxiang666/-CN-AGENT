package cncode.instruction;

import java.nio.file.Path;
import java.util.List;

public record InstructionLoadResult(Path source, String content, List<String> warnings) {
    public InstructionLoadResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        content = content == null ? "" : content;
    }
}
