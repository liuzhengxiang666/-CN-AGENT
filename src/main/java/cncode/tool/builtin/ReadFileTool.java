package cncode.tool.builtin;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReadFileTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "read_file",
                "当用户要求查看、读取、总结或引用本地文件内容时，必须使用此工具读取工作目录内的文本文件；需要文件内容时优先使用 read_file，不要凭空猜测文件内容。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"工作目录内文件路径，例如 AGENTS.md 或 src/main/java/App.java\"}},\"required\":[\"path\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            Path path = context.resolveInsideWorkspace(ToolJson.string(arguments, "path"));
            if (!Files.exists(path)) {
                return ToolResult.failure(metadata().name(), "文件不存在", "文件不存在：" + context.relative(path));
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return ToolResult.success(metadata().name(), "已读取文件：" + context.relative(path), context.limit(content));
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "读取文件失败", error.getMessage());
        }
    }
}
