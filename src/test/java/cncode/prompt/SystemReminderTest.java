package cncode.prompt;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

import java.nio.file.Path;

public class SystemReminderTest {
    public static void main(String[] args) {
        environmentReminderUsesSystemReminderTag();
    }

    private static void environmentReminderUsesSystemReminderTag() {
        PromptEnvironment env = PromptEnvironmentDetector.detect(Path.of("").toAbsolutePath());
        ChatMessage message = SystemReminder.environment(env);
        if (message.role() != ChatRole.SYSTEM) {
            throw new AssertionError("reminder should be system message");
        }
        String content = message.content();
        if (!content.contains("<system-reminder>") || !content.contains("工作目录") || !content.contains("操作系统")) {
            throw new AssertionError("environment reminder invalid: " + content);
        }
    }
}
