package cncode.skill;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

import java.util.ArrayList;
import java.util.List;

public class SkillExecutorTest {
    public static void main(String[] args) {
        SkillExecutor executor = new SkillExecutor();
        String replaced = executor.substituteArguments("Do $ARGUMENTS now", "tests");
        if (!"Do tests now".equals(replaced)) {
            throw new AssertionError("argument replacement failed: " + replaced);
        }
        String appended = executor.substituteArguments("Do work", "extra");
        if (!appended.contains("## User Request") || !appended.contains("extra")) {
            throw new AssertionError("argument append failed: " + appended);
        }
        List<ChatMessage> parent = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            parent.add(new ChatMessage(ChatRole.USER, "m" + i));
        }
        if (executor.buildForkSeed(ForkContextMode.NONE, parent).size() != 0) {
            throw new AssertionError("none should be empty");
        }
        if (executor.buildForkSeed(ForkContextMode.RECENT, parent).size() != 5) {
            throw new AssertionError("recent should keep five");
        }
        if (executor.buildForkSeed(ForkContextMode.FULL, parent).size() != 7) {
            throw new AssertionError("full should keep all");
        }
    }
}
