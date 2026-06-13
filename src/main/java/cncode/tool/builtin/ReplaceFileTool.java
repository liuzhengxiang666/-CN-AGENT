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

public class ReplaceFileTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "replace_file",
                "当用户要求修改已有文件的局部内容时，使用此工具按 old_text 唯一匹配替换；修改前必须先读相关文件，匹配 0 次或多次都会失败并返回原因。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"工作目录内要修改的文件路径\"},\"old_text\":{\"type\":\"string\",\"description\":\"必须在文件中恰好出现一次的原文\"},\"new_text\":{\"type\":\"string\",\"description\":\"替换后的新文本\"}},\"required\":[\"path\",\"old_text\",\"new_text\"]}"
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
            String oldText = ToolJson.string(arguments, "old_text");
            String newText = ToolJson.string(arguments, "new_text");
            if (oldText.isEmpty()) {
                return ToolResult.failure(metadata().name(), "替换失败", "old_text 不能为空");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldText);
            if (count == 0) {
                return ToolResult.failure(metadata().name(), "替换失败", "原文匹配 0 次，请提供准确 old_text。");
            }
            if (count > 1) {
                return ToolResult.failure(metadata().name(), "替换失败", "原文匹配 " + count + " 次，请提供唯一 old_text。");
            }
            Files.writeString(path, content.replace(oldText, newText), StandardCharsets.UTF_8);
            return ToolResult.success(metadata().name(), "已替换文件：" + context.relative(path), "原文唯一匹配并已替换。");
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "替换文件失败", error.getMessage());
        }
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
