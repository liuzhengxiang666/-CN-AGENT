package cncode.agent;

import cncode.tool.ToolCall;

import java.util.List;

public record CollectedStream(String text, List<ToolCall> toolCalls, String stopReason, String errorMessage) {
    public CollectedStream {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        stopReason = stopReason == null ? "" : stopReason;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
