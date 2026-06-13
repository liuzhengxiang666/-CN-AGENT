package cncode.web;

import cncode.agent.AgentEvent;
import cncode.agent.AgentLoop;
import cncode.agent.AgentLoopConfig;
import cncode.chat.ChatMessage;
import cncode.chat.ChatSession;
import cncode.command.Command;
import cncode.command.CommandContext;
import cncode.command.CommandParser;
import cncode.command.CommandRegistry;
import cncode.compact.CompactTrackingState;
import cncode.config.AppConfig;
import cncode.mcp.McpConnectResult;
import cncode.memory.MemoryManager;
import cncode.permission.PermissionRequest;
import cncode.permission.PermissionResponse;
import cncode.permission.PermissionScope;
import cncode.provider.JsonUtil;
import cncode.provider.ChatProvider;
import cncode.session.SessionInfo;
import cncode.session.SessionRestoreResult;
import cncode.session.SessionStore;
import cncode.skill.ActiveSkillState;
import cncode.skill.SkillCatalog;
import cncode.skill.SkillDefinition;
import cncode.skill.SkillExecutionMode;
import cncode.skill.SkillExecutor;
import cncode.skill.SkillToolPolicy;
import cncode.tool.ToolRegistry;
import cncode.toolresult.ContentReplacementState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebChatServer {
    private final AppConfig config;
    private final ChatProvider provider;
    private final ChatSession session;
    private final PortFinder portFinder;
    private final BrowserLauncher browserLauncher;
    private final WebFileOpener fileOpener;
    private final ToolRegistry registry;
    private final McpConnectResult mcpStatus;
    private final SessionStore sessionStore;
    private final MemoryManager memoryManager;
    private final SkillCatalog skillCatalog = new SkillCatalog();
    private final ActiveSkillState activeSkillState = new ActiveSkillState();
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final ContentReplacementState replacementState = new ContentReplacementState();
    private final CompactTrackingState compactTrackingState = new CompactTrackingState();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean planOnly = false;
    private volatile PermissionRequest pendingPermission;
    private HttpServer server;
    private URI uri;

    public WebChatServer(AppConfig config, ChatProvider provider, ChatSession session) {
        this(config, provider, session, ToolRegistry.defaults(), new McpConnectResult(0, 0, java.util.List.of()));
    }

    public WebChatServer(AppConfig config, ChatProvider provider, ChatSession session, ToolRegistry registry, McpConnectResult mcpStatus) {
        this(config, provider, session, registry, mcpStatus, new PortFinder(), new BrowserLauncher());
    }

    WebChatServer(AppConfig config, ChatProvider provider, ChatSession session, PortFinder portFinder, BrowserLauncher browserLauncher) {
        this(config, provider, session, ToolRegistry.defaults(), new McpConnectResult(0, 0, java.util.List.of()), portFinder, browserLauncher);
    }

    WebChatServer(AppConfig config, ChatProvider provider, ChatSession session, ToolRegistry registry, McpConnectResult mcpStatus, PortFinder portFinder, BrowserLauncher browserLauncher) {
        this.config = config;
        this.provider = provider;
        this.session = session;
        this.portFinder = portFinder;
        this.browserLauncher = browserLauncher;
        this.fileOpener = new WebFileOpener(Path.of("").toAbsolutePath());
        this.registry = registry == null ? ToolRegistry.defaults() : registry;
        this.mcpStatus = mcpStatus == null ? new McpConnectResult(0, 0, java.util.List.of()) : mcpStatus;
        Path workDir = Path.of("").toAbsolutePath();
        this.sessionStore = new SessionStore(workDir, "web-" + System.currentTimeMillis());
        this.memoryManager = new MemoryManager(workDir);
        this.skillCatalog.loadCatalog(workDir, this.registry);
        registerSkillCommands();
    }

    public URI start() throws IOException {
        int port = portFinder.findAvailablePort();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "cncode-web");
            thread.setDaemon(false);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.createContext("/app.css", exchange -> writeText(exchange, 200, "text/css; charset=utf-8", WebAssets.css()));
        server.createContext("/app.js", exchange -> writeText(exchange, 200, "application/javascript; charset=utf-8", WebAssets.js()));
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/open-file", this::handleOpenFile);
        server.createContext("/api/permission", this::handlePermission);
        server.start();
        uri = URI.create("http://127.0.0.1:" + port + "/");
        return uri;
    }

    public void openBrowser() {
        if (!browserLauncher.open(uri)) {
            System.out.println("鑷姩鎵撳紑娴忚鍣ㄥけ璐ワ紝璇锋墜鍔ㄨ闂細" + uri);
        }
    }

    public URI uri() {
        return uri;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        writeText(exchange, 200, "text/html; charset=utf-8", WebAssets.html());
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        writeText(exchange, 200, "application/json; charset=utf-8", WebJson.status(config, session.messages().size(), registry.all().size(), planOnly, mcpStatus));
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            if (!busy.compareAndSet(false, true)) {
                writeSse(output, "error", WebJson.textData("message", "模型正在回复，请稍后再试。"));
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String message = WebJson.extractMessage(body);
                writeSse(output, "start", "{}");
                if (handleSlashCommand(output, message)) {
                    return;
                }
                new AgentLoop(provider, config, session, registry, currentLoopConfig(), replacementState, compactTrackingState, sessionStore, memoryManager, skillCatalog, activeSkillState).run(message, event -> {
                    try {
                        writeAgentEvent(output, event);
                    } catch (IOException error) {
                        throw new WebStreamException(error);
                    }
                });
            } catch (WebStreamException error) {
                throw error;
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "请求处理失败：" + error.getMessage()));
            } finally {
                busy.set(false);
            }
        } catch (WebStreamException error) {
            throw new IOException("鍐欏叆 SSE 澶辫触", error.getCause());
        }
    }

    private void handleOpenFile(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "application/json; charset=utf-8", "{\"success\":false,\"external\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path;
        try {
            path = JsonUtil.extractStringField(body, "path");
        } catch (Exception error) {
            writeText(exchange, 400, "application/json; charset=utf-8", "{\"success\":false,\"external\":false,\"message\":\"path 瀛楁瑙ｆ瀽澶辫触\"}");
            return;
        }
        boolean confirmedExternal = body.contains("\"confirmedExternal\":true");
        WebFileOpener.OpenFileResponse response = fileOpener.open(path, confirmedExternal);
        writeText(exchange, response.success() ? 200 : 400, "application/json; charset=utf-8", response.toJson());
    }

    private void handlePermission(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "application/json; charset=utf-8", "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }
        PermissionRequest request = pendingPermission;
        if (request == null || request.response().isDone()) {
            writeText(exchange, 400, "application/json; charset=utf-8", "{\"success\":false,\"message\":\"当前没有等待处理的权限请求\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String decision;
        try {
            decision = JsonUtil.extractStringField(body, "decision");
        } catch (Exception error) {
            decision = "deny";
        }
        PermissionScope scope = switch (decision == null ? "deny" : decision.toLowerCase()) {
            case "once" -> PermissionScope.ONCE;
            case "session" -> PermissionScope.SESSION;
            case "always" -> PermissionScope.ALWAYS;
            default -> PermissionScope.DENY;
        };
        request.response().complete(new PermissionResponse(scope));
        pendingPermission = null;
        writeText(exchange, 200, "application/json; charset=utf-8", "{\"success\":true,\"message\":\"权限选择已提交\"}");
    }

    private boolean handleSlashCommand(OutputStream output, String message) throws IOException {
        CommandParser.ParsedCommand parsed = CommandParser.parse(message);
        if (!parsed.command()) {
            return false;
        }
        String name = parsed.name().isBlank() ? "help" : parsed.name();
        String args = parsed.args();
        var command = commandRegistry.find(name);
        if (command.isEmpty()) {
            writeSse(output, "system", WebJson.textData("message", "Unknown command: /" + name + " - type /help to see available commands"));
            writeSse(output, "done", "{}");
            return true;
        }
        if ("skills".equals(command.get().name())) {
            handleSkillsCommand(output, args);
            writeSse(output, "done", "{}");
            return true;
        }
        if (command.get().type() == Command.CommandType.LOCAL_UI) {
            handleLocalUiCommand(output, command.get(), args);
            writeSse(output, "done", "{}");
            return true;
        }
        if (command.get().type() == Command.CommandType.PROMPT) {
            if (command.get().description().endsWith("[skill]") || skillCatalog.get(command.get().name()).isPresent()) {
                executeSkillCommand(output, command.get().name(), args);
                return true;
            }
            String prompt = commandRegistry.execute(command.get().name(), commandContext(args));
            new AgentLoop(provider, config, session, registry, currentLoopConfig(), replacementState, compactTrackingState, sessionStore, memoryManager, skillCatalog, activeSkillState).run(prompt, event -> {
                try {
                    writeAgentEvent(output, event);
                } catch (IOException error) {
                    throw new WebStreamException(error);
                }
            });
            return true;
        }
        if ("memory".equals(command.get().name()) && args.toLowerCase().startsWith("add ")) {
            appendMemory(output, args.substring(4).trim());
            writeSse(output, "done", "{}");
            return true;
        }
        if ("session".equals(command.get().name()) && args.toLowerCase().startsWith("restore ")) {
            restoreSession(output, args.substring("restore ".length()).trim());
            writeSse(output, "done", "{}");
            return true;
        }
        String result = commandRegistry.execute(command.get().name(), commandContext(args));
        writeSse(output, "system", WebJson.textData("message", result));
        writeSse(output, "done", "{}");
        return true;
    }

    private void handleLocalUiCommand(OutputStream output, Command command, String args) throws IOException {
        switch (command.name()) {
            case "plan" -> {
                planOnly = true;
                writeSse(output, "mode", "{\"planOnly\":true}");
                writeSse(output, "system", WebJson.textData("message", "Entered Plan mode. Read/search only; use /do to return to execution mode."));
            }
            case "do" -> {
                planOnly = false;
                writeSse(output, "mode", "{\"planOnly\":false}");
                writeSse(output, "system", WebJson.textData("message", "Entered Do mode. CN Code can execute the approved task."));
            }
            case "compact" -> forceCompact(output);
            case "clear" -> {
                session.replaceMessages(List.of());
                activeSkillState.clear();
                writeSse(output, "clear", "{}");
                writeSse(output, "system", WebJson.textData("message", "Chat history cleared."));
            }
            case "resume" -> {
                if (args == null || args.isBlank()) {
                    writeSse(output, "system", WebJson.textData("message", sessionSummary()));
                } else {
                    restoreSession(output, args.trim());
                }
            }
            default -> writeSse(output, "system", WebJson.textData("message", commandRegistry.execute(command.name(), commandContext(args))));
        }
    }

    private void forceCompact(OutputStream output) throws IOException {
        try {
            var result = new AgentLoop(provider, config, session, registry, currentLoopConfig(), replacementState, compactTrackingState, sessionStore, memoryManager, skillCatalog, activeSkillState).forceCompact();
            String status = result.message().isBlank() ? "No compactable context yet." : result.message();
            writeSse(output, "compact", WebJson.textData("message", status));
        } catch (Exception error) {
            writeSse(output, "error", WebJson.textData("message", "Manual compact failed: " + error.getMessage()));
        }
    }

    private CommandContext commandContext(String args) {
        Path workDir = Path.of("").toAbsolutePath();
        return new CommandContext(
                args,
                workDir,
                config::model,
                () -> planOnly ? "plan" : "do",
                () -> registry.all().size(),
                () -> new int[]{0, 0},
                this::memoryLines,
                () -> {
                    try {
                        memoryManager.clear();
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                },
                this::sessionSummary,
                () -> skillCatalog.list().stream().map(skill -> skill.meta().name()).toList()
        );
    }

    private void registerSkillCommands() {
        for (SkillDefinition skill : skillCatalog.list()) {
            if (commandRegistry.find(skill.meta().name()).isPresent()) {
                continue;
            }
            commandRegistry.register(
                    new Command(skill.meta().name(), skill.meta().description() + " [skill]", new String[0], Command.CommandType.PROMPT, false),
                    context -> skillCatalog.getFull(skill.meta().name()).map(SkillDefinition::promptBody).orElse("")
            );
        }
    }

    private void handleSkillsCommand(OutputStream output, String args) throws IOException {
        String safeArgs = args == null ? "" : args.strip();
        if (safeArgs.isBlank() || "list".equalsIgnoreCase(safeArgs)) {
            writeSse(output, "system", WebJson.textData("message", skillCatalog.listText()));
            return;
        }
        if ("reload".equalsIgnoreCase(safeArgs)) {
            try {
                skillCatalog.reload(registry);
                registerSkillCommands();
                writeSse(output, "system", WebJson.textData("message", "Skills reloaded.\n\n" + skillCatalog.listText()));
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "Skills reload failed: " + error.getMessage()));
            }
            return;
        }
        String lower = safeArgs.toLowerCase();
        if (lower.startsWith("info ")) {
            writeSse(output, "system", WebJson.textData("message", skillCatalog.detailText(safeArgs.substring(5).strip())));
            return;
        }
        writeSse(output, "system", WebJson.textData("message", "Usage: /skills [list|info <name>|reload]"));
    }

    private void executeSkillCommand(OutputStream output, String name, String args) throws IOException {
        SkillDefinition skill = skillCatalog.getFull(name).orElse(null);
        if (skill == null) {
            writeSse(output, "error", WebJson.textData("message", "Unknown skill: " + name));
            writeSse(output, "done", "{}");
            return;
        }
        try {
            new SkillToolPolicy().assertAllowedToolsExist(skill, registry);
            writeSse(output, "system", WebJson.textData("message", "skill(" + skill.meta().name() + ") Successfully loaded skill"));
            String request = args == null || args.isBlank()
                    ? "Use active skill /" + skill.meta().name() + " for the current task."
                    : args;
            if (skill.meta().mode() == SkillExecutionMode.FORK) {
                runForkSkill(output, skill, request);
                return;
            }
            activeSkillState.activate(skill.meta().name(), skill.promptBody(), skill.meta().allowedTools());
            new AgentLoop(provider, config, session, registry, currentLoopConfig(), replacementState, compactTrackingState, sessionStore, memoryManager, skillCatalog, activeSkillState).run(request, event -> {
                try {
                    writeAgentEvent(output, event);
                } catch (IOException error) {
                    throw new WebStreamException(error);
                }
            });
        } catch (WebStreamException error) {
            throw error;
        } catch (Exception error) {
            writeSse(output, "error", WebJson.textData("message", "Skill execution failed: " + error.getMessage()));
            writeSse(output, "done", "{}");
        }
    }

    private void runForkSkill(OutputStream output, SkillDefinition skill, String request) throws IOException {
        ChatSession forkSession = new ChatSession();
        forkSession.replaceMessages(forkSeed(skill));
        ActiveSkillState forkActive = new ActiveSkillState();
        forkActive.activate(skill.meta().name(), skill.promptBody(), skill.meta().allowedTools());
        new AgentLoop(provider, config, forkSession, registry, currentLoopConfig(), replacementState, compactTrackingState, null, memoryManager, skillCatalog, forkActive).run(request, event -> {
            try {
                writeAgentEvent(output, event);
            } catch (IOException error) {
                throw new WebStreamException(error);
            }
        });
        String summary = forkSession.messages().stream()
                .filter(message -> message.role().name().equals("ASSISTANT"))
                .map(ChatMessage::content)
                .reduce((first, second) -> second)
                .orElse("Fork skill completed without assistant summary.");
        String returned = new SkillExecutor().summarizeForkResult(skill.meta().name(), summary);
        session.addAssistantMessage(returned);
        writeSse(output, "system", WebJson.textData("message", returned));
    }

    private List<ChatMessage> forkSeed(SkillDefinition skill) {
        List<ChatMessage> parent = session.messages();
        return switch (skill.meta().forkContext()) {
            case FULL -> new ArrayList<>(parent);
            case RECENT -> parent.subList(Math.max(0, parent.size() - SkillExecutor.RECENT_COUNT), parent.size());
            case NONE -> List.of();
        };
    }

    private List<String> memoryLines() {
        String memories = memoryManager.load();
        if (memories.isBlank()) {
            return List.of();
        }
        return memories.lines().filter(line -> !line.isBlank()).toList();
    }

    private String sessionSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Current session: ").append(sessionStore.id()).append("\n")
                .append("Messages: ").append(session.messages().size()).append("\n")
                .append("File: ").append(sessionStore.file());
        try {
            List<SessionInfo> sessions = sessionStore.listSessions();
            if (!sessions.isEmpty()) {
                builder.append("\n\nSessions:");
                for (SessionInfo info : sessions) {
                    builder.append("\n")
                            .append(info.id())
                            .append(" - ")
                            .append(info.records())
                            .append(" messages - ")
                            .append(info.path());
                }
            }
        } catch (IOException error) {
            builder.append("\n\nSession list failed: ").append(error.getMessage());
        }
        return builder.toString();
    }

    private void appendMemory(OutputStream output, String memory) throws IOException {
        if (memory.isBlank()) {
            writeSse(output, "system", WebJson.textData("message", "Usage: /memory add <content>"));
            return;
        }
        try {
            memoryManager.append(memory);
            writeSse(output, "system", WebJson.textData("message", "Memory added: " + memory));
        } catch (Exception error) {
            writeSse(output, "error", WebJson.textData("message", "Memory add failed: " + error.getMessage()));
        }
    }

    private void restoreSession(OutputStream output, String id) throws IOException {
        SessionRestoreResult result = sessionStore.restore(id);
        if (result.success()) {
            session.replaceMessages(result.messages());
            writeSse(output, "system", WebJson.textData("message", "Session restored: " + id + ", messages: " + result.messages().size()));
        } else {
            writeSse(output, "error", WebJson.textData("message", result.message()));
        }
    }

    private boolean handleModeCommand(OutputStream output, String message) throws IOException {
        String command = message == null ? "" : message.trim();
        if ("/plan".equalsIgnoreCase(command)) {
            planOnly = true;
            writeSse(output, "mode", "{\"planOnly\":true}");
            writeSse(output, "system", WebJson.textData("message", "已进入 plan-only 模式：只允许读取、查找和搜索，写文件/改文件/执行命令会被拦截。输入 /do 恢复执行模式。"));
            writeSse(output, "done", "{}");
            return true;
        }
        if ("/do".equalsIgnoreCase(command)) {
            planOnly = false;
            writeSse(output, "mode", "{\"planOnly\":false}");
            writeSse(output, "system", WebJson.textData("message", "已恢复执行模式：工具系统可以按任务需要读写文件、修改文件和执行命令。输入 /plan 可再次进入计划模式。"));
            writeSse(output, "done", "{}");
            return true;
        }
        if ("/compact".equalsIgnoreCase(command) || "/c".equalsIgnoreCase(command)) {
            try {
                var result = new AgentLoop(provider, config, session, registry, currentLoopConfig(), replacementState, compactTrackingState).forceCompact();
                String status = result.message().isBlank() ? "当前没有可压缩的上下文。" : result.message();
                writeSse(output, "compact", WebJson.textData("message", status));
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "手动压缩失败：" + error.getMessage()));
            }
            writeSse(output, "done", "{}");
            return true;
        }
        if (command.equalsIgnoreCase("/memory") || command.toLowerCase().startsWith("/memory ")) {
            handleMemoryCommand(output, command);
            writeSse(output, "done", "{}");
            return true;
        }
        if (command.equalsIgnoreCase("/session") || command.toLowerCase().startsWith("/session ")) {
            handleSessionCommand(output, command);
            writeSse(output, "done", "{}");
            return true;
        }
        return false;
    }

    private void handleMemoryCommand(OutputStream output, String command) throws IOException {
        String args = command.length() <= "/memory".length() ? "" : command.substring("/memory".length()).trim();
        if (args.isBlank() || "list".equalsIgnoreCase(args)) {
            String memories = memoryManager.load();
            writeSse(output, "system", WebJson.textData("message", memories.isBlank() ? "当前没有记忆。" : memories));
            return;
        }
        if (args.toLowerCase().startsWith("add ")) {
            String memory = args.substring(4).trim();
            try {
                memoryManager.append(memory);
                writeSse(output, "system", WebJson.textData("message", "记忆已追加：" + memory));
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "记忆追加失败：" + error.getMessage()));
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args)) {
            try {
                memoryManager.clear();
                writeSse(output, "system", WebJson.textData("message", "记忆已清空。"));
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "记忆清空失败：" + error.getMessage()));
            }
            return;
        }
        writeSse(output, "system", WebJson.textData("message", "用法：/memory [list|add <内容>|clear]"));
    }

    private void handleSessionCommand(OutputStream output, String command) throws IOException {
        String args = command.length() <= "/session".length() ? "" : command.substring("/session".length()).trim();
        if (args.isBlank() || "info".equalsIgnoreCase(args)) {
            writeSse(output, "system", WebJson.textData("message", "当前会话：" + sessionStore.id() + "\n消息数：" + session.messages().size() + "\n文件：" + sessionStore.file()));
            return;
        }
        if ("list".equalsIgnoreCase(args)) {
            try {
                java.util.List<SessionInfo> sessions = sessionStore.listSessions();
                if (sessions.isEmpty()) {
                    writeSse(output, "system", WebJson.textData("message", "当前项目没有历史会话。"));
                    return;
                }
                StringBuilder builder = new StringBuilder();
                for (SessionInfo info : sessions) {
                    builder.append(info.id()).append(" - ").append(info.records()).append(" messages - ").append(info.path()).append("\n");
                }
                writeSse(output, "system", WebJson.textData("message", builder.toString().strip()));
            } catch (Exception error) {
                writeSse(output, "error", WebJson.textData("message", "会话列表读取失败：" + error.getMessage()));
            }
            return;
        }
        if (args.toLowerCase().startsWith("restore ")) {
            String id = args.substring("restore ".length()).trim();
            SessionRestoreResult result = sessionStore.restore(id);
            if (result.success()) {
                session.replaceMessages(result.messages());
                writeSse(output, "system", WebJson.textData("message", "会话恢复成功：" + id + "，消息数：" + result.messages().size()));
            } else {
                writeSse(output, "error", WebJson.textData("message", result.message()));
            }
            return;
        }
        writeSse(output, "system", WebJson.textData("message", "用法：/session [info|list|restore <id>]"));
    }

    private AgentLoopConfig currentLoopConfig() {
        return new AgentLoopConfig(10, Duration.ofSeconds(60), Duration.ofSeconds(30), 12000, planOnly);
    }

    private void writeAgentEvent(OutputStream output, AgentEvent event) throws IOException {
        switch (event.type()) {
            case USER_MESSAGE -> {
            }
            case THINKING_DELTA -> writeSse(output, "thinking", WebJson.textData("text", event.text()));
            case DELTA -> writeSse(output, "delta", WebJson.textData("text", event.text()));
            case TOOL_START -> writeSse(output, "tool_start", WebJson.textData("toolName", event.toolName()));
            case TOOL_RESULT -> writeSse(output, "tool_result", event.toolResult().toJson());
            case PERMISSION_REQUEST -> {
                pendingPermission = event.permissionRequest();
                writeSse(output, "permission_request", event.permissionRequest().toJson());
            }
            case TURN_COMPLETE -> writeSse(output, "turn_complete", "{}");
            case LOOP_COMPLETE -> writeSse(output, "loop_complete", "{}");
            case COMPACT -> writeSse(output, "compact", WebJson.textData("message", event.text()));
            case CANCELLED -> writeSse(output, "cancelled", WebJson.textData("message", event.text()));
            case TIMEOUT -> writeSse(output, "timeout", WebJson.textData("message", event.text()));
            case ERROR -> writeSse(output, "error", WebJson.textData("message", event.text()));
            case DONE -> writeSse(output, "done", "{}");
        }
    }

    private void writeText(HttpExchange exchange, int status, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void writeSse(OutputStream output, String event, String jsonData) throws IOException {
        output.write(WebJson.sse(event, jsonData).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static class WebStreamException extends RuntimeException {
        WebStreamException(Throwable cause) {
            super(cause);
        }
    }
}
