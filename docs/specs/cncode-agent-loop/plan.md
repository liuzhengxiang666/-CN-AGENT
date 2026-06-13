# CN Code Agent Loop 技术设计

## 1. 架构概览

新的 Agent Loop 是 CN Code 的 ReAct 内核：

```text
用户输入
  -> AgentLoop 状态机
  -> Provider 流式调用
  -> 收集文本/thinking/tool call
  -> 按类别执行工具批次
  -> 工具结果回填
  -> 下一轮或终止
```

上层 Web/TUI/CLI 只消费 `AgentEvent`，不直接管理工具执行和循环状态。

## 2. 状态机设计

### 状态

```text
IDLE
RUNNING_MODEL
EXECUTING_TOOLS
COMPLETED
CANCELLED
FAILED
```

### 每轮状态转移

1. `RUNNING_MODEL`：调用 Provider，收集文本、thinking、工具调用、stop reason。
2. 如果取消：转 `CANCELLED`。
3. 如果 Provider 失败：转 `FAILED`。
4. 如果没有工具调用或 stop reason 表示结束：转 `COMPLETED`。
5. 如果有工具调用：转 `EXECUTING_TOOLS`。
6. 工具执行完成并回填结果后，如果未超最大轮数：回到 `RUNNING_MODEL`。
7. 达到最大轮数：转 `FAILED`。

## 3. 模块划分

### agent

新增或调整：

- `AgentLoop`
- `AgentLoopConfig`
- `AgentRunState`
- `AgentCancellationToken`
- `AgentEvent`
- `AgentEventHandler`
- `StreamCollector`
- `CollectedStream`
- `StreamingToolExecutor`
- `ToolBatch`
- `ToolInterceptor`

职责：

- 管理 ReAct 状态机。
- 输出事件流。
- 收集 Provider 流式响应。
- 控制最大轮数。
- 响应取消。
- 执行工具分批。
- 提供工具执行前后拦截位。

### tool

复用：

- `Tool`
- `ToolCategory`
- `ToolCall`
- `ToolRegistry`
- `ToolExecutor`
- `ToolResult`
- `ToolExecutionContext`

要求：

- `ToolCategory.READ` 可并发。
- `ToolCategory.WRITE` 和 `ToolCategory.COMMAND` 串行。
- 工具输出继续受 `ToolExecutionContext.maxOutputChars` 限制。

### provider

复用：

- `ChatRequest`
- `StreamHandler`
- `OpenAiProvider`

增强：

- `StreamHandler` 可接收 thinking 事件，当前 Provider 没有 thinking 时可以不触发。
- `StreamHandler` 可接收 completion 事件中的 stop reason。
- `StreamHandler` 一轮内可触发多个 `onToolCall`。
- Provider 流式异常必须能被 Agent Loop 转成错误事件。

### web

调整：

- `/api/chat` 调用 `AgentLoop`。
- 用户断开连接或取消时触发 cancellation token。
- 前端安全处理新增 SSE 事件。

## 4. 核心接口设计

### AgentLoopConfig

```text
record AgentLoopConfig(
    int maxIterations,
    Duration modelIdleTimeout,
    Duration toolTimeout,
    int maxOutputChars,
    boolean planOnly
)
```

默认：

- `maxIterations = 10`
- `modelIdleTimeout = 60s`
- `toolTimeout = 30s`
- `maxOutputChars = 12000`
- `planOnly = false`

### AgentCancellationToken

```text
class AgentCancellationToken {
    void cancel()
    boolean isCancelled()
}
```

Web 请求断开或用户取消时调用 `cancel()`。

### AgentLoop

```text
class AgentLoop {
    AgentCancellationToken run(String userMessage, AgentEventHandler handler)
}
```

`run` 可以同步执行，也可以内部启动后台线程；关键是必须有取消入口，并在每轮和工具执行前检查取消状态。

### StreamCollector

```text
class StreamCollector {
    CollectedStream collect(ChatRequest request, AgentEventHandler handler)
}
```

### CollectedStream

```text
record CollectedStream(
    String text,
    List<ToolCall> toolCalls,
    String stopReason,
    String errorMessage
)
```

`StreamCollector` 是 Agent Loop 与 Provider 回调之间的边界。Agent Loop 不直接拼接 delta、不直接收集 tool call。

### AgentEvent

事件类型：

- `USER_MESSAGE`
- `THINKING_DELTA`
- `DELTA`
- `TOOL_START`
- `TOOL_RESULT`
- `TURN_COMPLETE`
- `LOOP_COMPLETE`
- `CANCELLED`
- `TIMEOUT`
- `ERROR`
- `DONE`

当前 Web UI 至少处理 `DELTA / TOOL_START / TOOL_RESULT / ERROR / DONE`，其他事件安全忽略或显示为系统消息。

### ToolInterceptor

```text
interface ToolInterceptor {
    ToolResult before(Tool tool, ToolCall call, AgentLoopConfig config)
    void after(Tool tool, ToolCall call, ToolResult result)
}
```

本阶段可不做完整接口类，但必须在执行器中保留 pre/post 扩展点。plan-only 拦截属于 pre-tool。

## 5. 工具分批设计

输入是一轮内的 `List<ToolCall>`。

分批规则：

1. 连续 `READ` 调用组成一个 read batch。
2. `WRITE` 或 `COMMAND` 各自形成单独串行 batch。
3. read batch 可以并发执行。
4. 串行 batch 按顺序执行。
5. 所有结果按原始调用顺序回填。

示例：

```text
READ A, READ B, WRITE C, READ D, COMMAND E
```

执行批次：

```text
[READ A + READ B 并发] -> [WRITE C 串行] -> [READ D] -> [COMMAND E]
```

回填顺序仍是：

```text
A, B, C, D, E
```

## 6. Plan-only 设计

`AgentLoopConfig.planOnly = true` 时：

- `READ` 工具正常执行。
- `WRITE` 和 `COMMAND` 工具在 pre-tool 阶段被拦截。
- 拦截结果为失败 `ToolResult`。
- 错误摘要建议为：`plan-only 已开启，当前只允许读取和搜索。`
- 回填给模型后，模型应输出计划而不是继续执行写入。

本阶段不做完整 `/plan` 命令；可以先通过构造参数或测试配置启用。

### Web 命令入口

Web 会话保存一个 `planOnly` 布尔状态。

- 用户输入 `/plan`：设置 `planOnly=true`，返回 system 事件说明已进入 plan-only，不调用模型。
- 用户输入 `/do`：设置 `planOnly=false`，返回 system 事件说明已恢复正常执行，不调用模型。
- 其他输入：创建 `AgentLoopConfig(..., planOnly)` 后运行 Agent Loop。

该状态只存在于当前 Web 服务进程内，不做持久化。

## 6.1 Stop Reason 设计

`StreamHandler.onComplete()` 增加带 stop reason 的默认重载，或调整为 `onComplete(String stopReason)`。

OpenAI-compatible Provider：

- `finish_reason == "tool_calls"`：收集工具调用，stop reason 为 `tool_calls`。
- `finish_reason == "stop"`：stop reason 为 `stop`。
- `finish_reason == "length"`：stop reason 为 `length`。
- 其他值原样透传。

Agent Loop：

- `tool_calls` 且工具调用非空：执行工具。
- `stop` / `end_turn` 且没有工具调用：完成。
- `length`：发出错误事件，提示输出达到模型长度限制。

## 7. 取消与超时设计

### 取消检查点

- 每轮开始前。
- Provider 调用前。
- 工具批次执行前。
- 每个工具执行前。
- 每轮结束后。

### 超时

- `modelIdleTimeout` 用于定义模型长时间无事件的边界；如果当前 Provider 不支持中途中断，至少要在异常或调用返回后发出错误。
- `toolTimeout` 透传给 `ToolExecutionContext`。
- `run_command` 继续使用工具参数和 context timeout。

## 8. 会话回填

当前 `ChatSession` 只有普通 role 文本，因此继续用标签包装：

```text
assistant:
<tool-call id="..." name="...">
{...}
</tool-call>

user:
<tool-result name="..." success="true">
{...ToolResult JSON...}
</tool-result>
```

要求：

- assistant 文本不丢。
- thinking 可不写入历史。
- tool-call 和 tool-result 都写入历史。
- 工具失败也写入历史。

## 9. 风险与缓解

- 多 tool call 流式解析不稳定：先用 fake Provider 测试 Agent Loop，再逐步加强 OpenAI Provider。
- 取消无法立即中断正在进行的 HTTP 请求：先保证取消后不启动下一轮，后续再增强 HTTP 层取消。
- plan-only 容易被模型绕过：工具执行器层强制拦截，而不是只靠提示词。
- 并发读工具可能打乱顺序：执行结果携带原始 index，统一排序后回填。
- `/plan` `/do` 与普通消息冲突：只有完全等于命令的输入会被拦截，其他文本照常发给模型。
- stop reason 在不同 Provider 中名字不同：内部保留原始字符串，并只对已知值做行为判断。
