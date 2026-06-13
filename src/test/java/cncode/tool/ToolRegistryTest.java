package cncode.tool;

import java.util.List;

public class ToolRegistryTest {
    public static void main(String[] args) {
        ToolRegistry registry = ToolRegistry.defaults();
        for (String name : new String[]{"read_file", "write_file", "replace_file", "run_command", "find_files", "search_code"}) {
            if (registry.find(name).isEmpty()) {
                throw new AssertionError("missing tool: " + name);
            }
        }
        List<Tool> tools = registry.all();
        assertTool(tools.get(0), "read_file", ToolCategory.READ, "查看");
        assertTool(tools.get(1), "write_file", ToolCategory.WRITE, "创建");
        assertTool(tools.get(2), "replace_file", ToolCategory.WRITE, "修改");
        assertTool(tools.get(3), "run_command", ToolCategory.COMMAND, "运行");
        assertTool(tools.get(4), "find_files", ToolCategory.READ, "查找");
        assertTool(tools.get(5), "search_code", ToolCategory.READ, "搜索");

        String toolsJson = registry.toOpenAiToolsJson();
        if (!toolsJson.contains("\"type\":\"function\"") || !toolsJson.contains("read_file") || !toolsJson.contains("parameters")) {
            throw new AssertionError("tools json invalid");
        }
        if (!toolsJson.contains("需要文件内容时优先使用") || !toolsJson.contains("修改前必须先读") || !toolsJson.contains("优先使用专用工具")) {
            throw new AssertionError("tool descriptions missing prompt rules: " + toolsJson);
        }
    }

    private static void assertTool(Tool tool, String name, ToolCategory category, String descriptionKeyword) {
        if (!name.equals(tool.metadata().name())) {
            throw new AssertionError("tool order invalid: " + tool.metadata().name());
        }
        if (tool.category() != category) {
            throw new AssertionError("tool category invalid: " + name);
        }
        if (!tool.metadata().description().contains(descriptionKeyword)) {
            throw new AssertionError("tool description too weak: " + name);
        }
    }
}
