package cncode.app;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.config.ConfigException;
import cncode.config.ConfigLoader;
import cncode.mcp.McpConnectResult;
import cncode.mcp.McpManager;
import cncode.provider.ChatProvider;
import cncode.provider.ProviderException;
import cncode.provider.ProviderFactory;
import cncode.repl.ChatRepl;
import cncode.tool.ToolRegistry;
import cncode.tui.TuiChatApp;
import cncode.web.WebChatServer;

import java.io.IOException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            AppConfig config = new ConfigLoader().loadDefault();
            ChatProvider provider = new ProviderFactory().create(config);
            ChatSession session = new ChatSession();
            ToolRegistry registry = ToolRegistry.defaults();
            McpManager mcpManager = new McpManager(config.mcpServers());
            McpConnectResult mcpStatus = mcpManager.registerAllTools(registry);
            if (!mcpStatus.errors().isEmpty()) {
                System.out.println("MCP 部分 server 连接失败：" + String.join("; ", mcpStatus.errors()));
            }
            if (hasArg(args, "--web")) {
                WebChatServer server = new WebChatServer(config, provider, session, registry, mcpStatus);
                System.out.println("CN Code Web 服务启动中...");
                var uri = server.start();
                System.out.println("CN Code Web 已启动：" + uri);
                server.openBrowser();
                Thread.currentThread().join();
                return;
            }
            if (hasArg(args, "--repl")) {
                ChatRepl repl = new ChatRepl(provider, config, session, System.in, System.out);
                repl.run();
                return;
            }
            if (System.console() == null && !hasArg(args, "--tui")) {
                ChatRepl repl = new ChatRepl(provider, config, session, System.in, System.out);
                repl.run();
                return;
            }
            try {
                new TuiChatApp(provider, config, session).run();
            } catch (Exception error) {
                System.err.println("TUI 启动失败，已切换到基础 REPL：" + error.getMessage());
                ChatRepl repl = new ChatRepl(provider, config, session, System.in, System.out);
                repl.run();
            }
        } catch (ConfigException | ProviderException | IOException error) {
            System.err.println("启动失败：" + error.getMessage());
            System.exit(1);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Web 服务已停止。");
        }
    }

    private static boolean hasArg(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }
}
