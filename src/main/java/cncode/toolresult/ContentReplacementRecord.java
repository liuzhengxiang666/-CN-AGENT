package cncode.toolresult;

public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {
    public static final String KIND_TOOL_RESULT = "tool-result";

    public ContentReplacementRecord {
        kind = kind == null || kind.isBlank() ? KIND_TOOL_RESULT : kind;
        toolUseId = toolUseId == null ? "" : toolUseId;
        replacement = replacement == null ? "" : replacement;
    }

    public static ContentReplacementRecord toolResult(String toolUseId, String replacement) {
        return new ContentReplacementRecord(KIND_TOOL_RESULT, toolUseId, replacement);
    }
}
