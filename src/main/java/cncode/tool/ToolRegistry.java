package cncode.tool;

import cncode.tool.builtin.FindFilesTool;
import cncode.tool.builtin.ReadFileTool;
import cncode.tool.builtin.ReplaceFileTool;
import cncode.tool.builtin.RunCommandTool;
import cncode.tool.builtin.SearchCodeTool;
import cncode.tool.builtin.WriteFileTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public static ToolRegistry defaults() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ReplaceFileTool());
        registry.register(new RunCommandTool());
        registry.register(new FindFilesTool());
        registry.register(new SearchCodeTool());
        return registry;
    }

    public void register(Tool tool) {
        tools.put(tool.metadata().name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    public List<String> names() {
        return tools.keySet().stream().toList();
    }

    public ToolRegistry filtered(Predicate<String> visible) {
        if (visible == null) {
            return this;
        }
        ToolRegistry result = new ToolRegistry();
        for (Tool tool : tools.values()) {
            if (visible.test(tool.metadata().name())) {
                result.register(tool);
            }
        }
        return result;
    }

    public String toOpenAiToolsJson() {
        return all().stream()
                .map(tool -> tool.metadata().toOpenAiToolJson())
                .reduce((left, right) -> left + "," + right)
                .map(json -> "[" + json + "]")
                .orElse("[]");
    }
}
