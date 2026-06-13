package cncode.compact;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.chat.ChatSession;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.ProviderException;
import cncode.provider.StreamHandler;

import java.util.ArrayList;
import java.util.List;

public final class ContextCompactor {
    public static final double AUTO_COMPACT_THRESHOLD = 0.80;
    public static final int MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES = 3;
    public static final int KEEP_RECENT_MESSAGES = 8;

    public static final String SUMMARY_SYSTEM_PROMPT = """
            你正在执行内部上下文压缩任务。禁止调用任何工具，禁止执行命令，禁止读写文件。
            请总结下面的对话历史。用户原始表达要尽量保留，不要改写最新用户请求。
            先输出 <analysis> 分析草稿，再输出 <summary> 正式摘要。系统只会保留 <summary> 内的内容。
            正式摘要必须包含这些固定小节：
            1. 主要请求
            2. 关键概念
            3. 文件和代码
            4. 错误与修复
            5. 解决过程
            6. 用户原话
            7. 待办事项
            8. 当前工作状态
            9. 下一步
            再次强调：本次任务只允许总结上下文，禁止调用任何工具，禁止执行命令，禁止读写文件。
            """;

    private ContextCompactor() {
    }

    public static CompactResult manage(ChatSession session, ChatProvider provider, String model, int contextWindow, CompactTrackingState tracking) throws ProviderException {
        return manage(session, session.messages(), provider, model, contextWindow, tracking);
    }

    public static CompactResult manage(ChatSession session, List<ChatMessage> estimateMessages, ChatProvider provider, String model, int contextWindow, CompactTrackingState tracking) throws ProviderException {
        int before = estimateTokens(estimateMessages);
        int safeWindow = contextWindow <= 0 ? 120_000 : contextWindow;
        if (before < safeWindow * AUTO_COMPACT_THRESHOLD) {
            return CompactResult.skipped(before);
        }
        if (tracking != null && tracking.consecutiveFailures() >= MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES) {
            return new CompactResult(false, before, before, "自动压缩已熔断：连续失败次数达到上限，跳过本轮自动压缩");
        }
        try {
            CompactResult result = forceCompact(session, provider, model, safeWindow);
            if (tracking != null) {
                tracking.recordSuccess();
            }
            return result;
        } catch (ProviderException error) {
            if (tracking != null) {
                tracking.recordFailure();
            }
            throw error;
        } catch (RuntimeException error) {
            if (tracking != null) {
                tracking.recordFailure();
            }
            throw error;
        }
    }

    public static CompactResult forceCompact(ChatSession session, ChatProvider provider, String model, int contextWindow) throws ProviderException {
        List<ChatMessage> original = session.messages();
        int before = estimateTokens(original);
        if (original.isEmpty()) {
            return CompactResult.skipped(0);
        }
        String serialized = serializeForSummary(original);
        String summary = requestSummary(provider, model, serialized);
        String finalSummary = formatCompactSummary(summary);
        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(new ChatMessage(ChatRole.USER, "[Compacted conversation summary]\n\n" + finalSummary));
        compacted.add(new ChatMessage(ChatRole.USER, boundaryMessage()));
        compacted.addAll(recentMessages(original));
        session.replaceMessages(compacted);
        int after = estimateTokens(session.messages());
        return CompactResult.compacted(before, after);
    }

    public static int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (ChatMessage message : messages) {
            chars += safeLength(message.role().name()) + safeLength(message.content()) + 12;
        }
        return Math.max(1, chars / 4);
    }

    public static String formatCompactSummary(String text) {
        String safe = text == null ? "" : text.trim();
        int start = safe.indexOf("<summary>");
        int end = safe.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return safe.substring(start + "<summary>".length(), end).trim();
        }
        int analysisStart = safe.indexOf("<analysis>");
        int analysisEnd = safe.indexOf("</analysis>");
        if (analysisStart >= 0 && analysisEnd > analysisStart) {
            return (safe.substring(0, analysisStart) + safe.substring(analysisEnd + "</analysis>".length())).trim();
        }
        return safe;
    }

    public static String boundaryMessage() {
        return """
                <system-reminder>
                旧对话已被结构化摘要替换。摘要可能不包含完整文件细节、命令输出或工具结果。
                如果需要精确代码、文件内容或完整工具输出，必须重新调用工具读取，不要根据摘要脑补不存在的代码或文件内容。
                </system-reminder>
                """.strip();
    }

    private static String requestSummary(ChatProvider provider, String model, String serialized) throws ProviderException {
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                new ChatMessage(ChatRole.USER, serialized)
        );
        StringBuilder builder = new StringBuilder();
        provider.streamChat(new ChatRequest(model, messages, "[]", false), new StreamHandler() {
            @Override
            public void onDelta(String text) {
                builder.append(text);
            }
        });
        if (builder.isEmpty()) {
            throw new ProviderException("上下文压缩失败：摘要为空。");
        }
        return builder.toString();
    }

    private static String serializeForSummary(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append("[").append(message.role()).append("]\n");
            String content = message.content();
            if (content.length() > 6_000) {
                content = content.substring(0, 6_000) + "\n... (message truncated for summary)";
            }
            builder.append(content).append("\n\n");
        }
        return builder.toString();
    }

    private static List<ChatMessage> recentMessages(List<ChatMessage> messages) {
        int from = Math.max(0, messages.size() - KEEP_RECENT_MESSAGES);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    private static int safeLength(String text) {
        return text == null ? 0 : text.length();
    }
}
