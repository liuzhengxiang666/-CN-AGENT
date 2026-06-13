package cncode.tool.builtin;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolResult;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;
import java.util.stream.Collectors;

public class FindFilesTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "find_files",
                "当用户要求按文件名查找文件、列出某类路径或确认文件是否存在时，优先使用此工具在工作目录内按 glob 模式返回相对路径列表。",
                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"glob 模式，例如 **/*.java 或 AGENTS.md\"}},\"required\":[\"pattern\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            String pattern = ToolJson.string(arguments, "pattern");
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            String output;
            try (var paths = Files.walk(context.workspaceRoot())) {
                output = paths.filter(Files::isRegularFile)
                        .map(path -> context.workspaceRoot().relativize(path))
                        .filter(matcher::matches)
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining("\n"));
            }
            return ToolResult.success(metadata().name(), "文件查找完成", context.limit(output.isBlank() ? "[无匹配文件]" : output));
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "查找文件失败", error.getMessage());
        }
    }
}
