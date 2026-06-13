package cncode.instruction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InstructionLoaderTest {
    public static void main(String[] args) throws Exception {
        loadsHighestPriorityEntry();
        expandsIncludesAndWarns();
    }

    private static void loadsHighestPriorityEntry() throws Exception {
        Path root = Files.createTempDirectory("cncode-instructions");
        Files.writeString(root.resolve("CNCODE.md"), "cncode rules", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "agent rules", StandardCharsets.UTF_8);

        InstructionLoadResult result = new InstructionLoader(root).load();

        if (!result.content().contains("cncode rules") || result.content().contains("agent rules")) {
            throw new AssertionError("CNCODE.md should have priority: " + result);
        }
    }

    private static void expandsIncludesAndWarns() throws Exception {
        Path root = Files.createTempDirectory("cncode-includes");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("CNCODE.md"), """
                root rule
                @include docs/rules.md
                @include missing.md
                @include ../outside.md
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("docs/rules.md"), """
                nested rule
                @include ../CNCODE.md
                """, StandardCharsets.UTF_8);

        InstructionLoadResult result = new InstructionLoader(root).load();

        if (!result.content().contains("root rule") || !result.content().contains("nested rule")) {
            throw new AssertionError("include content missing: " + result.content());
        }
        String warnings = result.warnings().toString();
        if (!warnings.contains("不存在") || !warnings.contains("越界") || !warnings.contains("循环")) {
            throw new AssertionError("expected missing, outside and cycle warnings: " + warnings);
        }
    }
}
