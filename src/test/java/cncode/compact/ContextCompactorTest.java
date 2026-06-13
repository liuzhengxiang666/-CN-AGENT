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

public class ContextCompactorTest {
    public static void main(String[] args) throws Exception {
        estimateTokensIsStableAndSafe();
        summaryPromptForbidsToolsTwice();
        formatSummaryDropsAnalysis();
        forceCompactReplacesHistoryAndDisablesTools();
        autoCompactSkipsBelowThreshold();
        autoCompactUsesEstimateViewAndCanTrigger();
        autoCompactFailureKeepsHistory();
        autoCompactCircuitBreakerStopsRepeatedFailures();
        manualCompactIgnoresCircuitBreaker();
    }

    private static void estimateTokensIsStableAndSafe() {
        if (ContextCompactor.estimateTokens(List.of()) != 0) {
            throw new AssertionError("empty token estimate should be zero");
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.USER, "hello"),
                new ChatMessage(ChatRole.ASSISTANT, "world"),
                new ChatMessage(ChatRole.USER, "<tool-result name=\"x\" success=\"true\">\nvalue\n</tool-result>")
        );
        int first = ContextCompactor.estimateTokens(messages);
        int second = ContextCompactor.estimateTokens(messages);
        if (first <= 0 || first != second) {
            throw new AssertionError("token estimate should be positive and stable: " + first + ", " + second);
        }
    }

    private static void summaryPromptForbidsToolsTwice() {
        String prompt = ContextCompactor.SUMMARY_SYSTEM_PROMPT;
        int first = prompt.indexOf("禁止调用任何工具");
        int last = prompt.lastIndexOf("禁止调用任何工具");
        if (first < 0 || last <= first || !prompt.contains("禁止执行命令") || !prompt.contains("禁止读写文件")) {
            throw new AssertionError("summary prompt must forbid tools, commands, and file IO at both ends");
        }
        for (String section : List.of("主要请求", "关键概念", "文件和代码", "错误与修复", "解决过程", "用户原话", "待办事项", "当前工作状态", "下一步")) {
            if (!prompt.contains(section)) {
                throw new AssertionError("summary prompt missing section: " + section);
            }
        }
        if (!prompt.contains("<analysis>") || !prompt.contains("<summary>")) {
            throw new AssertionError("summary prompt should require analysis and summary tags");
        }
    }

    private static void formatSummaryDropsAnalysis() {
        String formatted = ContextCompactor.formatCompactSummary("<analysis>draft</analysis>\n<summary>final summary</summary>");
        if (!"final summary".equals(formatted)) {
            throw new AssertionError("summary extraction failed: " + formatted);
        }
        String fallback = ContextCompactor.formatCompactSummary("<analysis>draft</analysis>\nplain summary");
        if (fallback.contains("<analysis>") || fallback.contains("draft")) {
            throw new AssertionError("analysis draft should be dropped in fallback extraction: " + fallback);
        }
    }

    private static void forceCompactReplacesHistoryAndDisablesTools() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("我要做工具系统");
        session.addAssistantMessage("已分析需求");
        CapturingSummaryProvider provider = new CapturingSummaryProvider();

        CompactResult result = ContextCompactor.forceCompact(session, provider, "fake", 1000);

        if (!result.compacted() || session.messages().size() < 3) {
            throw new AssertionError("manual compact did not replace history");
        }
        String history = session.messages().toString();
        if (!history.contains("[Compacted conversation summary]") || !history.contains("旧对话已被结构化摘要替换") || !history.contains("重新调用工具读取")) {
            throw new AssertionError("summary or boundary message missing: " + history);
        }
        if (history.contains("<analysis>") || history.contains("draft")) {
            throw new AssertionError("analysis draft should not be saved into history: " + history);
        }
        if (!result.message().contains("estimated tokens") || !result.message().contains(" -> ")) {
            throw new AssertionError("compact result should include token delta: " + result.message());
        }
        if (provider.lastRequest.allowTools() || !"[]".equals(provider.lastRequest.toolsJson())) {
            throw new AssertionError("summary request must disable tools");
        }
    }

    private static void autoCompactSkipsBelowThreshold() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("small");
        CountingSummaryProvider provider = new CountingSummaryProvider();

        CompactResult result = ContextCompactor.manage(session, provider, "fake", 120_000, new CompactTrackingState());

        if (result.compacted() || provider.calls != 0) {
            throw new AssertionError("auto compact should skip below threshold");
        }
    }

    private static void autoCompactUsesEstimateViewAndCanTrigger() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("short source history");
        CountingSummaryProvider provider = new CountingSummaryProvider();
        List<ChatMessage> estimateView = List.of(new ChatMessage(ChatRole.USER, "x".repeat(400)));

        CompactResult result = ContextCompactor.manage(session, estimateView, provider, "fake", 100, new CompactTrackingState());

        if (!result.compacted() || provider.calls != 1) {
            throw new AssertionError("auto compact should trigger from estimate view: " + result);
        }
        if (!session.messages().getFirst().content().contains("[Compacted conversation summary]")) {
            throw new AssertionError("auto compact should replace main history");
        }
    }

    private static void autoCompactFailureKeepsHistory() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("x".repeat(1000));
        List<ChatMessage> before = new ArrayList<>(session.messages());
        ChatProvider failing = (request, handler) -> {
            throw new ProviderException("boom");
        };

        try {
            ContextCompactor.manage(session, failing, "fake", 100, new CompactTrackingState());
            throw new AssertionError("auto compact should throw provider failure");
        } catch (ProviderException expected) {
            if (!session.messages().equals(before)) {
                throw new AssertionError("history should remain unchanged after auto compact failure");
            }
        }
    }

    private static void autoCompactCircuitBreakerStopsRepeatedFailures() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("x".repeat(1000));
        CompactTrackingState tracking = new CompactTrackingState();
        ChatProvider failing = (request, handler) -> {
            throw new ProviderException("boom");
        };
        for (int i = 0; i < ContextCompactor.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES; i++) {
            try {
                ContextCompactor.manage(session, failing, "fake", 100, tracking);
            } catch (ProviderException expected) {
            }
        }
        CompactResult skipped = ContextCompactor.manage(session, failing, "fake", 100, tracking);
        if (!skipped.message().contains("熔断")) {
            throw new AssertionError("auto compact circuit breaker did not trigger: " + skipped);
        }
    }

    private static void manualCompactIgnoresCircuitBreaker() throws Exception {
        ChatSession session = new ChatSession();
        session.addUserMessage("manual still works");
        CompactTrackingState tracking = new CompactTrackingState();
        for (int i = 0; i < ContextCompactor.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES; i++) {
            tracking.recordFailure();
        }

        CompactResult result = ContextCompactor.forceCompact(session, new CountingSummaryProvider(), "fake", 100);

        if (!result.compacted()) {
            throw new AssertionError("manual compact should ignore auto circuit breaker");
        }
    }

    private static class CapturingSummaryProvider extends CountingSummaryProvider {
        private ChatRequest lastRequest;

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            lastRequest = request;
            super.streamChat(request, handler);
        }
    }

    private static class CountingSummaryProvider implements ChatProvider {
        private int calls;

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            calls++;
            handler.onDelta("""
                    <analysis>draft</analysis>
                    <summary>
                    主要请求：实现上下文压缩
                    关键概念：工具结果落盘
                    文件和代码：待实现
                    错误与修复：暂无
                    解决过程：先写文档再编码
                    用户原话：我要做压缩
                    待办事项：接入 AgentLoop
                    当前工作状态：测试中
                    下一步：运行检查
                    </summary>
                    """);
            handler.onComplete();
        }
    }
}
