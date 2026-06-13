package cncode.provider;

import cncode.prompt.PromptCacheStats;
import cncode.tool.ToolCall;

public interface StreamHandler {
    void onDelta(String text);

    default void onThinkingDelta(String text) {
    }

    default void onToolCall(ToolCall toolCall) {
    }

    default void onComplete() {
    }

    default void onComplete(String stopReason) {
        onComplete();
    }

    default void onCacheStats(PromptCacheStats stats) {
    }

    default void onError(Exception error) {
    }
}
