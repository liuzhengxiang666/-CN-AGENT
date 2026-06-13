package cncode.prompt;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

public final class SystemReminder {
    private SystemReminder() {
    }

    public static ChatMessage environment(PromptEnvironment env) {
        return message("环境信息", """
                工作目录：%s
                操作系统：%s
                Shell：%s
                当前时间：%s
                Git 仓库：%s
                Git 分支：%s
                Git 状态：%s
                """.formatted(
                env.workDir(),
                env.os(),
                env.shell(),
                env.dateTime(),
                env.gitRepo() ? "是" : "否",
                env.gitBranch().isBlank() ? "(无)" : env.gitBranch(),
                env.gitStatus().isBlank() ? "(干净或不可用)" : env.gitStatus()
        ));
    }

    public static ChatMessage message(String title, String body) {
        return new ChatMessage(ChatRole.SYSTEM, """
                <system-reminder>
                %s

                %s
                </system-reminder>
                """.formatted(title == null ? "系统提醒" : title, body == null ? "" : body.strip()).strip());
    }
}
