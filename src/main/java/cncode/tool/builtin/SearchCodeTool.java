package cncode.tool.builtin;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Pattern;

public class SearchCodeTool implements Tool {
    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "search_code",
                "当用户要求按代码内容搜索、查找关键字、定位实现位置或确认某段文本在哪里出现时，优先使用此工具返回匹配文件、行号和文本片段。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"要搜索的文本或正则表达式\"},\"regex\":{\"type\":\"boolean\",\"description\":\"为 true 时按正则搜索，否则按普通文本搜索\"}},\"required\":[\"query\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String query = ToolJson.string(arguments, "query");
        boolean regex = ToolJson.bool(arguments, "regex", false);
        if (query.isEmpty()) {
            return ToolResult.failure(metadata().name(), "搜索失败", "query 不能为空");
        }
        Pattern pattern = regex ? Pattern.compile(query) : null;
        StringBuilder output = new StringBuilder();
        try (var paths = Files.walk(context.workspaceRoot())) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                if (!context.isTextFile(path)) {
                    continue;
                }
                var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    boolean matched = regex ? pattern.matcher(line).find() : line.contains(query);
                    if (matched) {
                        output.append(context.relative(path)).append(":").append(i + 1).append(": ").append(line.strip()).append("\n");
                    }
                    if (output.length() > context.maxOutputChars()) {
                        return ToolResult.success(metadata().name(), "搜索完成，结果已截断", context.limit(output.toString()));
                    }
                }
            }
            return ToolResult.success(metadata().name(), "搜索完成", output.isEmpty() ? "[无匹配内容]" : context.limit(output.toString()));
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "搜索代码失败", error.getMessage());
        }
    }
}
