package cncode.repl;

import cncode.chat.ChatSession;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ChatRepl {
    private final ChatProvider provider;
    private final AppConfig config;
    private final ChatSession session;
    private final InputStream input;
    private final PrintStream output;

    public ChatRepl(ChatProvider provider, AppConfig config, ChatSession session, InputStream input, PrintStream output) {
        this.provider = provider;
        this.config = config;
        this.session = session;
        this.input = input;
        this.output = output;
    }

    public void run() {
        output.println("CN Code 已启动。输入 exit 或 quit 退出。");
        try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8)) {
            while (true) {
                output.print("cncode> ");
                output.flush();
                if (!scanner.hasNextLine()) {
                    output.println();
                    return;
                }
                String userInput = scanner.nextLine().trim();
                if (userInput.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(userInput) || "quit".equalsIgnoreCase(userInput)) {
                    output.println("再见。");
                    return;
                }
                handleMessage(userInput);
            }
        }
    }

    private void handleMessage(String userInput) {
        session.addUserMessage(userInput);
        StringBuilder assistantReply = new StringBuilder();
        try {
            provider.streamChat(new ChatRequest(config.model(), session.messages()), new StreamHandler() {
                @Override
                public void onDelta(String text) {
                    output.print(text);
                    output.flush();
                    assistantReply.append(text);
                }

                @Override
                public void onComplete() {
                    output.println();
                    output.flush();
                }

                @Override
                public void onError(Exception error) {
                    output.println();
                    output.println("调用模型失败：" + error.getMessage());
                }
            });
            if (!assistantReply.isEmpty()) {
                session.addAssistantMessage(assistantReply.toString());
            }
        } catch (ProviderException error) {
            output.println("调用模型失败：" + error.getMessage());
        }
    }
}
