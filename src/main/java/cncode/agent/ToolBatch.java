package cncode.agent;

import cncode.tool.ToolCall;

import java.util.List;

public record ToolBatch(boolean parallel, List<IndexedToolCall> calls) {
    public ToolBatch {
        calls = List.copyOf(calls);
    }

    public record IndexedToolCall(int index, ToolCall call) {
    }
}
