package cncode.agent;

import cncode.permission.PermissionCheckResult;
import cncode.permission.PermissionChecker;
import cncode.permission.PermissionDecision;
import cncode.permission.PermissionRequest;
import cncode.permission.PermissionResponse;
import cncode.permission.PermissionScope;
import cncode.tool.Tool;
import cncode.tool.ToolCall;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolExecutor;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class StreamingToolExecutor {
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final PermissionChecker permissionChecker;

    public StreamingToolExecutor(ToolRegistry registry, ToolExecutionContext context) {
        this(registry, context, new PermissionChecker(context.workspaceRoot()));
    }

    public StreamingToolExecutor(ToolRegistry registry, ToolExecutionContext context, PermissionChecker permissionChecker) {
        this.registry = registry;
        this.executor = new ToolExecutor(registry, context);
        this.permissionChecker = permissionChecker;
    }

    public List<ToolResult> executeAll(List<ToolCall> calls, AgentEventHandler handler) {
        return executeAll(calls, handler, AgentLoopConfig.defaults(), new AgentCancellationToken());
    }

    public List<ToolResult> executeAll(List<ToolCall> calls, AgentEventHandler handler, AgentLoopConfig config, AgentCancellationToken cancellationToken) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolBatch batch : partition(calls)) {
            if (cancellationToken.isCancelled()) {
                break;
            }
            List<IndexedResult> batchResults = batch.parallel()
                    ? executeParallel(batch.calls(), handler, config, cancellationToken)
                    : executeSerial(batch.calls(), handler, config, cancellationToken);
            batchResults.stream()
                    .sorted(Comparator.comparingInt(IndexedResult::index))
                    .map(IndexedResult::result)
                    .forEach(results::add);
        }
        return results;
    }

    List<ToolBatch> partition(List<ToolCall> calls) {
        List<ToolBatch> batches = new ArrayList<>();
        List<ToolBatch.IndexedToolCall> readBatch = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (categoryOf(call) == ToolCategory.READ) {
                readBatch.add(new ToolBatch.IndexedToolCall(i, call));
                continue;
            }
            flushReadBatch(batches, readBatch);
            batches.add(new ToolBatch(false, List.of(new ToolBatch.IndexedToolCall(i, call))));
        }
        flushReadBatch(batches, readBatch);
        return batches;
    }

    private void flushReadBatch(List<ToolBatch> batches, List<ToolBatch.IndexedToolCall> readBatch) {
        if (readBatch.isEmpty()) {
            return;
        }
        batches.add(new ToolBatch(readBatch.size() > 1, new ArrayList<>(readBatch)));
        readBatch.clear();
    }

    private ToolCategory categoryOf(ToolCall call) {
        return registry.find(call.name())
                .map(tool -> tool.category())
                .orElse(ToolCategory.COMMAND);
    }

    private List<IndexedResult> executeSerial(List<ToolBatch.IndexedToolCall> calls, AgentEventHandler handler, AgentLoopConfig config, AgentCancellationToken cancellationToken) {
        List<IndexedResult> results = new ArrayList<>();
        for (ToolBatch.IndexedToolCall indexedCall : calls) {
            if (cancellationToken.isCancelled()) {
                break;
            }
            results.add(executeOne(indexedCall, handler, config));
        }
        return results;
    }

    private List<IndexedResult> executeParallel(List<ToolBatch.IndexedToolCall> calls, AgentEventHandler handler, AgentLoopConfig config, AgentCancellationToken cancellationToken) {
        List<IndexedResult> results = new ArrayList<>();
        for (ToolBatch.IndexedToolCall call : calls) {
            handler.onEvent(AgentEvent.toolStart(call.call().name()));
        }
        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<IndexedResult>> futures = new ArrayList<>();
            for (ToolBatch.IndexedToolCall call : calls) {
                if (cancellationToken.isCancelled()) {
                    break;
                }
                futures.add(executorService.submit((Callable<IndexedResult>) () -> executeResult(call, config, handler)));
            }
            for (Future<IndexedResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception error) {
                    results.add(new IndexedResult(Integer.MAX_VALUE, ToolResult.failure("unknown", "工具执行异常", error.getMessage())));
                }
            }
        }
        results.stream()
                .sorted(Comparator.comparingInt(IndexedResult::index))
                .map(IndexedResult::result)
                .forEach(result -> handler.onEvent(AgentEvent.toolResult(result)));
        return results;
    }

    private IndexedResult executeOne(ToolBatch.IndexedToolCall indexedCall, AgentEventHandler handler, AgentLoopConfig config) {
        handler.onEvent(AgentEvent.toolStart(indexedCall.call().name()));
        return executeOneAfterStart(indexedCall, handler, config);
    }

    private IndexedResult executeOneAfterStart(ToolBatch.IndexedToolCall indexedCall, AgentEventHandler handler, AgentLoopConfig config) {
        IndexedResult indexedResult = executeResult(indexedCall, config, handler);
        handler.onEvent(AgentEvent.toolResult(indexedResult.result()));
        return indexedResult;
    }

    private IndexedResult executeResult(ToolBatch.IndexedToolCall indexedCall, AgentLoopConfig config, AgentEventHandler handler) {
        ToolResult result = preToolResult(indexedCall.call(), config);
        if (result == null) {
            result = permissionResult(indexedCall.call(), config, handler);
        }
        if (result == null) {
            result = executor.execute(indexedCall.call());
        }
        return new IndexedResult(indexedCall.index(), result);
    }

    private ToolResult preToolResult(ToolCall call, AgentLoopConfig config) {
        if (!config.planOnly()) {
            return null;
        }
        ToolCategory category = categoryOf(call);
        if (category == ToolCategory.READ) {
            return null;
        }
        return ToolResult.failure(
                call.name(),
                "plan-only 已开启，当前只允许读取和搜索。",
                "plan-only 模式禁止执行 " + category + " 工具。请先输出计划并等待用户确认。"
        );
    }

    private ToolResult permissionResult(ToolCall call, AgentLoopConfig config, AgentEventHandler handler) {
        Tool tool = registry.find(call.name()).orElse(null);
        if (tool == null) {
            return null;
        }
        PermissionCheckResult result = permissionChecker.check(tool, call, config.permissionMode(), config.planOnly());
        if (result.decision() == PermissionDecision.ALLOW) {
            return null;
        }
        if (result.decision() == PermissionDecision.DENY) {
            return ToolResult.failure(call.name(), "权限拒绝", result.reason());
        }

        PermissionRequest request = result.request();
        handler.onEvent(AgentEvent.permissionRequest(request));
        PermissionResponse response = request.await(config.permissionTimeout());
        if (response.scope() == PermissionScope.ONCE) {
            return null;
        }
        if (response.scope() == PermissionScope.SESSION) {
            permissionChecker.rememberSession(request);
            return null;
        }
        if (response.scope() == PermissionScope.ALWAYS) {
            permissionChecker.rememberAlways(request);
            return null;
        }
        return ToolResult.failure(call.name(), "用户拒绝", "用户拒绝或权限请求超时，已拒绝执行。");
    }

    private record IndexedResult(int index, ToolResult result) {
    }
}
