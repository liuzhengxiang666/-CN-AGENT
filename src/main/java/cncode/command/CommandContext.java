package cncode.command;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public record CommandContext(
        String args,
        Path workDir,
        Supplier<String> model,
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList
) {
    public CommandContext {
        args = args == null ? "" : args;
        workDir = workDir == null ? Path.of("").toAbsolutePath() : workDir;
        model = model == null ? () -> "" : model;
        permissionMode = permissionMode == null ? () -> "default" : permissionMode;
        toolCount = toolCount == null ? () -> 0 : toolCount;
        tokenCount = tokenCount == null ? () -> new int[]{0, 0} : tokenCount;
        memoryList = memoryList == null ? List::of : memoryList;
        memoryClear = memoryClear == null ? () -> {
        } : memoryClear;
        sessionInfo = sessionInfo == null ? () -> "" : sessionInfo;
        skillList = skillList == null ? List::of : skillList;
    }
}
