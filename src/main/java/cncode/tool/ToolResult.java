package cncode.tool;

public record ToolResult(boolean success, String toolName, String summary, String output, String error) {
    public static ToolResult success(String toolName, String summary, String output) {
        return new ToolResult(true, toolName, summary, output == null ? "" : output, "");
    }

    public static ToolResult failure(String toolName, String summary, String error) {
        return new ToolResult(false, toolName, summary, "", error == null ? "" : error);
    }

    public String toJson() {
        return "{"
                + "\"success\":" + success + ","
                + "\"toolName\":" + ToolJson.quote(toolName) + ","
                + "\"summary\":" + ToolJson.quote(summary) + ","
                + "\"output\":" + ToolJson.quote(output) + ","
                + "\"error\":" + ToolJson.quote(error)
                + "}";
    }
}
