package cncode.agent;

import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;
import cncode.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

public class StreamCollector {
    private final ChatProvider provider;

    public StreamCollector(ChatProvider provider) {
        this.provider = provider;
    }

    public CollectedStream collect(ChatRequest request, AgentEventHandler handler) throws ProviderException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder errorMessage = new StringBuilder();
        StringBuilder stopReason = new StringBuilder();

        provider.streamChat(request, new StreamHandler() {
            @Override
            public void onDelta(String delta) {
                text.append(delta);
                handler.onEvent(AgentEvent.delta(delta));
            }

            @Override
            public void onThinkingDelta(String thinking) {
                handler.onEvent(AgentEvent.thinkingDelta(thinking));
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                if (toolCall != null && !toolCall.name().isBlank()) {
                    toolCalls.add(toolCall);
                }
            }

            @Override
            public void onComplete(String reason) {
                if (stopReason.isEmpty() && reason != null) {
                    stopReason.append(reason);
                }
            }

            @Override
            public void onError(Exception error) {
                if (errorMessage.isEmpty()) {
                    errorMessage.append(error.getMessage());
                }
            }
        });

        return new CollectedStream(text.toString(), toolCalls, stopReason.toString(), errorMessage.toString());
    }
}
