package cncode.instruction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InstructionLoader {
    private static final List<String> ENTRY_FILES = List.of("CNCODE.md", "AGENTS.md", ".cncode/INSTRUCTIONS.md");

    private final Path workDir;

    public InstructionLoader(Path workDir) {
        this.workDir = (workDir == null ? Path.of("") : workDir).toAbsolutePath().normalize();
    }

    public InstructionLoadResult load() {
        List<String> warnings = new ArrayList<>();
        for (String entry : ENTRY_FILES) {
            Path path = workDir.resolve(entry).normalize();
            if (Files.isRegularFile(path)) {
                String content = loadFile(path, new HashSet<>(), warnings);
                return new InstructionLoadResult(path, content, warnings);
            }
        }
        return new InstructionLoadResult(null, "", warnings);
    }

    private String loadFile(Path path, Set<Path> stack, List<String> warnings) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workDir)) {
            warnings.add("include 越界，已跳过：" + workDir.relativize(workDir));
            return "";
        }
        if (!stack.add(normalized)) {
            warnings.add("include 循环引用，已跳过：" + display(normalized));
            return "";
        }
        try {
            StringBuilder builder = new StringBuilder();
            for (String line : Files.readAllLines(normalized, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("@include ")) {
                    String includePath = trimmed.substring("@include ".length()).trim();
                    Path included = normalized.getParent().resolve(includePath).normalize();
                    if (!included.toAbsolutePath().normalize().startsWith(workDir)) {
                        warnings.add("include 越界，已跳过：" + includePath);
                    } else if (!Files.isRegularFile(included)) {
                        warnings.add("include 文件不存在，已跳过：" + includePath);
                    } else {
                        builder.append("\n<!-- include: ").append(display(included)).append(" -->\n");
                        builder.append(loadFile(included, stack, warnings)).append("\n");
                    }
                    continue;
                }
                builder.append(line).append("\n");
            }
            return builder.toString().strip();
        } catch (IOException error) {
            warnings.add("指令文件读取失败：" + display(normalized) + "：" + error.getMessage());
            return "";
        } finally {
            stack.remove(normalized);
        }
    }

    private String display(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workDir)) {
            return workDir.relativize(normalized).toString();
        }
        return normalized.toString();
    }
}
