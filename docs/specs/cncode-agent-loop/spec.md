# CN Code Agent Loop 规格说明

## 1. 背景与目标

CN Code 当前已经具备 OpenAI-compatible 流式对话、Web UI、六个核心工具，以及第一版 `AgentLoop`。但上一版实现只完成了“多轮工具循环”的最小链路，还没有完整覆盖 ch04 Agent Loop 的关键设计：状态机终止判断、事件流抽象、读写分批执行、plan-only 模式、取消与超时。

本阶段目标是把 Agent Loop 做成可扩展的 ReAct 循环内核：

```text
一轮 = 调 LLM -> 收集响应 -> 有工具调用就执行 -> 回填结果 -> 下一轮
```

当模型没有工具调用、显式结束、用户取消、出现不可恢复错误或达到最大轮数时，循环终止。上层 Web/TUI/CLI 不直接关心内部细节，只消费 Agent 事件流。

本阶段参考提示词模板中的 ch04 Agent Loop，并结合 CN Code 当前 Java 21 结构落地。复杂 system prompt、完整权限系统、Hook 引擎、SubAgent、Team、Worktree、Context Compact 等后续章节能力仍不做。

## 2. 功能需求

### F1. ReAct 循环本体

Agent Loop 必须按 ReAct 范式运行：

1. 将用户输入写入会话。
2. 调用 LLM。
3. 流式接收文本、thinking、工具调用和结束信号。
4. 如果没有工具调用，写入最终助手回复并结束。
5. 如果有工具调用，执行工具并回填结果。
6. 进入下一轮，直到终止条件成立。

### F2. 状态机终止判断

Agent Loop 每轮结束必须判断“继续 / 终止”。

终止情形包括：

- 模型没有请求工具。
- 模型显式结束当前轮，例如 end_turn 或等价 stop reason。
- 达到最大轮数上限。
- 用户取消。
- Provider 或工具执行出现不可恢复错误。

继续情形包括：

- 本轮存在工具调用，并且未达到最大轮数。
- 本轮工具失败但失败结果已回填，允许模型下一轮调整。

### F3. 事件流输出

Agent Loop 对外以事件流暴露过程。

事件至少包含：

- 用户消息开始
- 模型 thinking 增量
- 模型文本增量
- 工具调用开始
- 工具结果
- 轮次完成
- 循环完成
- 取消
- 超时
- 错误

Web UI 当前可先显示文本、工具开始、工具结果、错误和完成；thinking、取消、超时事件可先以系统消息或安全忽略方式处理。

### F4. 工具分批执行

一轮响应里如果模型请求多个工具，Agent Loop 必须按工具类别分批：

- `READ` 工具是安全读类，可并发执行。
- `WRITE` 工具互斥，必须串行执行。
- `COMMAND` 工具互斥，必须串行执行。

结果回填顺序必须与模型工具调用顺序一致，即使只读工具并发完成顺序不同，也不能打乱回灌顺序。

### F5. Plan-only 模式

Agent Loop 提供 plan-only 开关。

开启后：

- 允许普通对话和 `READ` 工具。
- 拦截 `WRITE` 和 `COMMAND` 工具。
- 被拦截的工具返回结构化失败结果，提示当前处于 plan-only，不能执行写入或命令。
- 最终输出应是一份计划，交给用户审批。

本阶段实现最小可用 plan-only 和 Web 命令入口：

- 用户输入 `/plan` 后，当前 Web 会话进入 plan-only。
- 用户输入 `/do` 后，当前 Web 会话恢复正常执行模式。
- `/plan` 和 `/do` 本身不发送给模型，不进入普通对话历史。

本阶段不实现完整 Plan Mode 文案、计划文件存档或权限审批 UI。

### F6. 取消与超时

Agent Loop 必须能响应外部取消。

取消发生时：

- 不再发起新的 LLM 调用。
- 不再启动新的工具执行。
- 已完成的工具结果可以保留。
- 正在执行的命令类工具应尽量依赖现有超时结束。
- 发出取消事件或错误事件，让上层恢复 idle 状态。

Agent Loop 还必须具备超时边界：

- Provider 调用出现异常或长时间无响应时，返回错误事件。
- 工具执行使用已有 `ToolExecutionContext.timeout`。

### F7. 最大轮数限制

Agent Loop 必须有最大轮数限制。

默认最大轮数为 10。

达到上限仍未结束时，系统停止循环，输出最大轮数错误，并建议用户拆分任务。

### F8. 工具结果回填

每个工具执行完成后，必须把结构化 `ToolResult` 回填到对话历史。

回填内容至少包含：

- 工具名
- 是否成功
- 摘要
- 输出
- 错误信息

模型下一轮必须能看到这些结果，并基于结果继续或结束。

### F9. 拦截位

Agent Loop 在工具执行前后保留拦截位：

- pre-tool：未来用于权限检查、plan-only 拦截、hook 拦截。
- post-tool：未来用于 hook、日志、审计。

本阶段只使用 pre-tool 实现 plan-only 拦截，不实现完整权限策略和 Hook 引擎。

### F10. Web UI 接入

Web UI 的 `/api/chat` 使用 Agent Loop。

SSE 至少保持：

- `delta`
- `tool_start`
- `tool_result`
- `error`
- `done`

可新增：

- `thinking`
- `turn_complete`
- `loop_complete`
- `cancelled`
- `timeout`

前端对新增事件必须安全处理，不能因为不认识事件而崩溃。

### F11. StreamCollector

Agent Loop 的模型流式收集逻辑必须从 `AgentLoop.callModel()` 抽离为独立 `StreamCollector`。

`StreamCollector` 负责：

- 调用 Provider。
- 实时透传文本 delta。
- 实时透传 thinking delta。
- 累积完整 assistant 文本。
- 收集本轮所有工具调用。
- 收集 Provider 暴露的 stop reason。
- 收集错误信息。

`AgentLoop` 只消费 `CollectedStream`，不直接实现 Provider 回调细节。

### F12. stop_reason / end_turn

Provider 必须向上暴露明确的结束原因。

至少支持：

- `stop`
- `end_turn`
- `tool_calls`
- `length`
- `content_filter`
- 未知原因原样透传

OpenAI-compatible Provider 从 SSE 中的 `finish_reason` 提取 stop reason。

Agent Loop 根据 stop reason 判断终止：

- `tool_calls` 且存在工具调用：继续执行工具。
- `stop` / `end_turn` 且没有工具调用：正常完成。
- `length`：输出错误或提示输出长度达到上限。

## 3. 非功能需求

### N1. Java 21

继续使用 Java 21。

读类工具并发可以使用虚拟线程或普通 Executor，但不得阻塞 Web 服务器主线程。

### N2. 状态一致性

取消、超时、工具失败、Provider 失败都不能让会话进入半写乱序状态。

会话写入顺序必须保持：

1. 用户消息
2. 助手文本或工具调用
3. 工具结果
4. 下一轮助手回复

### N3. 并发安全边界

只有 `READ` 工具可并发。

`WRITE` 和 `COMMAND` 工具必须串行，避免同时修改文件或同时执行命令造成不可预测状态。

### N4. 可观测性

上层 UI 必须能看到每个关键阶段，而不是只看到最终回复。

工具执行失败、plan-only 拦截、取消和最大轮数停止都必须产生可读事件。

### N5. 保守实现

本阶段可以用最小 system prompt 跑通行为。

不为后续章节引入过多抽象。

## 4. 不做的事

本阶段明确不实现：

1. 不实现复杂 system prompt 组装。
2. 不实现完整权限策略。
3. 不实现权限审批 UI。
4. 不实现 Hook 引擎。
5. 不实现 Agent 作为工具递归调用。
6. 不实现 SubAgent。
7. 不实现 Team。
8. 不实现 Worktree。
9. 不实现 Context Compact。
10. 不实现 ToolSearch。
11. 不实现 AskUser。
12. 不实现 Deferred 工具发现。
13. 不实现计划文件存档。
14. 不实现完整 Anthropic tool use；本阶段仍优先 OpenAI-compatible/DeepSeek。

## 5. 验收标准

### A1. ReAct 多轮链路

用户请求“找到 AGENTS.md，然后读取它，总结里面的项目规则”时，模型可以先调用 `find_files`，再调用 `read_file`，最后输出总结。

### A2. 读写分批

当同一轮包含多个 `READ` 工具调用时，系统可以并发执行，并按原始工具调用顺序回填结果。

当同一轮包含 `WRITE` 或 `COMMAND` 工具时，它们必须串行执行。

### A3. Plan-only

开启 plan-only 后，读文件和搜索可以执行；写文件、改文件、执行命令会被拦截，并返回说明当前处于 plan-only 的结构化失败结果。

用户输入 `/plan` 后进入 plan-only。

用户输入 `/do` 后退出 plan-only。

### A4. 取消

用户取消当前请求后，Agent Loop 停止继续调用模型和新工具，并输出取消事件或错误事件。

### A5. 最大轮数

当 fake Provider 连续返回工具调用超过最大轮数时，Agent Loop 停止并输出最大轮数错误。

### A6. 普通聊天

普通问题不触发工具调用，仍能流式返回最终文本。

### A6a. StreamCollector

`AgentLoop` 不再包含 `callModel()` 的 Provider 回调细节；模型收集逻辑由独立 `StreamCollector` 完成。

### A6b. Stop Reason

fake Provider 或 OpenAI Provider 能把 `stop_reason` 暴露到 Agent Loop；`stop` 或 `end_turn` 能使循环正常结束。

### A7. Web UI

Web UI 能显示多次工具调用过程，每次工具都有开始和结果摘要；新增事件不会让前端报错。

### A8. 构建

`.\gradlew.bat check` 通过。

`.\gradlew.bat installDist` 通过。
