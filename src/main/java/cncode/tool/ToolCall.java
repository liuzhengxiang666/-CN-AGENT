package cncode.tool;

public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        id = id == null || id.isBlank() ? "tool_call_0" : id;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
