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

public class WriteFileTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "write_file",
                "当用户要求创建或覆盖项目工作目录内的文本文件时，使用此工具写入完整内容；Plan 模式下不能写入，执行模式下写入前要确认目标路径和完整内容。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"工作目录内目标文件路径\"},\"content\":{\"type\":\"string\",\"description\":\"要写入文件的完整文本内容\"}},\"required\":[\"path\",\"content\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            Path path = context.resolveInsideWorkspace(ToolJson.string(arguments, "path"));
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = ToolJson.string(arguments, "content");
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return ToolResult.success(metadata().name(), "已写入文件：" + context.relative(path), "写入字符数：" + content.length());
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "写入文件失败", error.getMessage());
        }
    }
}
