package cncode.tool.builtin;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RunCommandTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "run_command",
                "当用户要求运行构建、测试或缺少专用工具的 shell/PowerShell 命令时，使用此工具在工作目录内执行命令并返回 stdout、stderr 和 exit_code；有 read_file/find_files/search_code 等专用工具时优先使用专用工具。",
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"要在工作目录内执行的命令\"},\"timeout_seconds\":{\"type\":\"integer\",\"description\":\"命令超时时间，单位秒\"}},\"required\":[\"command\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String command = ToolJson.string(arguments, "command");
        int timeoutSeconds = Math.max(1, ToolJson.integer(arguments, "timeout_seconds", (int) context.timeout().toSeconds()));
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(context.workspaceRoot().toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure(metadata().name(), "命令超时", "命令超过 " + timeoutSeconds + " 秒未完成。");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String output = "exit_code: " + process.exitValue() + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
            boolean success = process.exitValue() == 0;
            if (success) {
                return ToolResult.success(metadata().name(), "命令执行成功", context.limit(output));
            }
            return ToolResult.failure(metadata().name(), "命令退出码非 0", context.limit(output));
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "命令执行失败", error.getMessage());
        }
    }

    private String[] shellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"powershell", "-NoProfile", "-Command", command};
        }
        return new String[]{"sh", "-c", command};
    }
}
