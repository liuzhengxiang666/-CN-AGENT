package cncode.agent;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.compact.CompactResult;
import cncode.compact.CompactTrackingState;
import cncode.compact.ContextCompactor;
import cncode.instruction.InstructionLoadResult;
import cncode.instruction.InstructionLoader;
import cncode.memory.MemoryExtractor;
import cncode.memory.MemoryManager;
import cncode.prompt.PlanModeReminder;
import cncode.prompt.PromptAssembler;
import cncode.prompt.PromptEnvironmentDetector;
import cncode.prompt.PromptOptions;
import cncode.prompt.SystemReminder;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.tool.ToolCall;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;
import cncode.toolresult.ApplyResult;
import cncode.toolresult.ContentReplacementState;
import cncode.toolresult.ReplacementRecordsIO;
import cncode.toolresult.ToolResultBudget;
import cncode.session.SessionStore;
import cncode.skill.ActiveSkillState;
import cncode.skill.InstallSkillTool;
import cncode.skill.LoadSkillTool;
import cncode.skill.SkillCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AgentLoop {
    private final ChatProvider provider;
    private final AppConfig config;
    private final ChatSession session;
    private final ToolRegistry registry;
    private final AgentLoopConfig loopConfig;
    private final StreamCollector streamCollector;
    private final String stablePrompt;
    private final Path workDir;
    private final Path sessionDir;
    private final SessionStore sessionStore;
    private final MemoryManager memoryManager;
    private final SkillCatalog skillCatalog;
    private final ActiveSkillState activeSkillState;
    private final LoadSkillTool loadSkillTool;
    private final InstallSkillTool installSkillTool;
    private final ContentReplacementState replacementState;
    private final CompactTrackingState compactTrackingState;
    private volatile AgentRunState state = AgentRunState.IDLE;

    public AgentLoop(ChatProvider provider, AppConfig config, ChatSession session) {
        this(provider, config, session, ToolRegistry.defaults(), AgentLoopConfig.defaults());
    }

    public AgentLoop(ChatProvider provider, AppConfig config, ChatSession session, ToolRegistry registry, AgentLoopConfig loopConfig) {
        this(provider, config, session, registry, loopConfig, new ContentReplacementState(), new CompactTrackingState());
    }

    public AgentLoop(ChatProvider provider, AppConfig config, ChatSession session, ToolRegistry registry, AgentLoopConfig loopConfig,
                     ContentReplacementState replacementState, CompactTrackingState compactTrackingState) {
        this(provider, config, session, registry, loopConfig, replacementState, compactTrackingState, null, null);
    }

    public AgentLoop(ChatProvider provider, AppConfig config, ChatSession session, ToolRegistry registry, AgentLoopConfig loopConfig,
                     ContentReplacementState replacementState, CompactTrackingState compactTrackingState,
                     SessionStore sessionStore, MemoryManager memoryManager) {
        this(provider, config, session, registry, loopConfig, replacementState, compactTrackingState, sessionStore, memoryManager, null, null);
    }

    public AgentLoop(ChatProvider provider, AppConfig config, ChatSession session, ToolRegistry registry, AgentLoopConfig loopConfig,
                     ContentReplacementState replacementState, CompactTrackingState compactTrackingState,
                     SessionStore sessionStore, MemoryManager memoryManager,
                     SkillCatalog skillCatalog, ActiveSkillState activeSkillState) {
        this.provider = provider;
        this.config = config;
        this.session = session;
        this.registry = registry;
        this.loopConfig = loopConfig;
        this.workDir = Path.of("").toAbsolutePath();
        this.sessionDir = workDir.resolve(".cncode").resolve("session");
        this.sessionStore = sessionStore;
        this.memoryManager = memoryManager;
        this.skillCatalog = skillCatalog;
        this.activeSkillState = activeSkillState;
        this.replacementState = replacementState == null ? new ContentReplacementState() : replacementState;
        this.compactTrackingState = compactTrackingState == null ? new CompactTrackingState() : compactTrackingState;
        if (skillCatalog != null && activeSkillState != null) {
            this.loadSkillTool = new LoadSkillTool(skillCatalog, activeSkillState, registry);
            this.installSkillTool = new InstallSkillTool(skillCatalog, registry);
            registry.register(loadSkillTool);
            registry.register(installSkillTool);
        } else {
            this.loadSkillTool = null;
            this.installSkillTool = null;
        }
        this.streamCollector = new StreamCollector(provider);
        this.stablePrompt = PromptAssembler.buildStablePrompt(new PromptOptions(
                readProjectInstructions(workDir),
                "",
                skillCatalog == null ? "" : skillCatalog.buildSummary(),
                memoryManager == null ? "" : memoryManager.promptSection()
        ));
    }

    public AgentCancellationToken run(String userInput, AgentEventHandler handler) {
        AgentCancellationToken cancellationToken = new AgentCancellationToken();
        run(userInput, handler, cancellationToken);
        return cancellationToken;
    }

    public void run(String userInput, AgentEventHandler handler, AgentCancellationToken cancellationToken) {
        state = AgentRunState.IDLE;
        session.addUserMessage(userInput);
        appendSession(session.messages().getLast());
        handler.onEvent(AgentEvent.userMessage(userInput));
        try {
            runLoop(handler, cancellationToken);
        } catch (ProviderException error) {
            state = AgentRunState.FAILED;
            emitProviderError(handler, error);
        } catch (Exception error) {
            state = AgentRunState.FAILED;
            handler.onEvent(AgentEvent.error("Agent Loop 执行失败：" + error.getMessage()));
        } finally {
            triggerMemoryExtraction();
            handler.onEvent(AgentEvent.done());
        }
    }

    public AgentRunState state() {
        return state;
    }

    private void runLoop(AgentEventHandler handler, AgentCancellationToken cancellationToken) throws ProviderException {
        for (int iteration = 1; iteration <= loopConfig.maxIterations(); iteration++) {
            if (cancellationToken.isCancelled()) {
                state = AgentRunState.CANCELLED;
                handler.onEvent(AgentEvent.cancelled("用户已取消当前 Agent Loop。"));
                return;
            }

            state = AgentRunState.RUNNING_MODEL;
            CollectedStream stream = collectModel(handler, iteration);
            if (!stream.errorMessage().isBlank()) {
                state = AgentRunState.FAILED;
                emitErrorOrTimeout(handler, stream.errorMessage());
                return;
            }
            if ("length".equalsIgnoreCase(stream.stopReason())) {
                state = AgentRunState.FAILED;
                handler.onEvent(AgentEvent.error("模型输出达到长度上限，请缩小任务或拆分请求。"));
                return;
            }

            if (stream.toolCalls().isEmpty()) {
                if (!stream.text().isBlank()) {
                    session.addAssistantMessage(stream.text());
                    appendSession(session.messages().getLast());
                }
                state = AgentRunState.COMPLETED;
                handler.onEvent(AgentEvent.turnComplete());
                handler.onEvent(AgentEvent.loopComplete());
                return;
            }

            if (cancellationToken.isCancelled()) {
                state = AgentRunState.CANCELLED;
                handler.onEvent(AgentEvent.cancelled("用户已取消，工具执行未启动。"));
                return;
            }

            state = AgentRunState.EXECUTING_TOOLS;
            session.addAssistantMessage(formatToolCalls(stream.text(), stream.toolCalls()));
            appendSession(session.messages().getLast());
            List<ToolResult> results = new StreamingToolExecutor(
                    currentToolRegistry(),
                    new ToolExecutionContext(workDir, loopConfig.toolTimeout(), loopConfig.maxOutputChars())
            ).executeAll(stream.toolCalls(), handler, loopConfig, cancellationToken);
            session.addUserMessage(formatToolResults(results));
            appendSession(session.messages().getLast());
            handler.onEvent(AgentEvent.turnComplete());

            if (cancellationToken.isCancelled()) {
                state = AgentRunState.CANCELLED;
                handler.onEvent(AgentEvent.cancelled("用户已取消，Agent Loop 不再进入下一轮。"));
                return;
            }
        }

        state = AgentRunState.FAILED;
        handler.onEvent(AgentEvent.error("Agent Loop 已达到最大轮数 " + loopConfig.maxIterations() + "，请拆分任务或缩小范围。"));
    }

    private CollectedStream collectModel(AgentEventHandler handler, int iteration) throws ProviderException {
        ApplyResult applied = ToolResultBudget.apply(session.messages(), sessionDir, replacementState);
        try {
            ReplacementRecordsIO.append(sessionDir, applied.newRecords());
        } catch (Exception ignored) {
        }
        try {
            CompactResult compact = ContextCompactor.manage(session, applied.apiMessages(), provider, config.model(), 120_000, compactTrackingState);
            if (compact.compacted()) {
                handler.onEvent(AgentEvent.compact(compact.message()));
                applied = ToolResultBudget.apply(session.messages(), sessionDir, replacementState);
            }
        } catch (Exception error) {
            handler.onEvent(AgentEvent.compact("上下文自动压缩失败，已保留原历史继续执行：" + error.getMessage()));
        }
        return streamCollector.collect(
                new ChatRequest(config.model(), withAgentLoopSystemPrompt(applied.apiMessages(), iteration), currentToolRegistry().toOpenAiToolsJson(), true),
                handler
        );
    }

    public CompactResult forceCompact() throws ProviderException {
        return ContextCompactor.forceCompact(session, provider, config.model(), 120_000);
    }

    private List<ChatMessage> withAgentLoopSystemPrompt(List<ChatMessage> messages, int iteration) {
        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage(ChatRole.SYSTEM, stablePrompt));
        result.add(SystemReminder.environment(PromptEnvironmentDetector.detect(workDir)));
        String activeSkills = activeSkillState == null ? "" : activeSkillState.buildContext();
        if (!activeSkills.isBlank()) {
            result.add(SystemReminder.message("Active Skills", activeSkills));
        }
        if (loopConfig.planOnly()) {
            result.add(SystemReminder.message("Plan 模式提醒", PlanModeReminder.build(iteration)));
        } else {
            result.add(SystemReminder.message("Do 模式提醒", "当前处于 Do 执行模式。可以按任务需要调用已授权工具。不要声称当前处于 Plan 模式；只有收到明确的 Plan 模式提醒时才按 Plan 模式行动。"));
        }
        result.addAll(messages);
        return result;
    }

    private ToolRegistry currentToolRegistry() {
        if (loadSkillTool == null) {
            return registry;
        }
        return registry.filtered(activeSkillState.toolFilter());
    }

    private String readProjectInstructions(Path root) {
        InstructionLoadResult result = new InstructionLoader(root).load();
        if (result.warnings().isEmpty()) {
            return result.content();
        }
        return (result.content() + "\n\n" + String.join("\n", result.warnings())).strip();
    }

    private void appendSession(ChatMessage message) {
        if (sessionStore == null || message == null) {
            return;
        }
        try {
            sessionStore.append(message);
        } catch (Exception ignored) {
        }
    }

    private void triggerMemoryExtraction() {
        if (memoryManager == null || state != AgentRunState.COMPLETED) {
            return;
        }
        List<ChatMessage> snapshot = session.messages();
        Thread.startVirtualThread(() -> {
            try {
                String memory = MemoryExtractor.extract(provider, config.model(), snapshot);
                memoryManager.appendExtracted(memory);
            } catch (Exception ignored) {
            }
        });
    }

    private void emitProviderError(AgentEventHandler handler, ProviderException error) {
        emitErrorOrTimeout(handler, error.getMessage());
    }

    private void emitErrorOrTimeout(AgentEventHandler handler, String message) {
        if (message != null && message.toLowerCase().contains("timeout")) {
            handler.onEvent(AgentEvent.timeout(message));
            return;
        }
        if (message != null && message.contains("超时")) {
            handler.onEvent(AgentEvent.timeout(message));
            return;
        }
        handler.onEvent(AgentEvent.error(message == null ? "未知错误" : message));
    }

    private String formatToolCalls(String assistantText, List<ToolCall> calls) {
        StringBuilder builder = new StringBuilder();
        if (assistantText != null && !assistantText.isBlank()) {
            builder.append(assistantText.strip()).append("\n\n");
        }
        for (ToolCall call : calls) {
            builder.append("<tool-call id=\"")
                    .append(escapeAttribute(call.id()))
                    .append("\" name=\"")
                    .append(escapeAttribute(call.name()))
                    .append("\">\n")
                    .append(call.argumentsJson())
                    .append("\n</tool-call>\n");
        }
        return builder.toString().strip();
    }

    private String formatToolResults(List<ToolResult> results) {
        StringBuilder builder = new StringBuilder("以下是工具执行结果。请基于这些结果继续任务；如果已经完成，请输出最终回复。\n\n");
        for (ToolResult result : results) {
            builder.append("<tool-result name=\"")
                    .append(escapeAttribute(result.toolName()))
                    .append("\" success=\"")
                    .append(result.success())
                    .append("\">\n")
                    .append(result.toJson())
                    .append("\n</tool-result>\n");
        }
        return builder.toString().strip();
    }

    private String escapeAttribute(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

}
