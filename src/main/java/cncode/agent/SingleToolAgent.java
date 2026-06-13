package cncode.agent;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;
import cncode.tool.ToolCall;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolExecutor;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SingleToolAgent {
    private final ChatProvider provider;
    private final AppConfig config;
    private final ChatSession session;
    private final ToolRegistry registry;
    private final ToolExecutor executor;

    public SingleToolAgent(ChatProvider provider, AppConfig config, ChatSession session) {
        this(provider, config, session, ToolRegistry.defaults(), ToolExecutionContext.defaultContext());
    }

    public SingleToolAgent(ChatProvider provider, AppConfig config, ChatSession session, ToolRegistry registry, ToolExecutionContext context) {
        this.provider = provider;
        this.config = config;
        this.session = session;
        this.registry = registry;
        this.executor = new ToolExecutor(registry, context);
    }

    public void run(String userInput, AgentEventHandler handler) {
        session.addUserMessage(userInput);
        StringBuilder firstText = new StringBuilder();
        AtomicReference<ToolCall> toolCallRef = new AtomicReference<>();

        try {
            provider.streamChat(new ChatRequest(config.model(), withAgentSystemPrompt(session.messages()), registry.toOpenAiToolsJson(), true), new StreamHandler() {
                @Override
                public void onDelta(String text) {
                    firstText.append(text);
                    handler.onEvent(AgentEvent.delta(text));
                }

                @Override
                public void onToolCall(ToolCall toolCall) {
                    toolCallRef.set(toolCall);
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onError(Exception error) {
                    handler.onEvent(AgentEvent.error(error.getMessage()));
                }
            });

            ToolCall toolCall = toolCallRef.get();
            if (toolCall == null || toolCall.name().isBlank()) {
                if (!firstText.isEmpty()) {
                    session.addAssistantMessage(firstText.toString());
                }
                handler.onEvent(AgentEvent.done());
                return;
            }

            handler.onEvent(AgentEvent.toolStart(toolCall.name()));
            ToolResult result = executor.execute(toolCall);
            handler.onEvent(AgentEvent.toolResult(result));
            session.addAssistantMessage("[工具调用] " + toolCall.name() + "\n参数：" + toolCall.argumentsJson());
            session.addUserMessage("[工具执行结果]\n" + result.toJson() + "\n请基于这个工具结果给出最终回复，不要继续调用工具。");

            StringBuilder finalReply = new StringBuilder();
            AtomicReference<ToolCall> secondToolCall = new AtomicReference<>();
            provider.streamChat(new ChatRequest(config.model(), session.messages(), "[]", false), new StreamHandler() {
                @Override
                public void onDelta(String text) {
                    finalReply.append(text);
                    handler.onEvent(AgentEvent.delta(text));
                }

                @Override
                public void onToolCall(ToolCall toolCall) {
                    secondToolCall.set(toolCall);
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onError(Exception error) {
                    handler.onEvent(AgentEvent.error(error.getMessage()));
                }
            });

            if (secondToolCall.get() != null) {
                handler.onEvent(AgentEvent.error("本阶段每次请求只支持一个工具调用，已拒绝后续工具请求。"));
            }
            if (!finalReply.isEmpty()) {
                session.addAssistantMessage(finalReply.toString());
            }
            handler.onEvent(AgentEvent.done());
        } catch (ProviderException error) {
            handler.onEvent(AgentEvent.error(error.getMessage()));
            handler.onEvent(AgentEvent.done());
        }
    }

    private List<ChatMessage> withAgentSystemPrompt(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage(ChatRole.SYSTEM, """
                你是 CN Code 的单工具 Agent。遇到读取文件、写文件、修改文件、执行命令、查找文件或搜索代码内容的请求时，必须调用可用工具，不要凭空猜测本地文件或命令结果。
                本阶段每次用户请求最多调用一个工具。工具执行后，你会收到工具结果；之后必须基于工具结果给出最终回答，不要继续调用工具。
                如果用户请求不需要本地工具，可以直接回答。
                """));
        result.addAll(messages);
        return result;
    }
}
