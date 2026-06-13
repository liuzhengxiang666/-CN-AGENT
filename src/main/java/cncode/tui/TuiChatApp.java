package cncode.tui;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TuiChatApp {
    private final ChatProvider provider;
    private final AppConfig config;
    private final ChatSession session;
    private final TuiState state;
    private final TuiRenderer renderer;
    private final ExecutorService executor;
    private Screen screen;

    public TuiChatApp(ChatProvider provider, AppConfig config, ChatSession session) {
        this(provider, config, session, new TuiState(), new TuiRenderer());
    }

    TuiChatApp(ChatProvider provider, AppConfig config, ChatSession session, TuiState state, TuiRenderer renderer) {
        this.provider = provider;
        this.config = config;
        this.session = session;
        this.state = state;
        this.renderer = renderer;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cncode-provider-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void run() throws IOException {
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
                .setForceTextTerminal(true)
                .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG_MOVE);
        Terminal terminal = terminalFactory.createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();
        screen.doResizeIfNecessary();
        try {
            renderer.render(screen, state, config);
            while (state.running()) {
                KeyStroke key = screen.pollInput();
                if (key != null) {
                    handleInput(key);
                    renderer.render(screen, state, config);
                    continue;
                }
                renderer.render(screen, state, config);
                sleepQuietly();
            }
        } finally {
            executor.shutdownNow();
            screen.stopScreen();
            terminal.close();
        }
    }

    private void handleInput(KeyStroke key) {
        if (key.getKeyType() == KeyType.MouseEvent && key instanceof MouseAction mouseAction) {
            handleMouse(mouseAction);
            return;
        }
        switch (key.getKeyType()) {
            case Character -> state.insertCharacter(key.getCharacter());
            case Enter -> {
                if (key.isCtrlDown()) {
                    state.insertNewline();
                } else {
                    sendInput();
                }
            }
            case Backspace -> state.backspace();
            case Delete -> state.delete();
            case ArrowLeft -> {
                if (key.isCtrlDown()) {
                    state.moveTextLeft();
                } else {
                    state.moveCursorLeft();
                }
            }
            case ArrowRight -> {
                if (key.isCtrlDown()) {
                    state.moveTextRight(maxHorizontalOffset());
                } else {
                    state.moveCursorRight();
                }
            }
            default -> {
            }
        }
    }

    private int maxHorizontalOffset() {
        if (screen == null) {
            return 12;
        }
        return Math.max(0, screen.getTerminalSize().getColumns() / 3);
    }

    private void handleMouse(MouseAction mouseAction) {
        MouseActionType type = mouseAction.getActionType();
        if (type == MouseActionType.SCROLL_UP) {
            state.scrollUp();
        } else if (type == MouseActionType.SCROLL_DOWN) {
            state.scrollDown();
        }
    }

    private void sendInput() {
        if (state.busy()) {
            state.addMessage(TuiMessageRole.SYSTEM, "模型正在回复，请等待当前回复完成。");
            state.scrollToBottom();
            return;
        }
        String input = state.consumeInput().trim();
        if (input.isEmpty()) {
            return;
        }
        if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
            state.stop();
            return;
        }
        state.addMessage(TuiMessageRole.USER, input);
        state.scrollToBottom();
        session.addUserMessage(input);
        TuiMessage assistantMessage = state.addAssistantPlaceholder();
        StringBuilder assistantReply = new StringBuilder();
        state.setBusy(true);

        executor.submit(() -> {
            try {
                provider.streamChat(new ChatRequest(config.model(), session.messages()), new StreamHandler() {
                    @Override
                    public void onDelta(String text) {
                        synchronized (state) {
                            state.appendTo(assistantMessage, text);
                            state.scrollToBottom();
                        }
                        assistantReply.append(text);
                    }

                    @Override
                    public void onComplete() {
                        if (!assistantReply.isEmpty()) {
                            session.addAssistantMessage(assistantReply.toString());
                        }
                        state.setBusy(false);
                        state.scrollToBottom();
                    }

                    @Override
                    public void onError(Exception error) {
                        state.addMessage(TuiMessageRole.SYSTEM, "调用模型失败：" + error.getMessage());
                        state.setBusy(false);
                        state.scrollToBottom();
                    }
                });
            } catch (ProviderException error) {
                state.addMessage(TuiMessageRole.SYSTEM, "调用模型失败：" + error.getMessage());
                state.setBusy(false);
                state.scrollToBottom();
            }
        });
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            state.stop();
        }
    }
}
