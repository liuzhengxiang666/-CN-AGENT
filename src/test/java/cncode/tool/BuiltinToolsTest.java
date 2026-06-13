package cncode.tool;

import cncode.tool.builtin.FindFilesTool;
import cncode.tool.builtin.ReadFileTool;
import cncode.tool.builtin.ReplaceFileTool;
import cncode.tool.builtin.RunCommandTool;
import cncode.tool.builtin.SearchCodeTool;
import cncode.tool.builtin.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public class BuiltinToolsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cncode-tools");
        ToolExecutionContext context = new ToolExecutionContext(root, Duration.ofSeconds(3), 1000);

        ToolResult write = new WriteFileTool().execute(context, Map.of("path", "a/test.txt", "content", "hello old"));
        assertSuccess(write);

        ToolResult read = new ReadFileTool().execute(context, Map.of("path", "a/test.txt"));
        assertSuccess(read);
        if (!read.output().contains("hello old")) {
            throw new AssertionError("read output invalid");
        }

        ToolResult replace = new ReplaceFileTool().execute(context, Map.of("path", "a/test.txt", "old_text", "old", "new_text", "new"));
        assertSuccess(replace);

        ToolResult replaceMissing = new ReplaceFileTool().execute(context, Map.of("path", "a/test.txt", "old_text", "missing", "new_text", "x"));
        assertFailure(replaceMissing);

        ToolResult find = new FindFilesTool().execute(context, Map.of("pattern", "**/*.txt"));
        assertSuccess(find);

        ToolResult search = new SearchCodeTool().execute(context, Map.of("query", "new", "regex", false));
        assertSuccess(search);

        ToolResult command = new RunCommandTool().execute(context, Map.of("command", "echo ok", "timeout_seconds", 3));
        assertSuccess(command);

        ToolResult outside = new ReadFileTool().execute(context, Map.of("path", "../outside.txt"));
        assertFailure(outside);
    }

    private static void assertSuccess(ToolResult result) {
        if (!result.success()) {
            throw new AssertionError("expected success: " + result);
        }
    }

    private static void assertFailure(ToolResult result) {
        if (result.success()) {
            throw new AssertionError("expected failure: " + result);
        }
    }
}
