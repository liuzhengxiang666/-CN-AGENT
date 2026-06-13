package cncode.skill;

import cncode.chat.ChatMessage;
import cncode.tool.ToolRegistry;

import java.util.List;

public final class SkillExecutor {
    public static final int RECENT_COUNT = 5;

    public String renderPrompt(SkillDefinition skill, String args) {
        return substituteArguments(skill.promptBody(), args);
    }

    public String executeInline(SkillDefinition skill, String args, ActiveSkillState activeSkillState, ToolRegistry registry) {
        new SkillToolPolicy().assertAllowedToolsExist(skill, registry);
        String body = renderPrompt(skill, args);
        activeSkillState.activate(skill.meta().name(), body, skill.meta().allowedTools());
        return body;
    }

    public List<ChatMessage> buildForkSeed(ForkContextMode mode, List<ChatMessage> parent) {
        List<ChatMessage> safeParent = parent == null ? List.of() : parent;
        if (mode == ForkContextMode.FULL) {
            return List.copyOf(safeParent);
        }
        if (mode == ForkContextMode.RECENT) {
            int from = Math.max(0, safeParent.size() - RECENT_COUNT);
            return List.copyOf(safeParent.subList(from, safeParent.size()));
        }
        return List.of();
    }

    public String summarizeForkResult(String skillName, String result) {
        return "Skill " + SkillMeta.normalizeName(skillName) + " completed in isolated mode.\n\nSummary:\n" + (result == null ? "" : result.strip());
    }

    public String substituteArguments(String body, String args) {
        String safeBody = body == null ? "" : body;
        String safeArgs = args == null ? "" : args.strip();
        if (safeArgs.isBlank()) {
            return safeBody;
        }
        if (safeBody.contains("$ARGUMENTS")) {
            return safeBody.replace("$ARGUMENTS", safeArgs);
        }
        return safeBody.strip() + "\n\n## User Request\n" + safeArgs;
    }
}
