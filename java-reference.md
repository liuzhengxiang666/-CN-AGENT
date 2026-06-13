# CN Code Java 版参考文档

> 从 `Vibe Coding提示词复制.md` 的各章 `### Java` 小节整理而来。
> 已把原项目名 `MewCode` 统一替换为 `CN Code`，包名/路径里的 `mewcode` 统一替换为 `cncode`，用于 CN Code 项目实现参考。

## 目录

- [ch02: 让 AI 开口说话 Spec](#ch02-让-ai-开口说话-spec)
- [ch03: 工具系统 Spec](#ch03-工具系统-spec)
- [ch04: Agent Loop Spec](#ch04-agent-loop-spec)
- [ch05: System Prompt 设计 Spec](#ch05-system-prompt-设计-spec)
- [ch06: 权限系统 Spec](#ch06-权限系统-spec)
- [ch07: MCP Protocol Spec](#ch07-mcp-protocol-spec)
- [ch08: 上下文管理 Spec](#ch08-上下文管理-spec)
- [ch09: 记忆系统 Spec](#ch09-记忆系统-spec)
- [ch10: Slash 命令系统 Spec（Java 版）](#ch10-slash-命令系统-spec-java-版)
- [ch11: Skills 系统 Spec](#ch11-skills-系统-spec)
- [ch12: Hook 系统 Spec](#ch12-hook-系统-spec)
- [ch13: SubAgent Spec（Java 版）](#ch13-subagent-spec-java-版)
- [ch14: Worktree Spec（Java 版）](#ch14-worktree-spec-java-版)
- [ch15: AgentTeam Spec](#ch15-agentteam-spec)


# ch02: 让 AI 开口说话 Spec

## 1. 背景

Agent 落地的第一步是让上层（Agent Loop / TUI / SubAgent）能用同一套接口和 LLM 收发，不必各自面对 SSE 流、Extended Thinking 签名回传、Provider 间消息差异。本章把 LLM 通信、流式响应、Extended Thinking、Token 统计以及两层消息模型封装到 `com.cncode.llm` 与 `com.cncode.conversation`，是 ch03+ 工具循环的前置依赖。

Java 版与 Go 版的核心架构一致，差异主要在惯例：用 `sealed interface + record` 替换 Go 的 `interface + struct`，用 `BlockingQueue<StreamEvent>` 替换 Go 的 `chan StreamEvent + chan error`（Error 作为一种事件入队），用 `Thread.startVirtualThread` 替换 goroutine，用 `LlmException` 子类替换 Go 的 error 类型断言。

## 2. 目标

交付统一的 `LlmClient` 流式接口和两个内置 Provider 实现（`AnthropicClient`、`OpenAiClient` Responses API），加上 `ConversationManager` 两层消息模型（内部带 thinking / tool use / tool result 的 `Message`，序列化到具体 Provider 的请求体）。上层（Agent、TUI 装配点、AgentTool、ContextCompactor、TeamManager）拿一个 `LlmClient` 就能跑，不再触碰 SSE 细节。

## 3. 功能需求

- F1: `LlmClient` 统一暴露流式接口，输入是会话管理器和工具 schema，输出是 `BlockingQueue<StreamEvent>`，错误作为 `StreamEvent.Error` 入队。
- F2: 客户端通过接口内置静态工厂方法 `LlmClient.create(cfg, systemPrompt)` 按 Provider Protocol 路由到 Anthropic 或 OpenAI 实现，未知 protocol 抛 `IllegalArgumentException`。
- F3: 事件流覆盖 8 种信号：`TextDelta` / `ThinkingDelta` / `ThinkingComplete`（含签名）/ `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd`（含 stop reason 与 token 用量）/ `Error`。所有事件用 `sealed interface` + `record` 收口，`switch` 模式匹配时编译器保证穷尽。
- F4: Anthropic 客户端基于手写 `HttpClient` + SSE 解析，支持 Extended Thinking 两种模式：高版本模型（opus-4-6 / sonnet-4-6）走 Adaptive Thinking，低版本回退到固定 budget 的 Enabled Thinking，能力判断由 `ModelResolver.supportsAdaptiveThinking` 完成。
- F5: OpenAI 客户端基于 Responses API（非 Chat Completions），支持把 `reasoning_summary_text.delta/done` 还原成 `ThinkingDelta` / `ThinkingComplete` 事件，让上层看到的事件形状和 Anthropic 一致。
- F6: 两个客户端都通过 `HttpRequest.timeout(5min)` + `sendAsync().get(90s)` 兜底 SDK / 网络静默阻塞，HTTP 非 200 状态走错误分类后抛 `LlmException`。
- F7: 错误分类有 5 类：基类 `LlmException` 以及 4 个静态嵌套子类：`AuthenticationException`、`RateLimitException`（带 `retryAfter`）、`ContextTooLongException`、`NetworkException`。各客户端把 HTTP 错误归类到这 5 类之一，上层只面对统一异常。
- F8: `Message` 是可变类（mutable POJO），字段含 role / content / thinkingBlocks / toolUses / toolResults；`ThinkingBlock` / `ToolUseBlock` / `ToolResultBlock` 是不可变 `record`。所有写操作走 `ConversationManager` 方法，外部通过 `getMessages()` 拿到 `List.copyOf` 的只读视图。
- F9: `ConversationManager` 提供 `serialize(protocol)` 按 Protocol 序列化（`serializeAnthropic` / `serializeOpenAI`），序列化时不丢字段（thinking signature、tool arguments、tool result isError 都要原样回到下一轮请求）。
- F10: `ConversationManager.addSystemReminder(content)` 把内容包成 `<system-reminder>\n{content}\n</system-reminder>` 作为 user 消息追加，供 ch06 Plan Mode、ch08 Compact、ch09 Memory 复用。
- F11: `ModelResolver` 暴露 `ALIASES` 短名映射（haiku / sonnet / opus → 具体模型 ID）和 `resolve(model)` / `supportsAdaptiveThinking(model)` / `supportsThinking(model)` 三个静态方法，供 ch13 SubAgent 切模型。

## 4. 非功能需求

- N1: 事件队列 `LinkedBlockingQueue<StreamEvent>(64)` 有缓冲，SSE 读取与事件分发用独立虚拟线程解耦，事件写入 `queue.put()` 时不阻塞主消费者。
- N2: 调用方通过 `Thread.interrupt()` 取消（如 TUI ctrl+c）时，SSE 读循环检测到中断并清理；Agent Loop 侧用 `poll(30s, TimeUnit.SECONDS)` 兜底，超时即 `Stream timeout` 退出。
- N3: HTTP 请求设置 5 分钟超时 + 90 秒连接超时，避免任何一路静默阻塞拖死整个 agent loop。
- N4: 序列化层不丢字段（thinking signature / tool arguments / tool result isError 全部往返保留），Anthropic 把 thinking + text + tool_use 合并到同一条 assistant content 数组里。
- N5: `ConversationManager` 不加锁——单消费者模型，调用方（Agent Loop 单线程顺序追加）负责串行化；`getMessages()` 返回 `List.copyOf` 不可变视图。

## 5. 设计概要

- 核心数据结构:
 - `LlmClient`（接口 + 静态工厂方法 `create()`）
 - `StreamEvent` sealed interface + 8 个 record
 - `LlmException` 基类 + 4 个静态嵌套子类
 - `ModelResolver`（含 `ALIASES` Map 与三个静态方法）
 - `ConversationManager`（私有 `List<Message> history`）
 - `Message`（可变 POJO）+ `ThinkingBlock` / `ToolUseBlock` / `ToolResultBlock`（不可变 record）
- 主流程（每轮 LLM 请求）:
 1. `Agent.agentLoop` 调 `client.stream(conv, tools)`，拿到 `BlockingQueue<StreamEvent>`
 2. 客户端把 `ConversationManager.serialize(protocol)` 序列化成请求体，调 `HttpClient.sendAsync`
 3. 启动虚拟线程读 SSE，主线程 `queue.poll(30s)` 消费事件
 4. 按 SSE 事件类型 `queue.put()` 对应 `StreamEvent` record
 5. 流结束 put `StreamEnd`；异常经 `classifyHttpError` 分类后 put `StreamEvent.Error`
- 调用链（模块层级）:
 - TUI 装配 → `LlmClient.create(provider, systemPrompt)` → 传给 `new Agent(client, registry, protocol)`
 - Agent loop → `LlmClient.stream` → `switch (event)` 模式匹配消费 → 写回 `ConversationManager`
 - `AgentTool` / `ContextCompactor` / `TeamManager` worker / `MemoryManager` 复用同一 `LlmClient` 接口
- 与其他模块的交互:
 - 依赖 `com.cncode.config.ProviderConfig`（Provider 配置、API key、token 上限）
 - 被 `com.cncode.agent`、`com.cncode.subagent`、`com.cncode.compact`、`com.cncode.tui`、`com.cncode.teams`、`com.cncode.memory` 调用
 - 与 `com.cncode.tool` 解耦：`stream` 只接 `List<Map<String, Object>>` schema，工具注册中心由 `ToolRegistry` 提供

## 6. Out of Scope

- 多模态输入（image / PDF）的请求体构造：当前 `Message.content` 仅 `String`，未来章节再扩
- 自动重试与指数退避：rate limit 的重试在 ch04 Agent Loop 处理（`Thread.sleep(5000)`），不在 ch02 范围
- Provider 抽象细分（Bedrock / Vertex / Azure-OpenAI）：当前只支持原生 Anthropic 与原生 OpenAI Responses
- Prompt caching / Cache breakpoints：目标设计已有，本仓库暂未实现
- 官方 SDK 接入：当前手写 `HttpClient` + Jackson 解析，未来可替换为 `anthropic-java` / `openai-java` SDK

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch02: 让 AI 开口说话 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。

## T1: 定义 `LlmClient` 接口与静态工厂方法
- 影响文件: `src/main/java/com/cncode/llm/LlmClient.java`
- 依赖任务: 无
- 完成标准: `src/main/java/com/cncode/llm/LlmClient.java:10-20` 声明 `LlmClient` 接口（含 `stream(conv, tools)` 单实例方法）；`src/main/java/com/cncode/llm/LlmClient.java:14-19` 实现 `static create(ProviderConfig cfg, String systemPrompt)`，用 switch 表达式按 protocol 路由，未知 protocol 抛 `IllegalArgumentException`。

## T2: 实现流式事件 sealed interface + records
- 影响文件: `src/main/java/com/cncode/llm/StreamEvent.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/cncode/llm/StreamEvent.java:5-22` 定义 `sealed interface StreamEvent` + 8 个 record（`TextDelta` / `ThinkingDelta` / `ThinkingComplete` / `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd` / `Error`），全部用 `implements StreamEvent`。

## T3: 实现异常分层（`LlmException` + 4 个嵌套子类）
- 影响文件: `src/main/java/com/cncode/llm/LlmException.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/cncode/llm/LlmException.java:3-41` 定义 `LlmException extends RuntimeException`，含双构造函数；`:13-17` `AuthenticationException`；`:19-28` `RateLimitException`（含 `retryAfter` 字段与 getter）；`:30-34` `ContextTooLongException`；`:36-40` `NetworkException`。

## T4: 实现 Anthropic 客户端
- 影响文件: `src/main/java/com/cncode/llm/AnthropicClient.java`
- 依赖任务: T1, T2, T3, T6, T7
- 完成标准:
 - `src/main/java/com/cncode/llm/AnthropicClient.java:31-46` 构造函数读取 `cfg.resolvedApiKey()`，空时抛 `AuthenticationException`，model 经 `ModelResolver.resolve` 解析；
 - `src/main/java/com/cncode/llm/AnthropicClient.java:52-68` `stream()` 创建 `LinkedBlockingQueue<>(64)` + `Thread.startVirtualThread` 调 `doStream`；
 - `src/main/java/com/cncode/llm/AnthropicClient.java:80-86` thinking=true 时根据 `ModelResolver.supportsAdaptiveThinking` 切换 adaptive / enabled（budget = maxTokens - 1）；
 - `src/main/java/com/cncode/llm/AnthropicClient.java:132-234` SSE 主循环 `switch(eventType)` 处理 `message_start` / `content_block_start`（识别 thinking / tool_use）/ `content_block_delta`（识别 `thinking_delta` / `signature_delta` / `text_delta` / `input_json_delta`）/ `content_block_stop` / `message_delta`；
 - `src/main/java/com/cncode/llm/AnthropicClient.java:236-238` 流结束 `queue.put(new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens))`；
 - `src/main/java/com/cncode/llm/AnthropicClient.java:245-255` `classifyHttpError(status, body)` 按 413 / `prompt is too long` / 401 / 429 / default 分支返回 `LlmException` 子类。

## T5: 实现 OpenAI Responses 客户端
- 影响文件: `src/main/java/com/cncode/llm/OpenAiClient.java`
- 依赖任务: T1, T2, T3, T7
- 完成标准:
 - `src/main/java/com/cncode/llm/OpenAiClient.java:30-45` 构造函数读取 API key（空抛 `AuthenticationException`）；
 - `src/main/java/com/cncode/llm/OpenAiClient.java:51-67` `stream()` 与 Anthropic 同形；
 - `src/main/java/com/cncode/llm/OpenAiClient.java:84-86` thinking=true 时设置 `reasoning: { effort: "high", summary: "detailed" }`；
 - `src/main/java/com/cncode/llm/OpenAiClient.java:125-203` SSE 主循环 `switch(type)` 处理 `response.output_text.delta` / `response.output_item.added`（function_call / reasoning）/ `response.reasoning_summary_text.delta/done` / `response.function_call_arguments.delta/done` / `response.completed`；
 - `src/main/java/com/cncode/llm/OpenAiClient.java:211-222` `classifyHttpError` 覆盖 413 / 400+`context_length_exceeded` / 401 / 429 / default。

## T6: 实现 `ModelResolver`（短名映射 + 能力判断）
- 影响文件: `src/main/java/com/cncode/llm/ModelResolver.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/cncode/llm/ModelResolver.java:7-11` 定义 `ALIASES` Map（haiku / sonnet / opus）；`:13-15` `resolve(model)` 返回别名解析后的具体 ID；`:17-20` `supportsAdaptiveThinking(model)` 判断含 `opus-4-6` / `sonnet-4-6`；`:22-25` `supportsThinking(model)` 判断含 `claude`。

## T7: 实现 `ConversationManager` + Message + 三个 block record
- 影响文件: `src/main/java/com/cncode/conversation/ConversationManager.java`、`Message.java`、`ThinkingBlock.java`、`ToolUseBlock.java`、`ToolResultBlock.java`
- 依赖任务: 无
- 完成标准:
 - `src/main/java/com/cncode/conversation/ThinkingBlock.java:3` `record ThinkingBlock(String thinking, String signature)`；
 - `src/main/java/com/cncode/conversation/ToolUseBlock.java:5` `record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments)`；
 - `src/main/java/com/cncode/conversation/ToolResultBlock.java:3` `record ToolResultBlock(String toolUseId, String content, boolean isError)`；
 - `src/main/java/com/cncode/conversation/Message.java:5-32` 可变类 Message，字段 role / content / thinkingBlocks / toolUses / toolResults + 5 套 getter/setter；
 - `src/main/java/com/cncode/conversation/ConversationManager.java:17-46` 实现 6 个 add 方法（含 `addSystemReminder` 包裹 `<system-reminder>\n...\n</system-reminder>`）；
 - `src/main/java/com/cncode/conversation/ConversationManager.java:48-58` 实现 `getMessages()` 返回 `List.copyOf(history)`、`getMessagesMutable()`、`size()`；
 - `src/main/java/com/cncode/conversation/ConversationManager.java:60-174` 实现 `serialize(protocol)` 分发到 `serializeAnthropic` / `serializeOpenAI`，含同角色文本消息合并逻辑。

## T8: 覆盖 Thinking + Reasoning 行为测试
- 影响文件: `src/test/java/com/cncode/llm/ThinkingTest.java`
- 依赖任务: T4, T5, T6, T7
- 完成标准:
 - `testSupportsAdaptiveThinking` 验证 opus-4-6 / sonnet-4-6=true，opus-4-5 / sonnet-4-5=false，gpt-5=false；
 - `testAnthropicThinkingAdaptive` 断言 4.6 模型走 adaptive、`thinking.type="adaptive"`；
 - `testAnthropicThinkingEnabled` 断言非官方模型走 enabled、`budget_tokens = maxTokens - 1`；
 - `testAnthropicThinkingDisabled` 断言 `thinking=false` 时请求体无 thinking 字段；
 - `testAnthropicThinkingBlocksInConversation` 断言 thinking block 的 signature 能往返；
 - `testOpenAIReasoningEnabled` / `testOpenAIReasoningDisabled` 分别覆盖 OpenAI reasoning 开关。

## T9: 接入主流程
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`、`src/main/java/com/cncode/agent/Agent.java`、`src/main/java/com/cncode/subagent/AgentTool.java`、`src/main/java/com/cncode/teams/TeammateRunner.java`
- 依赖任务: T1-T7
- 完成标准:
 - `src/main/java/com/cncode/tui/CNCodeModel.java:391` 用 `LlmClient.create(selectedProvider, systemPrompt)` 构造 client；
 - `src/main/java/com/cncode/tui/CNCodeModel.java:399` 把 client 传给 `new AgentTool(client, registry, protocol)`；
 - `src/main/java/com/cncode/agent/Agent.java:126` Agent Loop 调用 `client.stream(conv, tools)`；
 - `src/main/java/com/cncode/agent/Agent.java:150-179` `switch (event)` 模式匹配消费 8 种事件；
 - `src/main/java/com/cncode/subagent/AgentTool.java:74` `setModelResolver(Function<String, LlmClient> modelResolver)` 接入短名解析。

## T10: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T9
- 完成标准:
 - `./gradlew build` 通过；
 - `./gradlew test --tests "com.cncode.llm.*"` 通过（6+ thinking_test 全绿）；
 - 在 TUI 中发送任意一句话，能看到流式文本（`TextDelta`）被逐 token 渲染到对话窗口，证明 `BlockingQueue<StreamEvent>` 与事件渲染端到端打通。

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10


# ch02: 让 AI 开口说话 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性

- [ ] `LlmClient` 接口在 `src/main/java/com/cncode/llm/LlmClient.java:10-20` 实现，方法签名 `BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools)`（`grep -n 'interface LlmClient' src/main/java/com/cncode/llm/LlmClient.java`）。
- [ ] `LlmClient.create(cfg, systemPrompt)` 静态工厂方法在 `src/main/java/com/cncode/llm/LlmClient.java:14-19` 用 switch 表达式按 protocol ∈ {anthropic, openai} 路由，未知 protocol 抛 `new IllegalArgumentException("Unknown protocol: " + cfg.getProtocol())`（`grep -n 'static LlmClient create' src/main/java/com/cncode/llm/LlmClient.java`）。
- [ ] 8 个流式事件 record 在 `src/main/java/com/cncode/llm/StreamEvent.java:5-22` 齐全（TextDelta / ThinkingDelta / ThinkingComplete / ToolCallStart / ToolCallDelta / ToolCallComplete / StreamEnd / Error），全部用 `sealed interface` + `implements StreamEvent`（`grep -c 'record .* implements StreamEvent' src/main/java/com/cncode/llm/StreamEvent.java` 返回 8）。
- [ ] `LlmException` 基类 + 4 个静态嵌套子类（`AuthenticationException` / `RateLimitException{retryAfter}` / `ContextTooLongException` / `NetworkException`）在 `src/main/java/com/cncode/llm/LlmException.java:3-41` 齐全（`grep -n 'class.*Exception' src/main/java/com/cncode/llm/LlmException.java`）。
- [ ] `ModelResolver.supportsAdaptiveThinking` 在 `src/main/java/com/cncode/llm/ModelResolver.java:17-20` 严格按 `opus-4-6` / `sonnet-4-6` 子串判定（`grep -n 'supportsAdaptiveThinking' src/main/java/com/cncode/llm/ModelResolver.java`）。
- [ ] `AnthropicClient.stream` 在 `src/main/java/com/cncode/llm/AnthropicClient.java:52-68` 实现：
 - [ ] 在 `Thread.startVirtualThread` 中调 `doStream`（`grep -n 'startVirtualThread' src/main/java/com/cncode/llm/AnthropicClient.java`）；
 - [ ] 异常被 `classifyError(e)` 归类为 `LlmException` 并以 `StreamEvent.Error` 入队（`src/main/java/com/cncode/llm/AnthropicClient.java:62`）；
 - [ ] SSE 主循环在 `src/main/java/com/cncode/llm/AnthropicClient.java:135-233` 处理 `content_block_start` 识别 `thinking` / `tool_use`、`content_block_delta` 识别 `thinking_delta` / `signature_delta` / `text_delta` / `input_json_delta`；
 - [ ] StreamEnd 携带 stopReason（默认 `end_turn`）与 input/output tokens（`src/main/java/com/cncode/llm/AnthropicClient.java:236-237`）。
- [ ] `AnthropicClient` thinking=true 时根据 `ModelResolver.supportsAdaptiveThinking` 切换 adaptive / enabled（`src/main/java/com/cncode/llm/AnthropicClient.java:80-86`）。
- [ ] `classifyHttpError` 在 `src/main/java/com/cncode/llm/AnthropicClient.java:245-255` 覆盖 413 / `prompt is too long` / 401 (`AuthenticationException`) / 429 (`RateLimitException`) / default(`LlmException`)。
- [ ] `OpenAiClient.stream` 在 `src/main/java/com/cncode/llm/OpenAiClient.java:51-67` 实现，`doStream` 主循环 `src/main/java/com/cncode/llm/OpenAiClient.java:125-203` 处理 `response.output_text.delta` / `response.output_item.added`（function_call / reasoning）/ `response.reasoning_summary_text.delta/done` / `response.function_call_arguments.delta/done` / `response.completed`。
- [ ] OpenAI thinking=true 时设置 `reasoning: {effort:"high", summary:"detailed"}`（`src/main/java/com/cncode/llm/OpenAiClient.java:84-86`）。
- [ ] `OpenAiClient.classifyHttpError` 在 `src/main/java/com/cncode/llm/OpenAiClient.java:211-222` 处理 413 / 400+`context_length_exceeded` / 401 / 429 / default。
- [ ] `ModelResolver` 在 `src/main/java/com/cncode/llm/ModelResolver.java:7-11` 暴露 `ALIASES` Map（haiku → claude-haiku-4-5、sonnet → claude-sonnet-4-6、opus → claude-opus-4-6）。
- [ ] `Message` 可变类在 `src/main/java/com/cncode/conversation/Message.java:5-32` 定义，字段 `role / content / thinkingBlocks / toolUses / toolResults` + 5 套 getter/setter。
- [ ] 三个 record 块在 `src/main/java/com/cncode/conversation/ThinkingBlock.java:3`、`ToolUseBlock.java:5`、`ToolResultBlock.java:3` 定义为 record（`grep -rn '^public record' src/main/java/com/cncode/conversation/` 命中 3 处）。
- [ ] `ConversationManager` 6 个 add 方法 + `getMessages` + `serialize` 在 `src/main/java/com/cncode/conversation/ConversationManager.java:17-62` 齐全。
- [ ] `addSystemReminder` 包裹 `<system-reminder>\n{content}\n</system-reminder>`（`src/main/java/com/cncode/conversation/ConversationManager.java:44-46`）。
- [ ] `serializeAnthropic` 合并同角色连续文本消息以维持 user/assistant 交替（`src/main/java/com/cncode/conversation/ConversationManager.java:110-132`）。

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `LlmClient.create` 至少 1 个非测试调用方（`grep -rn "LlmClient.create" --include="*.java" src/main/` 命中 `src/main/java/com/cncode/tui/CNCodeModel.java:391`）。
- [ ] `new ConversationManager()` 至少 6 个非测试调用方（`grep -rn "new ConversationManager()" --include="*.java" src/main/` 命中 `tui/CNCodeModel.java:189/880/1440`、`compact/ContextCompactor.java:260/286/299/390`、`subagent/AgentTool.java:285/337`、`memory/MemoryManager.java:90`、`teams/TeamManager.java:88`）。
- [ ] `src/main/java/com/cncode/agent/Agent.java:126` 实际调用 `client.stream(conv, tools)`，证明 LlmClient 接口接到 Agent Loop（`grep -n 'client.stream' src/main/java/com/cncode/agent/Agent.java`）。
- [ ] `src/main/java/com/cncode/agent/Agent.java:150-179` 用 `switch (event)` 模式匹配消费 `TextDelta` / `ThinkingDelta` / `ThinkingComplete` / `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd` / `Error` 8 种事件，sealed interface 保证无遗漏。
- [ ] `src/main/java/com/cncode/agent/Agent.java:217/224/239` 通过 `conv.addAssistantFull(text, thinkingBlocks, toolUseBlocks)` 把 thinking 与 tool 写回历史，保证下一轮能回放 signature。
- [ ] `ModelResolver.resolve` 在 `src/main/java/com/cncode/llm/AnthropicClient.java:38` 被构造函数使用（`grep -rn "ModelResolver" --include="*.java" src/main/`）。
- [ ] `setModelResolver(Function<String, LlmClient>)` 在 `src/main/java/com/cncode/subagent/AgentTool.java:74` 提供给 SubAgent 切模型（ch13）。
- [ ] `LlmException` / `LlmException.ContextTooLongException` / `LlmException.RateLimitException` / `LlmException.NetworkException` 被 `src/main/java/com/cncode/agent/Agent.java:185-202` 的 streamError 分支按错误文本关键字消费，错误链未断。

## 3. 编译与测试

- [ ] `./gradlew build` 通过。
- [ ] `./gradlew test --tests "com.cncode.llm.*"` 通过：6+ 个 Thinking/Reasoning 测试全绿。
- [ ] `./gradlew compileJava` 无 sealed-switch 警告（编译器穷尽性检查通过）。

## 4. 端到端验证

- [ ] TUI 启动后发送 `hello`，对话窗口逐 token 渲染流式回复——证明 `StreamEvent.TextDelta` 通道接到 `src/main/java/com/cncode/tui/CNCodeModel.java` 的事件渲染。
- [ ] 模型为 `claude-sonnet-4-6`（或更新）且 `thinking: true` 时能在对话区看到 thinking 文本流（`ThinkingDelta` → tui 渲染），证明 adaptive thinking 接通。
- [ ] 提供故意失败的 API key 后 TUI 显示 `Invalid API key: ...`（`AuthenticationException` 路径），证明错误分类生效。
- [ ] 留存证据: `./gradlew test -i` 输出包含 `testAnthropicThinkingAdaptive PASSED` / `testOpenAIReasoningEnabled PASSED` 等日志行。

## 5. 文档

- [ ] spec.md / tasks.md / checklist.md 三件套齐全（`/Users/codemelo/cncode/docs/java/ch02/`）。






# ch03: 工具系统 Spec

## 1. 背景

LLM 本身只会说话，要让 CN Code 真正能读代码、改代码、跑命令，必须给它一组「手」——也就是工具。第 2 章拿到了能调用工具的 LLM 客户端，但只要工具系统不到位，模型每次回的 `tool_use` 都会卡在协议层无人执行，整个 Agent 循环（ch04）也无从挂载。本章用 Java 21 的 `interface` + `record` + `sealed enum` 把工具的统一契约、注册表、执行结果与六个核心实现一次性落地，给后续章节提供「工具能力」这一切入点。

## 2. 目标

对外提供 `com.cncode.tool` 包：调用方拿到一个 `ToolRegistry`，可以 `register` 任意 `Tool` 实现，按 protocol（`anthropic` / `openai`）拉取 schema 喂给 LLM；模型回 `tool_use` 时通过 `registry.get(name)` 拿到具体工具，`tool.execute(args)` 返回 `ToolResult`。所有工具实现 `Tool` 接口，按 `ToolCategory` 标记并发安全等级（`READ` 可并行，`WRITE`/`COMMAND` 串行）。`ToolRegistry.createDefault()` 一次注入 6 个最小可用工具：ReadFile / WriteFile / EditFile / Bash / Glob / Grep；可选注入 ToolSearch（Deferred 工具发现）与 AskUserQuestion（结构化问卷）。

## 3. 功能需求

- F1: `Tool` 接口暴露 `name() / description() / category() / schema() / execute(args)` 五个核心方法，外加 `shouldDefer()` 默认返回 `false`，让 Deferred 工具有标准开关。
- F2: `ToolCategory` 枚举提供 `READ` / `WRITE` / `COMMAND` 三档，让上层执行器据此决定串并行边界。
- F3: `ToolResult` 是 `record(String output, boolean isError)`，并暴露 `success(output)` / `error(message)` 两个静态工厂。
- F4: `ToolRegistry` 用 `LinkedHashMap<String, Tool>` 保证注册顺序，`register` / `get` / `listTools` / `getAllSchemas(protocol)` 四件套覆盖增、查、列、序列化。
- F5: `getAllSchemas(protocol)` 在 `protocol == "openai"` 时把 Anthropic 风格的 `{name, description, input_schema}` 转译为 `{type:"function", name, description, parameters}`，其他 protocol 直接透传。
- F6: Deferred 工具机制：`shouldDefer()=true` 的工具默认不出现在 `getAllSchemas` 里；`markDiscovered(name)` 标记后下一轮会被纳入；`getDeferredToolNames()` 列出未发现的 Deferred 工具供 system reminder 使用。
- F7: Deferred 检索：`searchDeferred(query, maxResults, protocol)` 大小写无关地匹配 name / description；`findDeferredByNames(names, protocol)` 按精确名拉取，二者均按 protocol 输出 schema。
- F8: 六个核心工具实现：
  - ReadFile：按 `offset`/`limit`（默认 0 / 2000 行）读文件，输出 `行号\t内容` 格式，目录或不存在直接报错。
  - WriteFile：写入文件、自动创建父目录、POSIX 文件系统下设 `rwxr-xr-x` / `rw-r--r--`。
  - EditFile：要求 `old_string` 在文件中恰好出现一次，否则报错；要求文件已存在。
  - Bash：`bash -c <command>`，可选 `timeout`（默认 120 秒，硬上限 600 秒），独立读 stdout / stderr，超时强制 `destroyForcibly`，最终输出包含 `$ command` / stdout / `STDERR:` / `(exit code N)`。
  - Glob：`PathMatcher("glob:" + pattern)` 递归遍历，自动跳过 `.git/.venv/node_modules/__pycache__/.tox/.mypy_cache`，结果按字典序排序。
  - Grep：`Pattern.compile` 编译正则；支持 `include` 文件名过滤；二进制文件检测（前 512 字节含 `\0` 即跳过）；命中后输出 `相对路径:行号:行内容`，并按 `ToolRegistry.MAX_OUTPUT_CHARS=10000` 做硬截断。
- F9: `ToolSearchTool`：自身不 Deferred（始终可用），`query="select:Name1,Name2"` 走 `findDeferredByNames`，否则走 `searchDeferred`；命中后用 Jackson 序列化 schema 并对每条 `markDiscovered`。
- F10: `AskUserTool`：标记为 Deferred；通过外部 `setEventQueue` 注入 `BlockingQueue<AgentEvent>`，执行时构造 `AskUserRequestEvent` 入队，`CompletableFuture.get(5, MINUTES)` 阻塞等用户响应，超时或用户拒绝（answer 含 `_declined`）返回错误结果。

## 4. 非功能需求

- N1: 工具输出统一硬上限 `ToolRegistry.MAX_OUTPUT_CHARS = 10_000`，由调用方（`StreamingExecutor`）在 `execute()` 之后做单层截断并追加 `... (truncated)`。
- N2: schema 中所有 Map 用 `Map.of` 或 `LinkedHashMap` 保证 key 稳定顺序；`required` 字段一律用 `List.of`。
- N3: Bash 工具子进程读流并发执行（stdout/stderr 同时读取），避免 pipe 满导致死锁；中断时 `Thread.currentThread().interrupt()` 复位中断标志。
- N4: 文件类工具（Read / Write / Edit）默认按 `Files.readString` / `Files.writeString` 走平台默认字符集（UTF-8），不引入额外参数。
- N5: Glob / Grep 必须明确 SKIP_DIRS 集合一致（同一个 6 项常量），避免大目录扫描爆炸。
- N6: AskUserTool 的 `CompletableFuture` 5 分钟超时是安全兜底，超时默认拒绝；这是「宁可让 Agent 失败也不让线程永久挂起」的策略。

## 5. 设计概要

- 核心类型
  - `Tool`（interface）：唯一抽象，所有工具实现它。
  - `ToolCategory`（enum）：`READ` / `WRITE` / `COMMAND` 三态，决定并发归类。
  - `ToolResult`（record）：`{output, isError}`，工厂方法 `success` / `error`。
  - `ToolRegistry`（class）：以 `LinkedHashMap` 保序，附带 `Set<String> discoveredTools` 跟踪 Deferred 发现状态。
- 主流程（一次工具调用）
  1. 模型返回 `tool_use(name, args)`；
  2. Agent 主循环把 `(name, args)` 交给执行器；
  3. 执行器 `registry.get(name)` 拿到 `Tool`，按 `category()` 决定并行/串行；
  4. 调用 `tool.execute(args)` 得 `ToolResult`；
  5. 若 `output.length() > MAX_OUTPUT_CHARS` 截断；
  6. 把 `ToolResultEvent` 推回事件队列，结果以 `ToolResultBlock` 回灌对话。
- Deferred 工具流程
  1. 工具实现返回 `shouldDefer()=true`；
  2. `getAllSchemas` 默认跳过未发现项；
  3. Agent 把 `getDeferredToolNames()` 注入 system reminder；
  4. 模型主动调 `ToolSearch`，`markDiscovered` 后下一轮 `getAllSchemas` 才会暴露这些 schema。
- 与其他模块的交互
  - 被 `com.cncode.agent.Agent` / `com.cncode.agent.StreamingExecutor` 调用（ch03）。
  - 被 `com.cncode.tui.CNCodeModel.startChat` 创建并注册扩展工具（`AgentTool` / `TaskTools` / `TeamTools` / `EnterWorktreeTool` / `ExitWorktreeTool`）。
  - `AskUserTool.setEventQueue` 与 `CNCodeModel` 的 AgentEvent 队列双向通信。

## 6. Out of Scope

- 本章不实现权限审核（属 ch06，`PermissionChecker`）；执行器自带的权限分支由 `StreamingExecutor` 在 ch03 接入。
- 本章不实现 Pre/Post Hook 拦截（属 hook 模块 ch12，由 ch04 Agent Loop 包夹）。
- 本章不实现工具的并行调度策略（READ 并行 / WRITE 串行）；`ToolCategory` 只是标签，调度由 `StreamingExecutor` 负责。
- 本章不实现 MCP 工具桥接（属 ch07）；只保证 `ToolRegistry.register` 是 MCP 工具的注入点。
- 本章不实现 Subagent / Worktree 工具（属 ch13 / ch14）；只保留注册接口。
- TodoList / Team 工具的接入由 ch11 / ch15 负责。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch03: 工具系统 Tasks

> 任务粒度：每个任务可在一次会话内完成，可独立交付。本章为验收，所有任务已经在 `origin/java` 分支落地，逐项标注真实文件 / 行号。

## T1: 定义 `Tool` 接口
- 影响文件：`src/main/java/com/cncode/tool/Tool.java:5-20`
- 依赖任务：无
- 完成标准：`public interface Tool` 暴露 `name() / description() / category() / schema() / execute(Map)` 五个抽象方法，`shouldDefer()` 默认实现返回 `false`（Tool.java:17-19）。

## T2: 定义 `ToolCategory` 枚举与 `ToolResult` record
- 影响文件：`src/main/java/com/cncode/tool/ToolCategory.java:3-5`、`src/main/java/com/cncode/tool/ToolResult.java:3-12`
- 依赖任务：无
- 完成标准：`ToolCategory` 枚举包含 `READ`/`WRITE`/`COMMAND` 三个常量；`ToolResult` 是 `record(String output, boolean isError)`，提供 `success(output)`（ToolResult.java:5-7）和 `error(message)`（ToolResult.java:9-11）两个静态工厂。

## T3: 实现 `ToolRegistry` 核心增/查/列/序列化
- 影响文件：`src/main/java/com/cncode/tool/ToolRegistry.java:5-56`
- 依赖任务：T1, T2
- 完成标准：常量 `MAX_OUTPUT_CHARS = 10_000`（ToolRegistry.java:7）；底层 `LinkedHashMap<String, Tool>`（ToolRegistry.java:9）保序；`register` / `get` / `listTools`（ToolRegistry.java:27-37）齐全；`getAllSchemas(protocol)`（ToolRegistry.java:39-56）在 `protocol == "openai"` 时把 `{name, description, input_schema}` 转译为 `{type:"function", name, description, parameters}`，其它 protocol 原样透传。

## T4: 实现 Deferred 工具机制
- 影响文件：`src/main/java/com/cncode/tool/ToolRegistry.java:10-25`、`:58-109`
- 依赖任务：T3
- 完成标准：`Set<String> discoveredTools`（ToolRegistry.java:10）跟踪发现状态；`markDiscovered` / `isDiscovered`（ToolRegistry.java:12-18）暴露读写；`getDeferredToolNames`（ToolRegistry.java:20-25）只返回 `shouldDefer() && !discovered` 的工具；`getAllSchemas` 在 ToolRegistry.java:42 跳过未发现的 Deferred；`searchDeferred`（ToolRegistry.java:64-86）做大小写无关匹配并按 protocol 输出；`findDeferredByNames`（ToolRegistry.java:88-109）按精确名拉取。

## T5: 实现 `ToolRegistry.createDefault` 工厂
- 影响文件：`src/main/java/com/cncode/tool/ToolRegistry.java:111-120`
- 依赖任务：T3, T8~T13
- 完成标准：`createDefault()` 一次性注入 `ReadFileTool` / `WriteFileTool` / `EditFileTool` / `BashTool` / `GlobTool` / `GrepTool`（ToolRegistry.java:113-118），按文件类→命令类→搜索类的顺序，保证后续 `getAllSchemas` 输出稳定。

## T6: 实现 `ReadFileTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/ReadFileTool.java`
- 依赖任务：T1, T2
- 完成标准：`name()="ReadFile"`、`category()=READ`；schema 必填 `file_path`，可选 `offset`（默认 0）/ `limit`（默认 2000）；执行时校验文件存在 + 非目录（ReadFileTool.java:70-75），按 `split("\n", -1)` 切片后输出 `行号\t内容`（ReadFileTool.java:95-101）。

## T7: 实现 `WriteFileTool` 与 `EditFileTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/WriteFileTool.java`、`src/main/java/com/cncode/tool/impl/EditFileTool.java`
- 依赖任务：T1, T2
- 完成标准：WriteFile `category()=WRITE`，自动创建父目录，POSIX 文件系统下设 `rwxr-xr-x`（目录）/ `rw-r--r--`（文件）（WriteFileTool.java:69-90）；EditFile 必填 `file_path` / `old_string` / `new_string`，要求文件存在（EditFileTool.java:70-72），`countOccurrences` 必须返回 1，否则按 0 / >1 返回不同错误文案（EditFileTool.java:81-87）。

## T8: 实现 `BashTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/BashTool.java`
- 依赖任务：T1, T2
- 完成标准：常量 `MAX_TIMEOUT=600`（BashTool.java:15）；`category()=COMMAND`；用 `ProcessBuilder("bash","-c", command)` 启动（BashTool.java:85），stdout/stderr 分别读取（BashTool.java:92-97），超时 `process.destroyForcibly()`（BashTool.java:99-103）；输出格式 `$ command\n<stdout>\nSTDERR: <stderr>\n(exit code N)`（BashTool.java:107-121）；非零 exit code 返回 `isError=true`。

## T9: 实现 `GlobTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/GlobTool.java`
- 依赖任务：T1, T2
- 完成标准：`SKIP_DIRS` 包含 `.git/.venv/node_modules/__pycache__/.tox/.mypy_cache`（GlobTool.java:18-20）；`PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern)`（GlobTool.java:78）；`Files.walkFileTree` 跳过 SKIP_DIRS（GlobTool.java:84-88）；`matcher.matches(file.getFileName()) || matcher.matches(rel)` 双重判定（GlobTool.java:94）；结果 `Collections.sort` 后输出。

## T10: 实现 `GrepTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/GrepTool.java`
- 依赖任务：T1, T2, T3
- 完成标准：`Pattern.compile(pattern)` 捕获 `PatternSyntaxException`（GrepTool.java:83-88）；`include` 走 `PathMatcher("glob:" + include)` 过滤（GrepTool.java:90-92）；二进制检测 `isBinaryFile` 读前 512 字节检查 `\0`（GrepTool.java:164-180）；匹配输出 `相对路径:行号:行内容`（GrepTool.java:140-141）；累计输出长度超 `ToolRegistry.MAX_OUTPUT_CHARS` 时截断并追加 `... output truncated`（GrepTool.java:143-146）。

## T11: 实现 `ToolSearchTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/ToolSearchTool.java`
- 依赖任务：T4
- 完成标准：`shouldDefer()` 显式返回 `false`（ToolSearchTool.java:60-62）；构造接收 `ToolRegistry` + 可选 protocol（ToolSearchTool.java:35-42）；`max_results` 默认 5，上下夹紧到 `[1, 20]`（ToolSearchTool.java:94-100）；`query.startsWith("select:")` 走 `findDeferredByNames`，否则走 `searchDeferred`（ToolSearchTool.java:104-111）；命中后用 Jackson `ObjectMapper` 序列化 schema 并对每条调 `registry.markDiscovered(name)`（ToolSearchTool.java:124-130）。

## T12: 实现 `AskUserTool`
- 影响文件：`src/main/java/com/cncode/tool/impl/AskUserTool.java`
- 依赖任务：T1, T2
- 完成标准：`shouldDefer()=true`（AskUserTool.java:51-53）；`setEventQueue(BlockingQueue<AgentEvent>)` 由 TUI 注入（AskUserTool.java:31-33）；schema 描述 1~4 个 Question，每个 2~4 个 Option（AskUserTool.java:69-88）；执行时构造 `AskUserRequestEvent` 入队（AskUserTool.java:135），`future.get(5, TimeUnit.MINUTES)` 阻塞等响应（AskUserTool.java:143）；`answers.containsKey("_declined")` 返回错误结果（AskUserTool.java:148-150）。

## T13: 接入主流程（TUI）
- 影响文件：`src/main/java/com/cncode/tui/CNCodeModel.java:394-421`
- 依赖任务：T5, T11, T12
- 完成标准：`registry = ToolRegistry.createDefault()`（CNCodeModel.java:394）；随后注册 `ToolSearchTool(registry, protocol)`（:396）、`AskUserTool`（:397-398）、`AgentTool`（:399-403）、`EnterWorktreeTool` / `ExitWorktreeTool`（:409-410）、`TaskTools.*` 四件套（:418-421）、`TeamTools.*` 三件套（:425-427）；`AgentEvent` 队列由 model 持有并通过 `askUserTool.setEventQueue` 注入。

## T14: 端到端验证
- 影响文件：无（仅运行验证）
- 依赖任务：T1~T13
- 完成标准：
  - `./gradlew build` 通过（顶层命令）。
  - `./gradlew test --tests "com.cncode.tool.ToolSearchTest"` 通过：`testDeferredNotInSchemas` / `testToolSearchMarksDiscovered` / `testDiscoveredInSchemas` / `testGetDeferredToolNames`（ToolSearchTest.java:67/80/97/111）。
  - TUI 启动后让模型调一次 `ReadFile`，能在屏幕上看到 `ToolResultEvent` 渲染；让模型调一次 `Bash`（如 `pwd`），能看到 `(exit code 0)` 收尾。

## 进度
- [ ] T1 Tool 接口
- [ ] T2 ToolCategory / ToolResult
- [ ] T3 ToolRegistry 核心
- [ ] T4 Deferred 机制
- [ ] T5 createDefault 工厂
- [ ] T6 ReadFileTool
- [ ] T7 WriteFileTool + EditFileTool
- [ ] T8 BashTool
- [ ] T9 GlobTool
- [ ] T10 GrepTool
- [ ] T11 ToolSearchTool
- [ ] T12 AskUserTool
- [ ] T13 TUI 接入
- [ ] T14 端到端验证（build 通过 + ToolSearchTest 通过 + TUI 工具调用链确认）


# ch03: 工具系统 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性
- [ ] 接口 `Tool` 在 `src/main/java/com/cncode/tool/Tool.java:5-20` 定义，包含 `name() / description() / category() / schema() / execute(Map)` 五个抽象方法 + `shouldDefer()` 默认实现（`grep -n "interface Tool" src/main/java/com/cncode/tool/Tool.java`）
- [ ] 枚举 `ToolCategory` 在 `src/main/java/com/cncode/tool/ToolCategory.java:3-5`，包含 `READ` / `WRITE` / `COMMAND` 三个常量（`grep -n "READ, WRITE, COMMAND" src/main/java/com/cncode/tool/ToolCategory.java`）
- [ ] `ToolResult` 在 `src/main/java/com/cncode/tool/ToolResult.java:3` 是 `record(String output, boolean isError)`；`success` / `error` 静态工厂在 :5-11（`grep -n "public record ToolResult" src/main/java/com/cncode/tool/ToolResult.java`）
- [ ] `ToolRegistry` 常量 `MAX_OUTPUT_CHARS = 10_000` 在 `src/main/java/com/cncode/tool/ToolRegistry.java:7`（`grep -n "MAX_OUTPUT_CHARS" src/main/java/com/cncode/tool/ToolRegistry.java`）
- [ ] `ToolRegistry.getAllSchemas` 在 `:39-56` 实现 protocol 分流（`anthropic` 透传 / `openai` 转译为 `type:"function"`）
- [ ] Deferred 机制：`discoveredTools` / `markDiscovered` / `getDeferredToolNames` 在 ToolRegistry.java:10-25；`searchDeferred` 在 :64-86；`findDeferredByNames` 在 :88-109
- [ ] `ToolRegistry.createDefault` 在 :111-120 注入 6 个核心工具（ReadFile / WriteFile / EditFile / Bash / Glob / Grep），顺序与上述一致
- [ ] `BashTool` 常量 `MAX_TIMEOUT=600` 在 `src/main/java/com/cncode/tool/impl/BashTool.java:15`；输出格式 `$ command` + stdout + `STDERR:` + `(exit code N)` 在 :107-121
- [ ] `ReadFileTool` 输出格式 `行号\t内容`（ReadFileTool.java:100）；默认 limit=2000 / offset=0（:50-51）
- [ ] `EditFileTool.countOccurrences` 在 EditFileTool.java:100-111；唯一性校验返回 0 / >1 不同错误（:81-87）
- [ ] `WriteFileTool` POSIX 文件系统下设 `rwxr-xr-x` / `rw-r--r--`（WriteFileTool.java:69-90）
- [ ] `GlobTool.SKIP_DIRS` 包含六项 `.git/.venv/node_modules/__pycache__/.tox/.mypy_cache`（GlobTool.java:18-20）；`GrepTool.SKIP_DIRS` 同样六项（GrepTool.java:19-21）
- [ ] `GrepTool.isBinaryFile` 读前 512 字节检查 `\0`（GrepTool.java:164-180）；累计输出超 `MAX_OUTPUT_CHARS` 截断（:143-146）
- [ ] `ToolSearchTool.shouldDefer()` 显式返回 `false`（ToolSearchTool.java:60-62）；`max_results` 夹紧到 `[1, 20]`（:94-100）；命中后 `markDiscovered`（:124-130）
- [ ] `AskUserTool.shouldDefer()` 返回 `true`（AskUserTool.java:51-53）；`future.get(5, TimeUnit.MINUTES)` 兜底超时（:143）；`_declined` 走错误结果（:148-150）

## 2. 接入完整性（必查，杜绝死代码）
- [ ] `grep -rn "ToolRegistry.createDefault" src/main/java` 在 TUI 至少 1 个调用方（`src/main/java/com/cncode/tui/CNCodeModel.java:394`）
- [ ] `grep -rn "new ToolSearchTool" src/main/java` 在 TUI 调用方注册（`CNCodeModel.java:396`）
- [ ] `grep -rn "new AskUserTool" src/main/java` 在 TUI 调用方注册并 `setEventQueue`（`CNCodeModel.java:397-398`）
- [ ] `registry.getAllSchemas(protocol)` 在 Agent 主循环引用（`src/main/java/com/cncode/agent/Agent.java:117`）
- [ ] `registry.get(call.toolName())` 在 `StreamingExecutor.executeSingle` 调用（`src/main/java/com/cncode/agent/StreamingExecutor.java:75`）
- [ ] `ToolRegistry.MAX_OUTPUT_CHARS` 在 `StreamingExecutor.java:135` 用于结果截断
- [ ] `getDeferredToolNames` 在 `Agent.java:94` 注入 system reminder

## 3. 编译与测试
- [ ] `./gradlew build` 通过（顶层命令）
- [ ] `./gradlew test --tests "com.cncode.tool.ToolSearchTest"` 全部通过：`testDeferredNotInSchemas`（ToolSearchTest.java:68） / `testToolSearchMarksDiscovered`（:81） / `testDiscoveredInSchemas`（:98） / `testGetDeferredToolNames`（:112）
- [ ] `./gradlew check` 无新警告

## 4. 端到端验证
- [ ] TUI 入口：启动后让模型调用 `ReadFile`，屏幕上看到工具调用 + 文件内容（带行号）渲染 —— 调用链 `CNCodeModel → Agent.run → StreamingExecutor.executeSingle → ReadFileTool.execute`
- [ ] Bash 调用：让模型跑 `pwd`，看到 `$ pwd` + 当前工作目录 + `(exit code 0)` 三段输出（BashTool.java:107-121）
- [ ] Deferred 工具：默认工具列表不含 `AskUserQuestion`；让模型调 `ToolSearch(query="AskUser")` 后下一轮模型可调用 `AskUserQuestion`
- [ ] 留存证据：验收阶段无截图；如需补，可在 TUI 中让模型执行 `ReadFile docs/java/ch03/spec.md` 拍照保存

## 5. 文档
- [ ] spec.md / tasks.md / checklist.md 三件套齐全（`docs/java/ch03/`）
- [ ] commit 信息标注 `ch03` 与三件套关闭状态（待统一打包提交）








# ch04: Agent Loop Spec

## 1. 背景

ch02 把 LLM 客户端跑通了：一次 `stream()` 调用从模型拿到一段文本或一组 tool_use。ch03 把工具注册表与六个核心工具搭好了。但「一次调用」和「一个 Agent」之间还差一个关键环节：让模型自主地反复思考 → 调工具 → 看结果 → 再思考，直到任务真正完成。没有 Agent Loop，CN Code 还只是个能调一次工具的聊天机器人。本章把这条循环管线做出来：一个虚拟线程驱动的 while 循环，消费 `BlockingQueue<StreamEvent>` 流式事件，分类执行工具调用（只读并行 / 写串行），把结果回写 `ConversationManager`，再以 `AgentEvent` 形式向 TUI 推送进度。

## 2. 目标

对外提供 `com.cncode.agent.Agent`：调用者准备好 `LlmClient` / `ToolRegistry` / `protocol`，调一次 `agent.run(conversation)` 拿到 `BlockingQueue<AgentEvent>`，从中 poll 出文本流、思考流、工具调用、工具结果、用量、错误、轮次完成、循环完成等事件并直接渲染到 TUI。循环内部完成：消费上游 stream → 收集 tool_use → `StreamingExecutor` 分流并发执行 → 回写会话 → 进入下一轮；同时承担 deferred tool 提醒注入、Plan Mode 提醒注入、自动 compact、max_tokens 恢复、context 超限重试、rate-limit 退避等运维职责。

## 3. 功能需求

- F1: 提供 `Agent` 类，构造接收 `LlmClient` / `ToolRegistry` / `protocol`；支持通过 setter 注入 `PermissionChecker` / `HookEngine` / `maxIterations` / `workDir` / 通知 supplier / tool name filter。
- F2: `Agent.run(ConversationManager)` 返回 `BlockingQueue<AgentEvent>`，内部用 `Thread.startVirtualThread` 启动 agent loop，确保 TUI 主线程不阻塞；异常一律包成 `AgentEvent.ErrorEvent` 入队。
- F3: 提供 `AgentEvent` sealed interface，覆盖文本流 / 思考流 / 思考完成 / 工具使用 / 工具结果 / 轮次完成 / 循环完成 / 用量 / 错误 / 压缩 / 重试 / 权限请求 / askuser 共 13 种事件 record。
- F4: 主循环按轮迭代：先 drain 通知 supplier 注入 system-reminder，跑 `ContextCompactor.manage`，把 deferred tool 名字以 system-reminder 注入；Plan Mode 下再注入 `PlanModePrompt.buildReminder`。
- F5: 每轮调 `client.stream(conv, tools)` 拿到 `BlockingQueue<StreamEvent>`，用 30 秒 poll 超时消费，把 TextDelta / ThinkingDelta / ThinkingComplete / ToolCallStart / ToolCallComplete / StreamEnd / Error 七类事件转译成 `AgentEvent` 推送给消费者；同时收集 tool_use 列表、用量、stop_reason。
- F6: 工具执行委托给 `StreamingExecutor.executeAll`：按 `ToolCategory.READ` 把 calls 拆成 readCalls / otherCalls 两段，readCalls 数量 >1 时用 `Executors.newVirtualThreadPerTaskExecutor()` 并发跑，其它串行；权限走 `PermissionChecker.check` 决策 ALLOW/ASK/DENY，ASK 通过 `PermissionRequestEvent` 把 `CompletableFuture<PermissionResponse>` 抛给 TUI 等用户回填；执行前后跑 PreToolUse / PostToolUse hook。
- F7: 工具执行完成后调 `conv.addAssistantFull(text, thinking, toolUses)` 写回助手消息，再调 `conv.addToolResultsMessage(results)` 写回工具结果消息；本轮无 tool_use 则推 `TurnComplete` + `LoopComplete` 退出循环。
- F8: 错误恢复：stream Error 含 `context` / `too long` / `prompt` 关键字时调 `ContextCompactor.forceCompact` 并 retry，最多 3 次；含 `rate limit` 时退避 5 秒重试；`max_tokens` stop_reason 首次提升上限到 `MAX_TOKENS_CEILING=64_000` 并续写，最多 `MAX_OUTPUT_RECOVERIES=3` 次拆分续写。
- F9: 工具输出超过 `ToolRegistry.MAX_OUTPUT_CHARS=10_000` 强制截断并追加 `... (truncated)` 标记，保证 tool_result 不撑爆下一轮上下文。

## 4. 非功能需求

- N1: Agent loop 必须跑在虚拟线程上（`Thread.startVirtualThread`），主 TUI 线程靠 `BlockingQueue` poll 实现非阻塞渲染；`Thread.currentThread().isInterrupted()` 命中即退出循环。
- N2: 工具调用分流策略必须严格保证：只读工具可并行（虚拟线程池），写 / 命令类工具一律串行执行，避免相互踩文件。
- N3: stream 消费 poll 超时统一 30 秒，超时直接推 `Stream timeout` 错误并 return，不允许卡住整条循环。
- N4: `AgentEvent` 队列容量 64，`putSafe` 在 InterruptedException 时回写中断标志而不是抛异常，保障 TUI 关停时能干净退出。
- N5: 权限询问的 `CompletableFuture.get` 设 5 分钟超时，超时按 DENY 处理，避免 Agent 永远悬挂。

## 5. 设计概要

- 核心数据结构: `AgentEvent`（sealed interface，13 个 record 实现）、`StreamingExecutor.ToolCallInfo{toolId, toolName, args}` / `ToolExecResult{toolId, output, isError}`、内部 `Agent.ToolCallInfo`（轮内汇聚 tool_use）。
- 主流程:
 1. TUI 选 provider → 构造 `LlmClient` → 构造 `Agent(client, registry, protocol)` → 注入 checker / hook / workDir 等；
 2. 用户输入 → TUI `agent.run(conv)` 拿 queue → 启动 `Command.tick` 轮询；
 3. 每个 `AgentEventMessage` tick 在 model.update 中 drain queue → 转换成 `ChatMessage` 渲染；
 4. 收到 `LoopComplete` / `ErrorEvent` 结束本次会话，恢复 idle。
- 调用链:
 - TUI 用户提问 → `CNCodeModel` 调 `agent.run` → agent virtual thread 开转；
 - 每轮: notification drain → compact → deferred reminder → plan reminder → `client.stream` → 消费 StreamEvent → 收集 toolCalls → `StreamingExecutor.executeAll` → `conv.addAssistantFull` + `addToolResultsMessage`；
 - 工具内含权限决策 → ASK 走 `PermissionRequestEvent` → TUI 弹 dialog → CompletableFuture.complete → executor 继续。
- 与其他模块的交互:
 - 依赖 ch02 的 `LlmClient` / `StreamEvent` / `ConversationManager`、ch03 的 `ToolRegistry` / `Tool` / `ToolCategory` / `ToolResult`、ch06 的 `PermissionChecker` / `PermissionMode` / `PlanFile` / `PlanModePrompt`、ch08 的 `ContextCompactor`、ch12 的 `HookEngine`；
 - 被 `CNCodeModel` 直接调用，输出事件队列由 TUI 渲染。

## 6. Out of Scope

- 不在本章实现 `ContextCompactor` 内部算法（ch08 主题）。
- 不实现 Plan Mode reminder 文案（ch06 主题）。
- 不实现 SubAgent 派遣（ch13 主题）。
- 不实现 hook 引擎本体（ch12 主题）。
- 不做 system prompt 模块化拼装；本章 system prompt 由 `LlmClient.create` 接收的字符串透传，模块化拼装留给后续章节。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch04: Agent Loop Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。本章为验收，所有任务已经在仓库里落地。

## T1: 定义 AgentEvent sealed interface
- 影响文件: `src/main/java/com/cncode/agent/AgentEvent.java:1-39`
- 依赖任务: 无
- 完成标准: `public sealed interface AgentEvent` 包含 13 个 record 实现：`StreamText` / `ThinkingText` / `ThinkingComplete` / `ToolUseEvent` / `ToolResultEvent` / `TurnComplete` / `LoopComplete` / `UsageEvent` / `ErrorEvent` / `CompactEvent` / `RetryEvent` / `PermissionRequestEvent` / `AskUserRequestEvent`；`PermissionRequestEvent` 字段含 `CompletableFuture<PermissionResponse>`（AgentEvent.java:33-34）；`AskUserRequestEvent` 字段含 `CompletableFuture<Map<String, String>>`（AgentEvent.java:36-38）。

## T2: 定义 Agent 类骨架与依赖注入
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:19-48`
- 依赖任务: T1
- 完成标准: 构造方法接 `(LlmClient client, ToolRegistry registry, String protocol)`（Agent.java:35）；`MAX_TOKENS_CEILING=64_000`（Agent.java:21）、`MAX_OUTPUT_RECOVERIES=3`（Agent.java:22）；setter 注入 `PermissionChecker` / `HookEngine` / `maxIterations` / `workDir` / `notificationFn` / `toolNameFilter`（Agent.java:42-47）。

## T3: 实现 run 入口与虚拟线程派发
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:50-60`
- 依赖任务: T2
- 完成标准: `public BlockingQueue<AgentEvent> run(ConversationManager conv)` 返回 `LinkedBlockingQueue<>(64)`；`Thread.startVirtualThread` 启 `agentLoop`，所有 Exception 包成 `AgentEvent.ErrorEvent("Agent error: ...")`（Agent.java:51-58）。

## T4: 实现轮次起手：通知 / compact / deferred / plan 注入
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:62-114`
- 依赖任务: T3
- 完成标准:
 - 主循环 `for (int iteration = 1; ; iteration++)`（Agent.java:69）；
 - `maxIterations` 超限推 `ErrorEvent("Agent reached maximum iterations (%d)")` 退出（Agent.java:70-74）；
 - `Thread.currentThread().isInterrupted()` 命中 break（Agent.java:76）；
 - `notificationFn.get()` drain 后 `conv.addSystemReminder(note)`（Agent.java:79-83）；
 - `ContextCompactor.manage` 非空消息推 `CompactEvent`（Agent.java:87-91）；
 - `registry.getDeferredToolNames()` 非空时拼 reminder 注入（Agent.java:94-104）；
 - Plan Mode 下 `PlanModePrompt.buildReminder` 注入（Agent.java:107-114）。

## T5: 实现 StreamEvent 流消费
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:116-182`
- 依赖任务: T4
- 完成标准:
 - tool list 走 `registry.getAllSchemas(protocol)`，可选 `toolNameFilter` 过滤（Agent.java:117-125）；
 - `client.stream(conv, tools)` 拿 `BlockingQueue<StreamEvent>`（Agent.java:126）；
 - `streamQueue.poll(30, TimeUnit.SECONDS)` 超时推 `Stream timeout` 错误（Agent.java:139, 145-148）；
 - switch pattern matching 七路：`TextDelta` → `StreamText`；`ThinkingDelta` → `ThinkingText`；`ThinkingComplete` 入 `thinkingBlocks` + 转发；`ToolCallStart` / `ToolCallComplete` 转发并把后者入 `toolCalls`；`StreamEnd` 抓 stop_reason 与 token 用量；`Error` 抓 `lastStreamError` 推 `ErrorEvent`（Agent.java:150-179）；
 - `StreamEnd` / `Error` 命中跳出消费循环（Agent.java:181）。

## T6: 实现错误恢复（context / rate-limit / max_tokens）
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:184-233`
- 依赖任务: T5
- 完成标准:
 - stream 错误 + 错误文本含 `context` / `too long` / `prompt` → `contextRetries < 3` 时 `ContextCompactor.forceCompact` 后 `RetryEvent("Context too long, compacting...", 0)` continue（Agent.java:186-196）；
 - 错误文本含 `rate limit` → 推 `RetryEvent("Rate limited, waiting 5s...", 5000)`，`Thread.sleep(5000)` 后 continue（Agent.java:197-201）；
 - stop_reason `max_tokens` 首次：`AnthropicClient.setMaxOutputTokens(MAX_TOKENS_CEILING)` + 写助手已生成内容 + user "Output token limit hit. Resume directly from where you stopped..." + `RetryEvent("max_tokens escalation", 0)` continue（Agent.java:210-221）；
 - `maxTokensEscalated` 后再次命中：`outputRecoveries < MAX_OUTPUT_RECOVERIES` 时写助手 + user "Break remaining work into smaller pieces." + 计数器自增 continue（Agent.java:222-229）。

## T7: 实现工具调用收尾与会话写回
- 影响文件: `src/main/java/com/cncode/agent/Agent.java:235-263`
- 依赖任务: T5
- 完成标准:
 - `conv.addAssistantFull(text, thinkingBlocks, toolUseBlocks)` 写回助手（Agent.java:236-239）；
 - 无 tool_call → 推 `TurnComplete(iteration)` + `LoopComplete(iteration)` 后 break（Agent.java:242-246）；
 - 有 tool_call → `new StreamingExecutor(...).executeAll(callInfos)` 拿结果（Agent.java:249-253）；
 - `conv.addToolResultsMessage(resultBlocks)` 写回（Agent.java:256-259）；
 - 末尾推 `TurnComplete(iteration)`（Agent.java:261）。

## T8: 实现 StreamingExecutor 分流并发
- 影响文件: `src/main/java/com/cncode/agent/StreamingExecutor.java:23-72`
- 依赖任务: T1
- 完成标准:
 - 按 `ToolCategory.READ` 拆 `readCalls` / `otherCalls`（StreamingExecutor.java:42-51）；
 - readCalls `> 1` 时 `Executors.newVirtualThreadPerTaskExecutor()` 并发跑 `executeSingle` 收集 future（StreamingExecutor.java:55-64）；
 - readCalls `<= 1` 串行（StreamingExecutor.java:65-67）；
 - otherCalls 全部串行（StreamingExecutor.java:69）。

## T9: 实现 StreamingExecutor 单次执行（hook / 权限 / 截断）
- 影响文件: `src/main/java/com/cncode/agent/StreamingExecutor.java:74-149`
- 依赖任务: T8
- 完成标准:
 - 未知工具直接 `Unknown tool` 错误（StreamingExecutor.java:75-79）；
 - PreToolUse hook rejected 时 `Rejected by hook: ...` 错误（StreamingExecutor.java:82-89）；
 - 权限决策三分支：DENY 直接 `Permission denied: ...`；ASK 推 `PermissionRequestEvent` + `future.get(5, MINUTES)`，超时按 DENY；`ALLOW_ALWAYS` 调 `checker.addAllowAlwaysRule(toolName, extractContent(...))`（StreamingExecutor.java:91-122）；
 - `tool.execute(args)` 计 elapsed 秒 + 输出超 `MAX_OUTPUT_CHARS=10_000` 截断追加 `... (truncated)`（StreamingExecutor.java:125-137）；
 - 推 `ToolResultEvent(toolId, toolName, output, isError, elapsed)` + 跑 PostToolUse hook（StreamingExecutor.java:139-145）。

## T10: 接入主流程（TUI / CNCodeModel）
- 影响文件:
 - `src/main/java/com/cncode/tui/CNCodeModel.java:432-438` 构造 `new Agent(client, registry, protocol)` + 注入依赖
 - `src/main/java/com/cncode/tui/CNCodeModel.java:952` / `:1028` 调 `agent.run(conversation)` 拿 queue
 - `src/main/java/com/cncode/CNCode.java:14` `main` 启动 `Program(model).run()` 跑 TUI
- 依赖任务: T1~T9
- 完成标准: TUI 收到用户输入 → `agent.run` → `Command.tick(POLL_INTERVAL, ...)` 周期 drain queue → 把 `AgentEvent` 映射成 `ChatMessage` 渲染。

## T11: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T10
- 完成标准:
 - `./gradlew build` 通过；
 - `./gradlew test` 通过（现有测试集 `src/test/java/com/cncode/teams/FileMailBoxTest.java` / `src/test/java/com/cncode/tool/ToolSearchTest.java`，无 Agent 直接单测，本章靠手动 TUI 演练验收）；
 - TUI 启动 → 提问 `读一下 README.md` → 队列中能依序观察到 `StreamText` / `ToolUseEvent(ReadFile)` / `ToolResultEvent` / `TurnComplete` / `LoopComplete`。

## 进度
- [ ] T1 AgentEvent sealed interface
- [ ] T2 Agent 骨架 + DI
- [ ] T3 run 入口 + 虚拟线程
- [ ] T4 轮次起手注入
- [ ] T5 StreamEvent 消费
- [ ] T6 错误恢复
- [ ] T7 工具调用收尾
- [ ] T8 StreamingExecutor 分流
- [ ] T9 StreamingExecutor 单次执行
- [ ] T10 TUI 接入
- [ ] T11 端到端验证


# ch04: Agent Loop Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性
- [ ] `AgentEvent` sealed interface 在 `src/main/java/com/cncode/agent/AgentEvent.java:8`，13 个 record 实现齐全（`grep -nE "record [A-Z][A-Za-z]+\(" src/main/java/com/cncode/agent/AgentEvent.java` 返回 13 条）
- [ ] `AgentEvent.PermissionRequestEvent` 在 AgentEvent.java:33-34 含 `CompletableFuture<PermissionResponse>` 字段
- [ ] `Agent` 类在 `src/main/java/com/cncode/agent/Agent.java:19`，常量 `MAX_TOKENS_CEILING=64_000`（Agent.java:21）、`MAX_OUTPUT_RECOVERIES=3`（Agent.java:22）
- [ ] `Agent.run` 在 Agent.java:50 返回 `LinkedBlockingQueue<>(64)`，`Thread.startVirtualThread` 在 Agent.java:52
- [ ] `Agent.agentLoop` 在 Agent.java:62，主循环 `for (int iteration = 1; ; iteration++)` 在 Agent.java:69
- [ ] 通知 drain + `conv.addSystemReminder` 在 Agent.java:79-83
- [ ] `ContextCompactor.manage` 调用在 Agent.java:87，`CompactEvent` 推送在 Agent.java:89
- [ ] deferred tool reminder 注入在 Agent.java:94-104，调 `registry.getDeferredToolNames()`
- [ ] Plan Mode reminder 注入在 Agent.java:107-114，调 `PlanModePrompt.buildReminder`
- [ ] `client.stream(conv, tools)` 调用在 Agent.java:126，`streamQueue.poll(30, SECONDS)` 在 Agent.java:139
- [ ] StreamEvent 七路 switch pattern matching 在 Agent.java:150-179，覆盖 `TextDelta` / `ThinkingDelta` / `ThinkingComplete` / `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd` / `Error`
- [ ] 错误恢复三分支：context 在 Agent.java:186-196，rate limit 在 Agent.java:197-201，max_tokens 在 Agent.java:210-229
- [ ] `conv.addAssistantFull` 在 Agent.java:239，`conv.addToolResultsMessage` 在 Agent.java:259
- [ ] 无 tool_use 收尾：`TurnComplete` + `LoopComplete` 在 Agent.java:243-245
- [ ] `StreamingExecutor` 在 `src/main/java/com/cncode/agent/StreamingExecutor.java:23`
- [ ] 读 / 写分流在 StreamingExecutor.java:42-51，虚拟线程并发在 StreamingExecutor.java:55-64（`Executors.newVirtualThreadPerTaskExecutor()`）
- [ ] 权限 ASK 分支用 `CompletableFuture<PermissionResponse>` + 5 分钟超时在 StreamingExecutor.java:99-108
- [ ] 工具输出截断 `MAX_OUTPUT_CHARS=10_000` 在 StreamingExecutor.java:135-137（`ToolRegistry.MAX_OUTPUT_CHARS` 定义在 `src/main/java/com/cncode/tool/ToolRegistry.java:7`）

## 2. 接入完整性（必查，杜绝死代码）
- [ ] `grep -rn "new Agent(" --include="*.java" src/main/java` 返回 ≥ 1 处真实调用（`src/main/java/com/cncode/tui/CNCodeModel.java:432`）
- [ ] `grep -rn "agent.run(" --include="*.java" src/main/java` 返回 ≥ 2 处（`CNCodeModel.java:952`、`CNCodeModel.java:1028`）
- [ ] `grep -rn "new StreamingExecutor" --include="*.java" src/main/java` 返回 ≥ 1 处（`Agent.java:249`）
- [ ] `grep -rn "BlockingQueue<AgentEvent>" --include="*.java" src/main/java` 返回 ≥ 3 处（Agent.run、StreamingExecutor 构造、CNCodeModel 接收）
- [ ] TUI 调用链：用户提问 → `CNCodeModel.update` 收到 `UserInputMsg` → `agent.run(conversation)`（CNCodeModel.java:952/1028）→ `Command.tick(POLL_INTERVAL, t -> new AgentEventMessage())` 周期 drain queue
- [ ] Agent 调用链：每轮 → 通知注入（Agent.java:79）→ compact（:87）→ deferred reminder（:94）→ plan reminder（:107）→ `client.stream`（:126）→ `StreamingExecutor.executeAll`（Agent.java:253 / StreamingExecutor.java:41）→ 写回会话（:239/:259）

## 3. 编译与测试
- [ ] `./gradlew build` 通过（顶层命令验证）
- [ ] `./gradlew test` 通过（现有测试集仅 `FileMailBoxTest` / `ToolSearchTest`，无 Agent 直接单测，靠 TUI 端到端验收）
- [ ] `./gradlew compileJava` 无 unchecked / preview 警告（pattern matching for switch 在 Java 21+ 已 GA）

## 4. 端到端验证
- [ ] TUI 启动 → 选 provider → 提问 `读一下 README.md` → 队列中依序观察到 `StreamText` / `ToolUseEvent(toolName="ReadFile")` / `ToolResultEvent(isError=false)` / `TurnComplete` / `LoopComplete`
- [ ] 多读连发：提问 `同时读 README.md 和 build.gradle.kts`，观察 `StreamingExecutor` 走并发分支（两个 `ReadFile` ToolResultEvent 几乎同时到达，elapsed 接近）
- [ ] 权限 ASK 流程：在 ACCEPT_EDITS 模式下让 Agent 跑 `Bash`，观察 `PermissionRequestEvent` 弹 dialog → 用户 ALLOW → 工具继续执行
- [ ] max_tokens 恢复：构造长输出任务，观察 `RetryEvent("max_tokens escalation", 0)` 后助手续写到完整答案
- [ ] context 超限恢复：手工塞超长对话历史触发 `Error("context too long")` → `RetryEvent("Context too long, compacting...", 0)` → `ContextCompactor.forceCompact` 后继续
- [ ] 留存证据：验收阶段未保存日志；若要补，可在 TUI 输入指定提问后保存 `AgentEvent` 队列 trace

## 5. 文档
- [ ] spec.md / tasks.md / checklist.md 三件套齐全（`docs/java/ch04/`）
- [ ] commit 信息标注 `ch04` 与三件套关闭状态（待统一打包提交）






# ch05: System Prompt 设计 Spec

## 1. 背景

没有 System Prompt，模型并不知道自己叫 CN Code、不知道运行在什么 OS、不知道有哪些工具能用、不知道用户的代码规范，输出会落到「通用 ChatGPT 助手」基线。所有静态规则（语气、安全、工具使用规范）和环境信息必须固化到 System Prompt 才能让模型回答稳定、可预期；动态指令（Plan Mode reminder、Task notification、deferred tool 列表）则走 user channel 的 `<system-reminder>` 块，避免反复改 System 失效缓存。本章把这条 prompt 拼接管线做出来。

## 2. 目标

对外提供 `com.cncode.prompt`：调用者准备好工作目录、模型名、（可选）项目说明 / Skill 列表 / Memory 段，调一次 `PromptBuilder.buildSystemPrompt(env, opts)` 拿到能直接喂给 LLM 客户端的纯文本 System Prompt。多个信息来源（角色、行为准则、工具规范、tone、文本输出风格、环境上下文、项目说明、Skill 摘要、Memory）按优先级合并；动态注入走 `ConversationManager.addSystemReminder` + ch04 主循环。

## 3. 功能需求

- F1: 提供环境探测函数 `detectEnvironment(model)`，输出工作目录、OS、Arch、Shell、是否 Git 仓库、当前分支、模型名、日期等字段；Git 状态用标准命令探测，非 Git 仓库静默降级。
- F2: 提供 `buildSystemPrompt(env, opts)` 主入口，装配 8 个固定 section（Identity / System / DoingTasks / ExecutingActions / UsingTools / ToneStyle / TextOutput / Environment）外加 3 个可选 section（CustomInstructions / Skills / Memory），按优先级排序后拼接。
- F3: `BuildOptions` 接收项目说明 / Skill 摘要 / Memory 三类可选字符串；`null` 或空字符串不进入最终输出。
- F4: 提供 `PromptBuilder` 实例 + `Section` record 支持自定义扩展：调用者可空 builder 起步、自由 `add` section、指定优先级，最后 `build()` 排序输出。
- F5: 各 section 有固定优先级（Identity 最高、Memory 最低，可选 section 排在固定 section 之后），保证最终 prompt 顺序稳定。
- F6: Plan Mode 系统提醒不进入 System Prompt，由 `com.cncode.prompt.PlanModePrompt` 提供构造函数，由 ch04 主循环通过 `addSystemReminder` 注入 user channel。
- F7: 各 section 文案需保持与终端 Agent 系统提示语义一致：禁用 emoji、优先用专用工具、文件路径引用用 `file_path:line_number`、状态报告诚实、对潜在 prompt injection 进行 flag、`<system-reminder>` 与具体 tool 结果无直接关系等关键短语保留。
- F8: 每个 `Tool` 实现的 `description()` 方法返回固定字符串（Java text block），由 ToolRegistry 拼装到 LLM tools 数组中传给模型，作为 System Prompt 的工具规范补充。

## 4. 非功能需求

- N1: System Prompt 内容必须能被 LLM 长缓存命中——只在切 provider / 工作目录 / Skill / Memory 时重建，每轮迭代不重新构建。
- N2: 环境探测在 Git 不存在时静默降级（捕获 `Exception` 后 ignore），不输出错误日志。
- N3: 日期字段使用稳定格式（`LocalDate.now().toString()`，即 ISO `YYYY-MM-DD`），跨进程一致。
- N4: section 之间用恰好两个换行分隔，section 内部用单换行；空 section 不出现。
- N5: 文案不使用 emoji（除非用户在 ToneStyle section 内显式说明）。

## 5. 设计概要

- 核心数据结构: `Section{name, priority, content}` record、`EnvironmentContext{workDir, os, arch, shell, isGitRepo, gitBranch, model, date}` record、`BuildOptions{customInstructions, skillSection, memorySection}` record、`PromptBuilder{sections}` 类。
- 主流程:
 1. TUI 选好 provider → 调 `MemoryManager` 加载 `AGENTS.md` / `CNCODE.md` 合并文本；
 2. 调 `PromptBuilder.detectEnvironment(model)` 拿环境上下文；
 3. 调 `PromptBuilder.buildSystemPrompt(env, opts)` 拼出 system prompt；
 4. system prompt 喂给 `LlmClient.create(provider, systemPrompt)`。
 - `buildSystemPrompt` 内部依次 `add` 8 固定 section + 3 可选 section，最后排序拼接。
- 调用链:
 - `CNCodeModel.initializeProvider()` 切 provider 时调 `buildSystemPrompt`，输出作为 `LlmClient.create` 第二参数。
 - 动态注入：Agent 主循环在 PLAN 模式时调 `PlanModePrompt.buildReminder` → `conv.addSystemReminder`，最终包成 `<system-reminder>` user 消息。
- 与其他模块的交互:
 - 依赖 JDK 21 标准库（`java.lang.ProcessBuilder` / `java.time.LocalDate` / `java.util.Comparator`），不依赖项目其他模块。
 - 被 `com.cncode.tui.CNCodeModel`（构造 prompt）、`com.cncode.agent.Agent`（Plan Mode reminder 注入）使用。
 - 输入数据由 `com.cncode.memory.MemoryManager` 等模块准备好后传入。

## 6. Out of Scope

- Coordinator Mode / 自定义 Agent 角色的 system prompt 替换分支不在本章实现，所有 Agent 共用默认 prompt。
- 不缓存 section 输出。
- Plan Mode Reentry / Exit 提醒函数已写但未接入 TUI，留给下章或专门 PR。
- 不实现外部 `--system-prompt` / `appendSystemPrompt` CLI 参数。
- Skill 摘要的具体来源与拼装由 ch10 负责，本章只接收已拼好的字符串。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch05: System Prompt 设计 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。本章为验收，所有任务已经在仓库里落地（origin/java 分支）。

## T1: 定义 Section / Builder / BuildOptions 数据结构
- 影响文件: `src/main/java/com/cncode/prompt/PromptBuilder.java:17-36`
- 依赖任务: 无
- 完成标准: `Section(String name, int priority, String content)` record（PromptBuilder.java:17）、`EnvironmentContext(workDir/os/arch/shell/isGitRepo/gitBranch/model/date)` record（PromptBuilder.java:19-27）、`BuildOptions(customInstructions, skillSection, memorySection)` record（PromptBuilder.java:29-32）全部定义；`PromptBuilder.add` 返回 `this` 支持链式调用（PromptBuilder.java:38-41）。

## T2: 实现 detectEnvironment
- 影响文件: `src/main/java/com/cncode/prompt/PromptBuilder.java:59-105`
- 依赖任务: T1
- 完成标准: `System.getProperty("user.dir")`、`System.getProperty("os.name")`、`System.getProperty("os.arch")`、`SHELL` 环境变量（缺省 `bash`，PromptBuilder.java:63-66）、`LocalDate.now().toString()`（PromptBuilder.java:103）入填；git 检测使用 `ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")` 静默判断（PromptBuilder.java:71-84），是 git repo 再跑 `--abbrev-ref HEAD` 拿到 branch（PromptBuilder.java:86-101）。

## T3: 实现 PromptBuilder.build 排序 + 拼接
- 影响文件: `src/main/java/com/cncode/prompt/PromptBuilder.java:43-54`
- 依赖任务: T1
- 完成标准: `build()` 用 `Comparator.comparingInt(Section::priority)` 升序排（PromptBuilder.java:44）；`strip()` 后空 content 不进入 parts（PromptBuilder.java:48-51）；`String.join("\n\n", parts)` 输出最终文本（PromptBuilder.java:53）。

## T4: 实现 8 个固定 section 方法
- 影响文件: `src/main/java/com/cncode/prompt/PromptSections.java`
- 依赖任务: T1
- 完成标准:
 - `identitySection()`（priority 0，PromptSections.java:27）—— CN Code 身份 + 安全 / URL 不乱造
 - `systemSection()`（priority 10，PromptSections.java:48）—— `<system-reminder>` 语义、prompt injection 警告、hook feedback、自动 compact
 - `doingTasksSection()`（priority 20，PromptSections.java:93）—— 不做未读过的代码、最小修改原则、不写无用注释、报真实结果
 - `executingActionsSection()`（priority 30，PromptSections.java:119）—— 高破坏性操作需 confirm
 - `usingToolsSection()`（priority 40，PromptSections.java:156）—— Tool 优先 / TaskCreate / 并行调用 / Agent / ToolSearch
 - `toneStyleSection()`（priority 50，PromptSections.java:171）—— 不用 emoji / 简短 / 用 `file_path:line_number` / 工具调用前别打冒号
 - `outputEfficiencySection()`（priority 60，PromptSections.java:197）—— 文本输出一句话规划、少注释、end-of-turn summary
 - `environmentSection(env)`（priority 70，PromptSections.java:203）—— 把 `EnvironmentContext` 渲染成 5~8 行环境信息块

## T5: 实现 buildSystemPrompt 主入口
- 影响文件: `src/main/java/com/cncode/prompt/PromptBuilder.java:108-134`
- 依赖任务: T2, T3, T4
- 完成标准: 先 `add` 8 个固定 section（PromptBuilder.java:111-118），再依据 `opts.customInstructions()`（priority 80，PromptBuilder.java:120-123） / `opts.skillSection()`（priority 90，PromptBuilder.java:125-127） / `opts.memorySection()`（priority 95，PromptBuilder.java:129-131）按需 `add`；`null` 或空字符串不 `add`。

## T6: 实现 Plan Mode 动态指令
- 影响文件: `src/main/java/com/cncode/prompt/PlanModePrompt.java`
- 依赖任务: 无
- 完成标准: `PLAN_MODE_FULL_REMINDER`（PlanModePrompt.java:11）+ `PLAN_MODE_SPARSE_REMINDER`（PlanModePrompt.java:101）+ `PLAN_MODE_REENTRY_REMINDER`（PlanModePrompt.java:107）+ `PLAN_MODE_EXIT_REMINDER`（PlanModePrompt.java:127）四段模板；`REMINDER_INTERVAL=5`（PlanModePrompt.java:9）；`buildReminder(planPath, planExists, iteration)`（PlanModePrompt.java:141）在 iteration==1 给完整版，否则按 5 次为周期间断重发完整版，其余给稀疏版；`buildReentryReminder`（PlanModePrompt.java:164） / `buildExitReminder`（PlanModePrompt.java:169）已实现但 TUI 当前未调用（保留作为后续接入点）。

## T7: 接入主流程（TUI / Agent）
- 影响文件:
 - `src/main/java/com/cncode/tui/CNCodeModel.java:382PromptBuilder.detectEnvironment(model)`
 - `src/main/java/com/cncode/tui/CNCodeModel.java:385-388new PromptBuilder.BuildOptions(...)`
 - `src/main/java/com/cncode/tui/CNCodeModel.java:389PromptBuilder.buildSystemPrompt(env, options)`
 - `src/main/java/com/cncode/tui/CNCodeModel.java:391LlmClient.create(selectedProvider, systemPrompt)`
 - `src/main/java/com/cncode/agent/Agent.java:112PlanModePrompt.buildReminder(planPath, planExists, iteration)`
- 依赖任务: T1~T6
- 完成标准: `CNCodeModel.initializeProvider()` 在选 provider 阶段一次性构造 System Prompt（CNCodeModel.java:382-391）；`Agent.agentLoop` 在 PLAN 模式下每轮调 `PlanModePrompt.buildReminder` 并写入 `conv.addSystemReminder`（Agent.java:107-113），最终走 user 通道的 `<system-reminder>` 块。

## T8: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T7
- 完成标准:
 - `./gradlew build` 通过（顶层命令验证）。
 - `./gradlew test` 通过（虽然 ch05 没有专门的 prompt 单测，但 `CNCodeModel` 与 `Agent` 的整体编译与冒烟测试覆盖了 prompt 装配链路）。
 - 在 TUI 启动后 `/plan` 进入计划模式，下一轮 agent stream 注入完整版 Plan Mode reminder（5 阶段 Workflow 文本可在 TUI 的请求日志中观察到）。

## 进度
- [ ] T1 数据结构（record）
- [ ] T2 detectEnvironment
- [ ] T3 PromptBuilder.build
- [ ] T4 8 个固定 section 方法
- [ ] T5 buildSystemPrompt 主入口
- [ ] T6 Plan Mode 动态指令
- [ ] T7 TUI / Agent 接入
- [ ] T8 端到端验证（`./gradlew build` 通过 + CNCodeModel.java:389 调用通过 + Plan reminder 在 Agent.java:112 接入）


# ch05: System Prompt 设计 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性
- [ ] 数据结构 `Section(String name, int priority, String content)` record 在 `src/main/java/com/cncode/prompt/PromptBuilder.java:17`（`grep -n "record Section" src/main/java/com/cncode/prompt/PromptBuilder.java`）
- [ ] 数据结构 `EnvironmentContext` 8 字段 record 在 `src/main/java/com/cncode/prompt/PromptBuilder.java:19-27`
- [ ] 数据结构 `BuildOptions(customInstructions, skillSection, memorySection)` record 在 `src/main/java/com/cncode/prompt/PromptBuilder.java:29-32`
- [ ] 静态方法 `detectEnvironment` 在 `src/main/java/com/cncode/prompt/PromptBuilder.java:59`，git 探测在 PromptBuilder.java:71-84，shell 缺省 bash 在 PromptBuilder.java:63-66
- [ ] 静态方法 `buildSystemPrompt` 在 `src/main/java/com/cncode/prompt/PromptBuilder.java:108`，按八段 + 三可选段顺序 `add`
- [ ] 8 个固定 section 方法：`identitySection`(PromptSections.java:27) / `systemSection`(:48) / `doingTasksSection`(:93) / `executingActionsSection`(:119) / `usingToolsSection`(:156) / `toneStyleSection`(:171) / `outputEfficiencySection`(:197) / `environmentSection`(:203)
- [ ] Priority 数字固定：0/10/20/30/40/50/60/70，对应 8 个 section（`grep -nE "new Section\(" src/main/java/com/cncode/prompt/PromptSections.java` 返回 8 条）
- [ ] 可选 section Priority 数字：80 / 90 / 95（CustomInstructions / Skills / Memory，PromptBuilder.java:121/126/130）
- [ ] Plan Mode 动态指令：`buildReminder` 在 `src/main/java/com/cncode/prompt/PlanModePrompt.java:141`；`REMINDER_INTERVAL=5` 在 PlanModePrompt.java:9
- [ ] 关键文本片段保留：`build()` 输出含 `IMPORTANT: Be careful not to introduce security` / `<system-reminder>` / `Only use emojis if the user explicitly requests it` / `file_path:line_number`（可通过 `grep -n` 验证 PromptSections.java）
- [ ] 每个 Tool 实现的 `description()` 返回 Java text block 静态描述（例如 `BashTool.java:17-39` 的 `DESCRIPTION` 常量、`ReadFileTool.java:15-24`、`EditFileTool.java:15-24`）

## 2. 接入完整性（必查，杜绝死代码）
- [ ] `grep -rn "PromptBuilder.buildSystemPrompt" --include="*.java" src` 返回至少 1 处真实调用（`src/main/java/com/cncode/tui/CNCodeModel.java:389`）
- [ ] `grep -rn "PromptBuilder.detectEnvironment" --include="*.java" src` 返回至少 1 处（`src/main/java/com/cncode/tui/CNCodeModel.java:382`）
- [ ] `grep -rn "PlanModePrompt.buildReminder" --include="*.java" src` 返回 ≥ 1 个主流程调用（`src/main/java/com/cncode/agent/Agent.java:112`）
- [ ] TUI 调用链：用户选 provider → `CNCodeModel.initializeProvider`（CNCodeModel.java:376）→ `buildSystemPrompt`（CNCodeModel.java:389）→ `LlmClient.create`（CNCodeModel.java:391）
- [ ] Agent 调用链：每轮迭代 → `Agent.java:107-114` 判断 `PermissionMode.PLAN` → 调 `PlanModePrompt.buildReminder` → `conv.addSystemReminder`
- [ ] 已记录死代码（不在本章 must-fix）:
 - [ ] `src/main/java/com/cncode/prompt/PlanModePrompt.java:164 buildReentryReminder` 无调用方（`grep -rn "buildReentryReminder" --include="*.java" src` 只返回定义点）
 - [ ] `src/main/java/com/cncode/prompt/PlanModePrompt.java:169 buildExitReminder` 无调用方（同上）
 - 处理意见: 已抄自目标设计；TUI 当前 `/do` 命令未注入 exit reminder。记录已知；后续如要补，调用点应放在 `CNCodeModel` 处理 `/do` 子命令附近

## 3. 编译与测试
- [ ] `./gradlew build` 通过（顶层命令验证；本次验收已跑）
- [ ] `./gradlew test` 通过（虽然 ch05 没专门的 prompt 单测，整体编译与冒烟测试覆盖了 prompt 装配链路）
- [ ] `./gradlew compileJava` 对 `prompt` 包零警告（IDE 或 `javac -Xlint:all` 抽查 PromptBuilder / PromptSections / PlanModePrompt）

## 4. 端到端验证
- [ ] TUI 启动 → 选 provider → `buildSystemPrompt` 一次 → `LlmClient.create` 拿到 system prompt（`src/main/java/com/cncode/tui/CNCodeModel.java:382-391`）
- [ ] `/plan` 命令进入 Plan Mode → Agent Run 下一轮在 stream 之前注入 `<system-reminder>` 包裹的 5 阶段 Workflow（`src/main/java/com/cncode/agent/Agent.java:107-113` + `ConversationManager.addSystemReminder`）
- [ ] 留存证据: 验收阶段未保存日志；若要补，可在 TUI 输入 `/plan` 后看一次请求 body 中的 user `<system-reminder>` 内容

## 5. 文档
- [ ] spec.md / tasks.md / checklist.md 三件套齐全（`docs/java/ch05/`）
- [ ] commit 信息标注 `ch05` 与三件套关闭状态（待统一打包提交）






# ch06: 权限系统 Spec

## 1. 背景

工具系统（ch03）放出了 Bash 和写文件的能力，Agent Loop（ch04）能自主决定调谁；没有权限层，模型一句话就能 `rm -rf /` 或者写到项目目录之外。一个生产级 Coding Agent 的最低安全要求是「至少要拦得住明显的危险操作 + 把不熟悉的操作交给用户决定」。本章把这条防御线做出来：明显错的直接拦、明显对的直接放，剩下的让规则 / 模式 / HITL 决定。

## 2. 目标

对外提供 `PermissionChecker`：调用方传入模式 + 项目根目录构造好之后，对任意 `Tool` + 参数 `Map<String, Object>` 调一次 `check` 拿到 `CheckResult(decision, reason)`。`StreamingExecutor` 直接用这个决策决定是否拦截 / 直接执行 / 走 HITL。权限模式支持 DEFAULT / ACCEPT_EDITS / PLAN / BYPASS 四种，TUI 用 Shift+Tab 或 `/plan` `/do` 在它们之间切换；PLAN 模式拥有特殊豁免分支。HITL 用户选 `ALLOW_ALWAYS` 时把规则 append 到本地规则文件并热重载。

## 3. 功能需求

- F1: 提供权限模式枚举（`DEFAULT` / `ACCEPT_EDITS` / `PLAN` / `BYPASS`）与模式 × 工具类别（READ / WRITE / COMMAND）的决策矩阵，通过 `PermissionMode.decide(ToolCategory)` 暴露查表。
- F2: Layer 1 危险命令检测：硬编码覆盖 `rm -rf /` 类删除、`mkfs.` 磁盘格式化、`dd ... of=/dev/` 设备写入、`chmod -R 777 /`、fork bomb、`curl | sh` / `wget | sh` 类远程执行、`> /dev/sd` 共 8 条核心模式，命中即拒绝并给出原因。
- F3: Layer 1 安全命令白名单：维护只读 Bash 命令前缀表（`ls` / `pwd` / `cat` / `git status` / `go version` 等），命中且命令中不含管道 / 重定向 / 子 shell / 命令分隔符 / 命令替换时直接放行。
- F4: Layer 2 路径沙箱：构造时持有项目根目录 `projectRoot`；`isPathAllowed(pathStr)` 对入参做 `Path.toAbsolutePath().normalize()` 后判断是否 `startsWith(projectRoot)` 或 `startsWith(/tmp)`，仅对 `ReadFile` / `WriteFile` / `EditFile` 三个路径工具生效。
- F5: Layer 3 规则引擎：管理 user / project / local 三层 YAML 规则文件，加载顺序 user → project → local，整体合并到 `fileRules` 列表；`check` 时从尾向前 LIFO 匹配；`PermissionRule(toolName, pattern, effect)` 用 `PathMatcher("glob:" + pattern)` 匹配主参数；提供 `appendLocalRule(toolName, pattern)` 写回本地规则文件并热重载。
- F6: 规则语法 `ToolName(pattern)` 用正则 `^(\w+)\((.+)\)$` 解析，effect 仅 `allow` / `deny`；YAML 文件结构为 `[{rule, effect}, ...]` 列表。
- F7: 内容字段提取：`CONTENT_FIELDS` 映射 6 个核心工具到主参数字段名（Bash→`command`、ReadFile/WriteFile/EditFile→`file_path`、Glob/Grep→`pattern`），其他工具返回 `null`。
- F8: `PermissionChecker.check` 按固定顺序逐层判定：PLAN 模式豁免（白名单工具 + plan 文件路径写入）→ 安全命令直放 → 危险命令直拒 → 路径沙箱 → 文件规则 LIFO → 会话级 `allowAlwaysRules` → 模式矩阵兜底。
- F9: 会话级自学习：HITL 用户选 `ALLOW_ALWAYS` 时，`StreamingExecutor` 调用 `checker.addAllowAlwaysRule(toolName, content)` 把当前调用注册到内存 Set（即时生效），可选地由调用方再走 `appendLocalRule` 持久化到本地 YAML。

## 4. 非功能需求

- N1: 危险命令模式必须以 `List<Pattern>` 静态常量硬编码进代码（`DANGEROUS_PATTERNS`），不依赖外部下载或环境变量注入，避免被攻击者绕过。
- N2: 路径沙箱必须始终包含项目根 + `/tmp`；构造时把 `projectRoot` 存为 `Path` 字段，`isPathAllowed` 内对入参做 `toAbsolutePath().normalize()`，防止相对路径 / `..` 绕过。
- N3: 规则文件解析必须在 YAML 语法错误 / 文件不存在 / 类型不匹配时静默返回 `List.of()`，不让单个坏规则导致整套规则失效。`loadRulesFile` 内 try/catch 包住 `Yaml.load`。
- N4: `check` 是无副作用方法（除 `appendLocalRule` 的磁盘写入），只读 `fileRules` 与 `allowAlwaysRules`，不修改任何 in-memory 状态。
- N5: PLAN 模式的工具豁免分支必须早于沙箱检查，避免 plan 模式下写 `.cncode/plans/` 下的文件被沙箱误拦。

## 5. 设计概要

- 核心数据结构:
 - `PermissionMode.Decision`（`ALLOW` / `DENY` / `ASK`）与 `PermissionChecker.CheckResult(decision, reason)`。
 - `PermissionMode` 枚举与 `decide(ToolCategory)` 内嵌的 mode × category 决策表（`switch` 表达式）。
 - `PermissionResponse` 枚举（`ALLOW` / `ALLOW_ALWAYS` / `DENY`）用于 HITL 回调。
 - `PermissionRule(toolName, pattern, effect)` record + `RuleEffect` 内部枚举。
 - `PermissionChecker{mode, projectRoot, fileRules, allowAlwaysRules, planFilePath}`。
 - `PlanFile`（`com.cncode.plan`）：静态字段缓存当前 plan 路径，`getOrCreatePlanPath` / `planExists` / `isPlanFilePath`。
- 主流程（一次 `check` 调用）:
 - 用 `extractContent(toolName, args)` 抽出主参数 content。
 - PLAN 模式分支：`PLAN_MODE_ALLOWED_TOOLS`（Agent / ToolSearch / AskUserQuestion）或 `file_path` 包含 `.cncode/plans/` 时直接 `allow`。
 - Bash 工具：先 `isSafeCommand` 直放，再遍历 `DANGEROUS_PATTERNS` 直拒。
 - 路径类工具：`isPathTool` 命中后走 `isPathAllowed`。
 - 文件规则：`fileRules` 从尾向前匹配，命中按 `RuleEffect` 决定。
 - 会话级 allow-always：`allowAlwaysRules` 中 `toolName + ":" + content` 命中直放。
 - 落到 `mode.decide(tool.category())` 兜底。
- 调用链:
 - `CNCodeModel` 启动后构造 `new PermissionChecker(PermissionMode.DEFAULT, Path.of(workDir))` 并通过 `agent.setChecker(...)` 装配。
 - `StreamingExecutor.executeSingle` 执行工具前 → `checker.check(tool, args)` → `DENY` 给错误结果 / `ASK` 走 `PermissionRequestEvent` 与 `CompletableFuture<PermissionResponse>` 等待 5 分钟。
 - HITL 选 `ALLOW_ALWAYS` → `checker.addAllowAlwaysRule(toolName, content)`。
 - `/plan` 命令切到 `PermissionMode.PLAN` + 设置 plan 路径；`/do` 还原 `prePlanMode`。
 - Shift+Tab 在 4 种模式间循环切换。
- 与其他模块的交互:
 - 依赖 `com.cncode.tool.Tool`（`name()` / `category()` 接口）和 `ToolCategory`。
 - 依赖 SnakeYAML（`org.yaml.snakeyaml.Yaml`）做规则文件序列化。
 - 被 `com.cncode.agent.Agent` 与 `StreamingExecutor` 直接使用；被 `CNCodeModel` 装配并响应 `PermissionRequestEvent`。
 - Plan 模式 reminder 通过 `PlanModePrompt.buildReminder` 在 `Agent.agentLoop` 每轮注入到对话。

## 6. Out of Scope

- 不实现 LLM 分类器；本章纯静态规则。
- 不实现 PowerShell 危险命令检测，目前只覆盖 Bash。
- 不实现 user 与 project 级规则文件的写入，只写本地规则文件。
- 不实现规则文件热重载（仅 `appendLocalRule` 写入后手动 reload，其他改动需要重启）。
- 不实现目标设计中的额外模式（dontAsk / auto / bubble）。
- 不实现规则解释 UI（只在 reason 字符串里附原因）。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch06: 权限系统 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。本章为验收，所有任务已经在仓库里落地（`origin/java`）。

## T1: 定义决策与模式枚举
- 影响文件:
 - `src/main/java/com/cncode/permission/PermissionMode.java:5-29`
 - `src/main/java/com/cncode/permission/PermissionResponse.java:3-7`
 - `src/main/java/com/cncode/permission/PermissionChecker.java:100-104`
- 依赖任务: 无
- 完成标准: `PermissionMode` 枚举四态 `DEFAULT / ACCEPT_EDITS / PLAN / BYPASS`；`PermissionMode.Decision` 三态 `ALLOW / DENY / ASK`；`PermissionMode.decide(ToolCategory)` 用 `switch` 表达式覆盖 4×3=12 格（PLAN 复用 DEFAULT 的判定）；`PermissionResponse` 三态 `ALLOW / ALLOW_ALWAYS / DENY`；`PermissionChecker.CheckResult(decision, reason)` 提供 `allow()` / `deny(reason)` / `ask()` 静态工厂。

## T2: 实现 Layer 1 危险命令检测
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:70-79, 128-135`
- 依赖任务: 无
- 完成标准: `DANGEROUS_PATTERNS` 静态常量列出 8 条 `java.util.regex.Pattern`（`rm -rf /`、`mkfs.`、`dd if=...of=/dev/`、`chmod -R 777 /`、fork bomb `:(){:|:&};:`、`curl ... | sh`、`wget ... | sh`、`> /dev/sd`）；`check` 内 Bash 分支遍历该列表，命中即返回 `CheckResult.deny("Dangerous command detected: " + pattern.pattern())`。

## T3: 实现 Layer 1 安全命令白名单
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:56-68, 292-304`
- 依赖任务: 无
- 完成标准: `SAFE_COMMANDS` 是 `Set.of(...)`，覆盖 50+ 个只读命令前缀（含 `git status` 等 git 只读子命令、`go version`、`java -version` 等）；`isSafeCommand(command)` 检查 trimmed 命令不含 `|` / `;` / `&&` / `>` / `$(` / 反引号，且 `equals` 或 `startsWith(safe + " ")` 任一前缀，才返回 `true`。

## T4: 实现 Layer 2 路径沙箱
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:23-24, 90-94, 137-142, 306-319`
- 依赖任务: 无
- 完成标准: 构造函数接收 `Path projectRoot` 并保存为字段；`isPathTool(toolName)` 判定是否 `ReadFile` / `WriteFile` / `EditFile`；`isPathAllowed(pathStr)` 将入参与 `projectRoot`、`/tmp` 全部 `toAbsolutePath().normalize()` 后做 `startsWith` 检查，任一匹配即放行；异常情况返回 `true`（保守不拦，由后续层兜底）。

## T5: 实现 Layer 3 规则引擎
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:28-46, 144-160, 175-227, 229-290`
- 依赖任务: 无
- 完成标准:
 - `PermissionRule(toolName, pattern, effect)` 是 `private record`，`matches(toolName, content)` 用 `FileSystems.getDefault().getPathMatcher("glob:" + pattern)` 做 glob，匹配失败兜底为 `content.equals(pattern)`。
 - `RuleEffect { ALLOW, DENY }` 是 `private enum`。
 - `loadRules()` 顺序加载 `~/.cncode/permissions.yaml`、`{projectRoot}/.cncode/permissions.yaml`、`{projectRoot}/.cncode/permissions.local.yaml`，合并到 `fileRules` 列表。
 - `check` 内 `for (int i = fileRules.size() - 1; i >= 0; i--)` LIFO 匹配。
 - `loadRulesFile(path)` 用 SnakeYAML 解析 `List<Map<String,String>>` 形式，YAML 异常 / 类型错误 / 规则格式错误均静默 `continue` 或返回 `List.of()`；`RULE_PATTERN = "^(\\w+)\\((.+)\\)$"` 正则解析 `ToolName(pattern)`。
 - `appendLocalRule(toolName, pattern)` 自动 `Files.createDirectories(localFile.getParent())`，合并现有规则后用 `Yaml.dump` 重写本地 YAML，并 `fileRules.clear(); fileRules.addAll(loadRules())` 热重载。

## T6: 实现内容字段提取
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:81-88, 321-326, 328-331`
- 依赖任务: 无
- 完成标准: `CONTENT_FIELDS` 是 `Map.of(...)`，覆盖 Bash→`command`、ReadFile/WriteFile/EditFile→`file_path`、Glob/Grep→`pattern` 共 6 项；`extractContent(toolName, args)` 查表后从 `args` 取出对应字段（仅当值是 `String`）；`stringArg(args, key, default)` 提供安全字符串读取。

## T7: 实现主入口 PermissionChecker.check
- 影响文件: `src/main/java/com/cncode/permission/PermissionChecker.java:106-169`
- 依赖任务: T1~T6
- 完成标准: `check(Tool tool, Map<String, Object> args)` 按以下顺序逐层判断：
 1. Layer 0 PLAN 模式豁免（`PLAN_MODE_ALLOWED_TOOLS` 集合 + `WriteFile/EditFile` 写 `.cncode/plans/` 路径）。
 2. Layer 1 Bash 安全命令直放。
 3. Layer 2 Bash 危险命令直拒。
 4. Layer 3 路径沙箱（仅 `isPathTool` 命中时）。
 5. Layer 4 文件规则 LIFO 匹配。
 6. Layer 4b 会话级 `allowAlwaysRules` 命中直放。
 7. Layer 5 `mode.decide(tool.category())` 兜底。
 - `reason` 字段写明决策来源（"Dangerous command detected: ..." / "Path outside allowed sandbox: ..." / "Denied by rule: ..." / "Denied by permission mode: ..."）。

## T8: 实现 PLAN 模式豁免与 PlanFile
- 影响文件:
 - `src/main/java/com/cncode/permission/PermissionChecker.java:52-54, 110-121`
 - `src/main/java/com/cncode/plan/PlanFile.java:54-67, 116-123`
- 依赖任务: T7
- 完成标准: `PLAN_MODE_ALLOWED_TOOLS = Set.of("Agent", "ToolSearch", "AskUserQuestion")`；`check` 在 PLAN 分支判断 `WriteFile`/`EditFile` 的 `file_path` 是否 `contains(".cncode/plans/")`；`PlanFile.getOrCreatePlanPath(workDir)` 在 `.cncode/plans/` 下生成 `<adj>-<noun>-<MMdd-HHmm>.md` slug；`PlanFile.isPlanFilePath(target, plan)` 多策略匹配 normalize 后相等或 endsWith。

## T9: Plan 模式 reminder 注入
- 影响文件:
 - `src/main/java/com/cncode/prompt/PlanModePrompt.java:7-176`
 - `src/main/java/com/cncode/agent/Agent.java:106-114`
- 依赖任务: T7, T8
- 完成标准: `PlanModePrompt.buildReminder(planPath, planExists, iteration)` 在 iteration=1 注入完整 5-phase 工作流提示，其他轮次按 `REMINDER_INTERVAL=5` 节奏注入完整或精简提示；`Agent.agentLoop` 每轮检查 `checker.getMode() == PermissionMode.PLAN`，调用 `PlanFile.getOrCreatePlanPath` 后通过 `conv.addSystemReminder(reminder)` 注入。

## T10: 接入主流程
- 影响文件:
 - `src/main/java/com/cncode/agent/StreamingExecutor.java:91-123, 159-169`（权限拦截与 HITL 流程）
 - `src/main/java/com/cncode/tui/CNCodeModel.java:77, 429-433`（构造 Checker 并注入 Agent）
 - `src/main/java/com/cncode/tui/CNCodeModel.java:575-585`（Shift+Tab 循环切模式）
 - `src/main/java/com/cncode/tui/CNCodeModel.java:893-916`（`/plan` 与 `/do` 切换 + plan 路径）
 - `src/main/java/com/cncode/tui/dialog/PlanApprovalDialog.java`（plan 完成后 YOLO/Manual/Feedback 三选项）
 - `src/main/java/com/cncode/command/CommandRegistry.java:203-242`（`/plan` `/do` `/permission` 命令注册）
- 依赖任务: T1~T9
- 完成标准: 用户切换模式 / 进入 PLAN 模式 / 工具调用 / HITL 选 `ALLOW_ALWAYS` 四条主路径全部接到 `PermissionChecker.check` 与 `addAllowAlwaysRule`；`StreamingExecutor` 在 `ASK` 分支发 `PermissionRequestEvent` 并用 `CompletableFuture<PermissionResponse>.get(5, TimeUnit.MINUTES)` 阻塞等待。

## T11: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T10
- 完成标准:
 - `./gradlew build` 通过（顶层命令，已验证）
 - 手动场景:
 1. 在 TUI 默认模式下，发送让 Agent 跑 `rm -rf /` → 工具结果应是 `Permission denied: Dangerous command detected: ...`。
 2. 在 TUI 中让 Agent 写一个工作目录外的文件 `/etc/passwd` → 应被沙箱拒绝 `Path outside allowed sandbox: ...`。
 3. 在 TUI 中让 Agent 写工作目录内的文件 → DEFAULT 模式触发 `ASK`；HITL 选 `ALLOW_ALWAYS` → 同会话内同路径再写直接 `ALLOW`。
 4. `/plan` 进入 PLAN 模式 → Agent 调 WriteFile 写非 plan 文件被沙箱或模式 deny；写 `.cncode/plans/<slug>.md` 被 Allow。
 5. Shift+Tab 切到 `BYPASS` → 危险命令仍被拦（Layer 1/2 不可绕过），普通 Write 直接 `ALLOW`。

## 进度
- [ ] T1 决策 + 模式枚举
- [ ] T2 危险命令检测
- [ ] T3 安全命令白名单
- [ ] T4 路径沙箱
- [ ] T5 规则引擎
- [ ] T6 内容字段提取
- [ ] T7 主入口 PermissionChecker.check
- [ ] T8 PLAN 模式豁免与 PlanFile
- [ ] T9 Plan 模式 reminder 注入
- [ ] T10 主流程接入
- [ ] T11 端到端验证（构建通过 + Agent loop 与 TUI 调用链确认）


# ch06: 权限系统 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性
- [ ] 枚举 `PermissionMode` 四常量 `DEFAULT / ACCEPT_EDITS / PLAN / BYPASS` 在 `src/main/java/com/cncode/permission/PermissionMode.java:5-10`（`grep -n "DEFAULT\|ACCEPT_EDITS\|PLAN\|BYPASS" src/main/java/com/cncode/permission/PermissionMode.java`）
- [ ] 内嵌枚举 `PermissionMode.Decision` 三态 `ALLOW / DENY / ASK` 在 `PermissionMode.java:27-29`
- [ ] `PermissionMode.decide(ToolCategory)` 决策矩阵在 `PermissionMode.java:12-25`，覆盖 4 模式 × 3 类别共 12 个组合（PLAN 复用 DEFAULT 判定）
- [ ] 枚举 `PermissionResponse` 三态 `ALLOW / ALLOW_ALWAYS / DENY` 在 `src/main/java/com/cncode/permission/PermissionResponse.java:3-7`
- [ ] `DANGEROUS_PATTERNS` 静态常量 8 条正则在 `PermissionChecker.java:70-79`
- [ ] `SAFE_COMMANDS` 静态常量 50+ 条前缀在 `PermissionChecker.java:56-68` + `isSafeCommand` 实现在 `PermissionChecker.java:292-304`
- [ ] `projectRoot` 字段 + 构造函数注入在 `PermissionChecker.java:24, 90-94`，`isPathTool` 与 `isPathAllowed` 在 `:306-319`，默认包含 `/tmp` 与 `projectRoot`
- [ ] `PermissionRule` record + `RuleEffect` 枚举在 `PermissionChecker.java:32-50`，glob 用 `FileSystems.getDefault().getPathMatcher("glob:" + pattern)`
- [ ] 三层规则加载（user / project / local）在 `PermissionChecker.java:187-206`，`appendLocalRule` 在 `:208-227`，`loadRulesFile` 在 `:239-290`
- [ ] `RULE_PATTERN` 正则 `^(\\w+)\\((.+)\\)$` 在 `PermissionChecker.java:177`
- [ ] `CONTENT_FIELDS` 6 工具映射在 `PermissionChecker.java:81-88` + `extractContent` 在 `:321-326`
- [ ] `CheckResult` record + `allow/deny/ask` 工厂在 `PermissionChecker.java:100-104`
- [ ] `check` 主入口在 `PermissionChecker.java:106-169`，按 Layer 0→5 顺序排布
- [ ] `PLAN_MODE_ALLOWED_TOOLS` 集合 `{"Agent","ToolSearch","AskUserQuestion"}` 在 `PermissionChecker.java:52-54`，PLAN 豁免分支在 `:110-121` 早于沙箱
- [ ] `PlanFile.isPlanFilePath(target, plan)` 多策略匹配在 `src/main/java/com/cncode/plan/PlanFile.java:116-123`：normalize 相等 / endsWith 命中
- [ ] 五层防御按序：PLAN 豁免 → 安全命令 → 危险命令 → 沙箱 → 文件规则 → 会话级 allow-always → 模式（`PermissionChecker.java:106-169`）
- [ ] `PlanModePrompt.buildReminder(planPath, planExists, iteration)` 在 `src/main/java/com/cncode/prompt/PlanModePrompt.java:141-161`，按 `REMINDER_INTERVAL=5` 切换完整与精简提示

## 2. 接入完整性（必查，杜绝死代码）
- [ ] `grep -rn "new PermissionChecker" --include="*.java" src/main` 至少 1 处真实调用（`src/main/java/com/cncode/tui/CNCodeModel.java:429`）
- [ ] `grep -rn "checker.check\|permChecker" --include="*.java" src/main` Agent 与 TUI 调用方均覆盖（`StreamingExecutor.java:91-123`、`CNCodeModel.java:77/429/575-585/893-916`）
- [ ] `grep -rn "PermissionMode.PLAN\|PermissionMode.DEFAULT\|PermissionMode.ACCEPT_EDITS\|PermissionMode.BYPASS" --include="*.java" src/main` 在 TUI 与 Agent 多处使用，覆盖创建 / 切换 / Plan 模式 reminder 注入
- [ ] `grep -rn "addAllowAlwaysRule\|appendLocalRule" --include="*.java" src/main` 主流程调用方在 `StreamingExecutor.java:114-118`
- [ ] `grep -rn "PermissionRequestEvent" --include="*.java" src/main` 至少 2 处：`StreamingExecutor.java:102` 发事件，`CNCodeModel.java` 处理事件
- [ ] `grep -rn "PlanModePrompt.buildReminder" --include="*.java" src/main` 主流程调用方在 `Agent.java:112`
- [ ] `grep -rn "PlanFile.getOrCreatePlanPath\|PlanFile.isPlanFilePath" --include="*.java" src/main` Agent 与 TUI 均覆盖（`Agent.java:109`、`CNCodeModel.java:897-913`）
- [ ] 配置接入：本地规则文件路径默认 `projectRoot.resolve(".cncode").resolve("permissions.local.yaml")` 在 `PermissionChecker.java:201, 210`
- [ ] HITL 链路：`PermissionChecker.check` 返回 `ASK` → `StreamingExecutor.java:99-119` 通过 `PermissionRequestEvent + CompletableFuture<PermissionResponse>` 走 ch04 事件循环 → TUI 渲染 3 选项 → 用户选 `ALLOW_ALWAYS` 回灌 `addAllowAlwaysRule`

## 3. 编译与测试
- [ ] `./gradlew build` 通过（顶层命令）
- [ ] `./gradlew test --tests "*Permission*"` 通过（如存在）。建议覆盖：`DANGEROUS_PATTERNS` 命中 / `isSafeCommand` 边界（含 `|` 拒绝）/ `isPathAllowed` (项目根内允许、外部拒绝、`/tmp` 允许) / `RULE_PATTERN` 解析 / `fileRules` LIFO 最后一条胜出 / `extractContent` 6 工具 / `mode.decide` 4×3 矩阵 / `check` 多层组合。
- [ ] `./gradlew check` 无警告

## 4. 端到端验证
- [ ] TUI 启动后构造 Checker（`CNCodeModel.java:429-433`），`agent.setChecker(permChecker)` 注入 Agent
- [ ] PLAN 模式：`/plan` → `permChecker.setMode(PermissionMode.PLAN)` + `PlanFile.getOrCreatePlanPath(workDir)`；下一轮工具调用调 `check`，`WriteFile` 写非 plan 文件被 Deny 除非命中 `.cncode/plans/`
- [ ] HITL：DEFAULT 模式下让模型写新文件，TUI 弹出三选项（YOLO / Manual / Feedback 见 `PlanApprovalDialog.java:47-51`，普通 HITL 见 `AskUserDialog`），三选项对应 `ALLOW / ALLOW_ALWAYS / DENY`
- [ ] 自学习：选 `ALLOW_ALWAYS` 时 `StreamingExecutor.java:114-118` 调用 `checker.addAllowAlwaysRule(toolName, content)`，同会话内同 `toolName:content` 直接放行
- [ ] 危险命令防御不可绕过：即使 `BYPASS`，`check` 仍按顺序经过 Layer 1/2（Bash 安全命令与危险命令分支不依赖 mode，见 `PermissionChecker.java:123-135`），让模型执行 `rm -rf /` 仍 Deny
- [ ] 留存证据: 验收阶段可在 `.cncode/permissions.local.yaml` 中观察 `appendLocalRule` 写入的 YAML 列表项

## 5. 文档
- [ ] spec.md / tasks.md / checklist.md 三件套齐全（`docs/java/ch06/`）
- [ ] commit 信息标注 `ch06` 与三件套关闭状态（待统一打包提交）






# ch07: MCP Protocol Spec

## 1. 背景

外部能力（Context7、Atlassian、Slack 等）通过 Model Context Protocol（MCP）暴露给 Agent。如果没有 MCP 客户端实现，CN Code 就只能依赖内置的六个工具（ReadFile / WriteFile / EditFile / Bash / Glob / Grep），无法接入生态里已有的几百个 MCP server，等于砍掉一大块工具生态。MCP 规范定义了 JSON-RPC 2.0 之上的握手 → 工具发现 → 工具调用三阶段会话，需要本章在 Java 侧把这三阶段、两种传输（stdio 子进程 / Streamable HTTP，含兼容 SSE 解析）以及到 `com.cncode.tool.Tool` 接口的适配器实现，并接到 TUI 的启动流程里。

## 2. 目标

交付一个能在 CN Code 启动时按配置批量连接外部 MCP server、把每个 server 暴露的工具注册到全局 `ToolRegistry` 的客户端。具体能力：单服务器 `McpTransport` 抽象（connect / getInstructions / listTools / callTool / close）；多服务器调度类 `McpManager`（构造、`connectAll`、`registerAllTools`、`shutdown`）；`McpToolWrapper` 把每个 MCP tool 适配到 `com.cncode.tool.Tool` 接口；工具名做命名空间消毒。最终效果是用户在 TUI 里看到 MCP server 的工具与内置工具并列，能被 LLM 调用，且默认走 deferred 通道按需披露。

## 3. 功能需求

- F1: 服务器配置 `McpServerConfig` 同时承载 stdio（`command + args + env`）和 HTTP（`url + headers`）两种传输，POJO + getter/setter，YAML 反序列化兼容。
- F2: `McpManager.connectAll` 在 `command` 非空时构造 `McpStdioClient`，否则在 `url` 非空时构造 `McpHttpClient`，两者皆空时把错误写入 `errors` 列表并跳过该 server。
- F3: stdio 子进程的 stderr 用一个 virtual thread 持续 drain 丢弃，避免 OSC 颜色查询污染父 TTY 输入。
- F4: HTTP 请求头通过 `HttpRequest.Builder.header` 注入，并对 header 值做 `${VAR}` 占位符替换（`resolveEnvVars`），方便从环境变量取 API key；stdio 子进程的 `env` 同样做替换。
- F5: 单服务器实现 `connect` → `listTools` → `callTool` → `close` 四阶段：`connect` 发 `initialize` 请求并紧跟一条 `notifications/initialized`；`listTools` 调 `tools/list`；`callTool` 调 `tools/call`；HTTP 复用同一个 `HttpClient` 实例，stdio 复用同一对 `BufferedReader / BufferedWriter`。
- F6: `McpManager.connectAll` 把多 server 批量并入，单个 server 抛异常时把 `errors.add("MCP server '<name>': <message>")` 收集但不阻塞其他 server；返回 `ConnectResult(tools, servers, errors)` 三元组。
- F7: 工具名按 `mcp__<server>__<tool>` 命名，server 名和 tool 名都过 `sanitizeName`（非 `[A-Za-z0-9_]` 字符替换为下划线），保证 LLM API 的 tool name 合法。
- F8: `McpToolWrapper.execute` 透过 transport 调真实工具，把 MCP 响应里的 `result.content` 列表中所有 `type == "text"` 块拼成字符串；JSON-RPC 错误（`response.error` 非空）返回 `"MCP error: <message>"`；无输出回填 `(no output)`；任何异常包成 `ToolResult.error(...)`。
- F9: `McpManager.registerAllTools(ToolRegistry registry)` 把所有 wrapper 注册到 `ToolRegistry`，返回 `errors` 列表供 TUI 显示。
- F10: 所有 wrapper 实现 `Tool.shouldDefer() == true`，类别 `ToolCategory.COMMAND`，让 TUI / Agent 把 MCP 工具放进 deferred 通道，靠 `ToolRegistry.getDeferredTools / searchDeferred / findDeferredByNames` 按需披露给 LLM。
- F11: HTTP transport 支持 `Mcp-Session-Id` 会话头：首次响应里若带回 `mcp-session-id` 则保存到客户端实例字段，后续每个请求自动带上。
- F12: HTTP transport 同时支持 `application/json` 与 `text/event-stream`：响应 `Content-Type` 含 `text/event-stream` 时走 `parseSseResponse`，从 `data:` 行里挑出匹配 `id` 的 JSON-RPC 帧；纯 JSON 走 `ObjectMapper.readValue`。
- F13: TUI 启动时（`CNCodeModel` 初始化阶段或专用 init 命令）读 `config.yaml` 的 `mcp_servers`，构造 `McpManager` 实例，异步调 `connectAll` / `registerAllTools`，把结果汇回主线程后注册到全局 registry。

## 4. 非功能需求

- N1: 连接是异步执行的（在 TUI 后台 virtual thread / executor 里执行），不阻塞 TUI 启动渲染。
- N2: 单个 server 连接失败要被收集到 `ConnectResult.errors`，其他 server 继续连。
- N3: 工具名转换必须保证 LLM API 的 tool name 合法性（只允许字母数字和下划线），由 `NON_ALNUM` 正则 + `sanitizeName` 保证。
- N4: 不要手写 JSON-RPC 帧格式以外的协议细节；JSON 编解码统一走单例 `ObjectMapper`。
- N5: `shutdown` 必须幂等：stdio 客户端调 `process.destroyForcibly`，HTTP 客户端无连接可关；多次调用不抛异常。
- N6: stdio 客户端的 `connect` 必须在 `initialize` 之后立刻发 `notifications/initialized`，否则有些 server 拒绝继续会话。

## 5. 设计概要

- 核心数据结构:
 - `com.cncode.config.McpServerConfig`：POJO，字段 `name / command / args / url / headers / env`，YAML 反序列化目标。
 - `McpManager`：多 server 调度，持有 `Map<String, McpServerConfig> configs` 与 `Map<String, McpTransport> clients` 两张 `LinkedHashMap`，对外暴露 `connectAll / registerAllTools / shutdown`。
 - `McpManager.McpTransport`：传输抽象接口（5 个方法），由 `McpStdioClient` / `McpHttpClient` 两个内部类实现。
 - `McpManager.ConnectResult`：record，含 `List<Tool> tools / List<ServerInfo> servers / List<String> errors`，作为 `connectAll` 的返回类型。
 - `McpManager.ServerInfo`：record，含 `name / instructions`，承载 `initialize` 响应里的 server `instructions` 文本。
 - `McpManager.McpToolDef`：record，含 `name / description / inputSchema`，承载 `tools/list` 单条结果。
 - `McpManager.McpToolWrapper`：把 `McpToolDef + 服务端 name + transport` 适配到 `com.cncode.tool.Tool`。
- 主流程（调用链）:
 - TUI 启动读 config 拿到 MCP server 列表 → 用 `new McpManager(configs)` 构造 → 异步走 `manager.connectAll()` / `manager.registerAllTools(registry)`。
 - 对每个 server 按 `command / url` 选择 `McpStdioClient` 或 `McpHttpClient`：构造 → `connect()` 发 `initialize` + `notifications/initialized` → `getInstructions()` 取握手返回的 server 指令 → `listTools()` 拿工具列表 → 包成 `McpToolWrapper`。
 - TUI 收到完成事件后把 `ConnectResult.tools` 批量 `registry.register(tool)`；`errors` 渲染到对话顶部告知用户。
 - LLM 调用工具时按 `mcp__<server>__<tool>` 在 registry 命中 wrapper，`execute(args)` 调 transport 的 `callTool` 走 session 上的 `tools/call`。
- 与其他模块的交互:
 - 依赖 `com.cncode.tool`：实现 `Tool` 接口、注册到 `ToolRegistry`、返回 `ToolResult`。
 - 依赖 `com.fasterxml.jackson.databind.ObjectMapper`：所有 JSON-RPC 帧编解码。
 - 依赖 JDK 自带的 `java.net.http.HttpClient`：HTTP 传输；stdio 走 `ProcessBuilder` + `BufferedReader / BufferedWriter`。
 - 被 `com.cncode.CN Code` / `com.cncode.tui.CNCodeModel` 调用：在主启动入口把 `config.getMcpServers()` 传进 model；model 内构造 `McpManager` 并调 `registerAllTools`。

## 6. Out of Scope

- OAuth / 鉴权刷新：本仓库只做静态 header 注入与环境变量展开，不实现 OAuth step-up 401 处理。
- 连接缓存：每次启动重新连接，不做跨进程缓存。
- IDE 集成（双向 SSE / WebSocket / 进程内 transport）。
- MCP resources / prompts / sampling 三种非 tool 能力：只暴露 `tools/list` + `tools/call`。
- 服务器健康检查与自动重连：断了由用户重启 CN Code。
- stdio 端的 stderr 内容回流：当前直接 drain 丢弃，不做日志聚合。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch07: MCP Protocol Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。每完成一个任务跑 `./gradlew build` 确保编译过；接入主流程的任务（T7、T8）做完后立刻补一次端到端验证再进下一项。

## T1: 定义 `McpServerConfig` 配置 POJO
- 影响文件: `src/main/java/com/cncode/config/McpServerConfig.java`（行 1~32）
- 依赖任务: 无
- 完成标准: 类含字段 `name / command / args / url / headers / env`，全部走 `private` + getter/setter，类型分别为 `String / String / List<String> / String / Map<String,String> / Map<String,String>`，可被 YAML 反序列化为 `mcp_servers` 列表项。

## T2: 抽出 `McpTransport` 接口与共享工具
- 影响文件: `src/main/java/com/cncode/mcp/McpManager.java`（行 19~30 类骨架与字段；行 86~96 `sanitizeName` / `resolveEnvVars`；行 100~106 `McpTransport` 接口；行 401~419 `extractTextContent`；行 421~423 `McpToolDef` record）
- 依赖任务: T1
- 完成标准: `McpManager` 持有 `ObjectMapper MAPPER`、`Pattern NON_ALNUM`、`Pattern ENV_VAR` 三个静态常量；定义内嵌 `interface McpTransport`，5 个方法 `connect / getInstructions / listTools / callTool / close`；静态助手 `sanitizeName` / `resolveEnvVars` / `extractTextContent` 实现；`McpToolDef(name, description, inputSchema)` record 定义齐全。

## T3: 实现 `McpStdioClient`（JSON-RPC over stdio）
- 影响文件: `src/main/java/com/cncode/mcp/McpManager.java`（行 108~239）
- 依赖任务: T2
- 完成标准:
 - `connect()` 用 `ProcessBuilder` 拉起子进程，把 `config.getEnv()` 跑 `resolveEnvVars` 后写入 `pb.environment()`；启动一个 virtual thread drain stderr（行 142~146）。
 - 发 `initialize` 请求（protocolVersion `2024-11-05`、clientInfo `{name: cncode, version: 0.1.0}`），从 `result.instructions` 取 server 指令。
 - 紧跟发 `notifications/initialized` 通知（行 159）。
 - `sendRequest` 用 `idCounter` 自增 + `MAPPER.writeValueAsString` 拼帧 + `writer.write + newLine + flush`；读响应循环 `readLine`，丢空行，遇到含 `id` 的帧返回。
 - `listTools` 把 `result.tools` 解析为 `List<McpToolDef>`。
 - `callTool(name, args)` 调 `tools/call`，错误透传 `MCP error: <message>`，否则调 `extractTextContent`。
 - `close()` 在 `process != null && process.isAlive()` 时 `destroyForcibly()`。

## T4: 实现 `McpHttpClient`（JSON-RPC over Streamable HTTP，兼容 SSE）
- 影响文件: `src/main/java/com/cncode/mcp/McpManager.java`（行 241~399）
- 依赖任务: T2
- 完成标准:
 - 类持有单例 `HttpClient.newHttpClient()` 与 `String sessionId` 字段。
 - `connect()` 发 `initialize`，从响应里读 `instructions`；紧跟发 `notifications/initialized`（行 268）。
 - `sendHttpRequest` 构建 `HttpRequest.newBuilder().uri(config.getUrl())`，必带 `Content-Type: application/json` 与 `Accept: application/json, text/event-stream`；`sessionId` 不空则带 `Mcp-Session-Id`；config 的 `headers` 走 `resolveEnvVars` 后逐条 `.header(key, value)`。
 - 响应 `mcp-session-id` 头若存在则赋值到 `sessionId`（行 337）。
 - `Content-Type` 含 `text/event-stream` 时走 `parseSseResponse`：按行解析 `data: ` 前缀，跳过空行与 `[DONE]`，匹配 `id` 后返回；否则 `MAPPER.readValue` 当成单个 JSON 帧。
 - `sendHttpNotification` 不带 `id` 字段，响应丢弃（`BodyHandlers.discarding()`）。
 - `listTools` / `callTool` 复用与 stdio 一致的语义。
 - `close()` 无连接需关，方法体为空注释。

## T5: 实现 `McpToolWrapper` 适配器
- 影响文件: `src/main/java/com/cncode/mcp/McpManager.java`（行 425~460）
- 依赖任务: T3, T4
- 完成标准:
 - 实现 `com.cncode.tool.Tool` 接口。
 - `name()` 返回 `"mcp__" + sanitizeName(serverName) + "__" + sanitizeName(toolDef.name())`（行 438~440）。
 - `description()` 直接透传 `toolDef.description()`。
 - `category()` 返回 `ToolCategory.COMMAND`；`shouldDefer()` 返回 `true`（让 deferred 通道接管）。
 - `schema()` 返回 `Map.of("name", name(), "description", description(), "input_schema", input)`，`input` 为 `toolDef.inputSchema()`，空则回退到 `{"type":"object","properties":{}}`。
 - `execute(args)` 调 `transport.callTool(toolDef.name(), args)`，捕获异常包成 `ToolResult.error("MCP tool call failed: " + e.getMessage())`，成功包 `ToolResult.success(output)`。

## T6: 实现 `McpManager.connectAll` / `registerAllTools` / `shutdown`
- 影响文件: `src/main/java/com/cncode/mcp/McpManager.java`（行 31~84）
- 依赖任务: T5
- 完成标准:
 - 构造函数接收 `List<McpServerConfig>`，按 name 装进 `configs` `LinkedHashMap`，null 安全。
 - `connectAll()` 遍历 `configs`：根据 `command` / `url` 选 `McpStdioClient` / `McpHttpClient`，两者皆空则错误清单加 `"MCP server '<name>': neither command nor url configured"` 并 continue。
 - 单个 server 走 `try { connect; listTools; tools.add(new McpToolWrapper(...)) } catch (Exception e) { errors.add(...) }`，不阻塞其他 server。
 - 返回 `new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors))`。
 - `registerAllTools(ToolRegistry registry)` 调一次 `connectAll`，对 `result.tools()` 逐个 `registry.register(t)`，返回 `result.errors()`。
 - `shutdown()` 遍历 `clients.values()` 调 `client.close()`，最后 `clients.clear()`，幂等。

## T7: 接入 TUI 启动流程
- 影响文件: `src/main/java/com/cncode/CNCode.java`（行 35~39 把 `config.getMcpServers()` 传进 model 构造）、`src/main/java/com/cncode/tui/CNCodeModel.java`（在初始化阶段构造 `McpManager` 并异步调 `connectAll` / `registerAllTools`，把结果汇回 update / Msg 通道）
- 依赖任务: T6
- 完成标准:
 - TUI 启动时把 `config.getMcpServers()` 拷成 `List<McpServerConfig>` 传给 model；model 内构造 `new McpManager(configs)` 与默认 `ToolRegistry.createDefault()` 并存。
 - 异步线程（`Thread.ofVirtual().start(...)` 或 executor）执行 `registerAllTools`，错误列表通过自定义 `McpReadyMsg` 回主线程渲染。
 - MCP 工具能与内置 6 个工具并列被 LLM 调用（通过 `getDeferredTools` / `searchDeferred` / `findDeferredByNames` 披露）。
 - 退出钩子（如 `program.run()` 的 `finally`）调 `manager.shutdown()`。

## T8: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T7
- 完成标准:
 - `./gradlew build` 通过。
 - `./gradlew test` 全过（含 `McpManagerTest` 之类单测）。
 - 在 `config.yaml` 添加 context7 server（`command: npx, args: [-y, @upstash/context7-mcp]`），启动 TUI 后看到 MCP 工具列表（含 `mcp__context7__resolve_library_id` 等）能被 LLM 调用并返回结果；启动日志或错误面板看到 `Connected successfully`/无错误。
 - HTTP 路径用一台公开 MCP server（或自起 `mcp-server-stdio` 套 HTTP wrapper）验证 SSE 与 Mcp-Session-Id 头能跑通。
 - 截图或日志留证。

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8（受外部 `npx` / 公开 MCP server 依赖，本机已验证；CI 默认跳过）


# ch07: MCP Protocol Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性

### 1.1 配置 POJO
- [ ] `McpServerConfig` 在 `src/main/java/com/cncode/config/McpServerConfig.java:6-32` 实现，字段 `name / command / args / url / headers / env` 齐全（grep `class McpServerConfig` 命中）。
- [ ] 全部字段走 `private` + 公开 `getXxx / setXxx`，类型分别为 `String / String / List<String> / String / Map<String,String> / Map<String,String>`（验证：肉眼检查 `McpServerConfig.java:15-31`）。

### 1.2 McpManager 骨架与共享工具
- [ ] 类 `McpManager` 位于 `src/main/java/com/cncode/mcp/McpManager.java:19`，含静态常量 `MAPPER`（行 21）、`NON_ALNUM`（行 22）、`ENV_VAR`（行 23）。
- [ ] record `ServerInfo(String name, String instructions)` 在 `McpManager.java:25` 定义。
- [ ] record `ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors)` 在 `McpManager.java:26` 定义。
- [ ] record `McpToolDef(String name, String description, Map<String, Object> inputSchema)` 在 `McpManager.java:423` 定义。
- [ ] 接口 `McpTransport` 在 `McpManager.java:100-106` 定义，5 个方法 `connect / getInstructions / listTools / callTool / close` 齐全。
- [ ] 静态助手 `sanitizeName` 在 `McpManager.java:86-88` 实现，正则替换非 `[A-Za-z0-9_]` 为 `_`。
- [ ] 静态助手 `resolveEnvVars` 在 `McpManager.java:90-96` 实现，对 `${VAR}` 占位符做 `System.getenv` 替换，匹配不到时保留原样。
- [ ] 静态助手 `extractTextContent` 在 `McpManager.java:403-419` 实现，把 `result.content` 中 `type == "text"` 的块拼成字符串，空时返回 `(no output)`。

### 1.3 stdio 传输
- [ ] `McpStdioClient` 在 `McpManager.java:110-239` 实现，含 `process / writer / reader / idCounter / instructions` 五个字段。
- [ ] `connect()` 在 `McpManager.java:124-160` 实现，用 `ProcessBuilder` 启动子进程并 `redirectErrorStream(false)`。
- [ ] stderr drain 在 `McpManager.java:142-146`，用 `Thread.startVirtualThread` 持续 `readLine`（避免 OSC 颜色查询污染 TTY）。
- [ ] `initialize` 请求体 `protocolVersion=2024-11-05`、`clientInfo={name:cncode,version:0.1.0}` 在 `McpManager.java:148-152`。
- [ ] `notifications/initialized` 在 `McpManager.java:159` 发出。
- [ ] `sendRequest` 在 `McpManager.java:201-221` 实现：`idCounter.incrementAndGet`、`writer.write + newLine + flush`、读循环跳过空行、遇到含 `id` 的 JSON 帧返回。
- [ ] `listTools` 在 `McpManager.java:167-184` 解析 `result.tools` 为 `List<McpToolDef>`。
- [ ] `callTool` 在 `McpManager.java:188-198`，JSON-RPC `error` 非空时返回 `MCP error: <message>`；否则调 `extractTextContent`。
- [ ] `close` 在 `McpManager.java:234-238`，`process.isAlive()` 时 `destroyForcibly()`，幂等。
- [ ] env 变量替换：`config.getEnv()` 的值在 `McpManager.java:131-136` 走 `resolveEnvVars` 后写入 `pb.environment()`。

### 1.4 HTTP 传输
- [ ] `McpHttpClient` 在 `McpManager.java:243-399` 实现，含 `config / httpClient / idCounter / instructions / sessionId` 五个字段。
- [ ] `connect()` 在 `McpManager.java:256-269` 发 `initialize` 与 `notifications/initialized`。
- [ ] `sendHttpRequest` 在 `McpManager.java:310-347`：必带 `Content-Type: application/json` 与 `Accept: application/json, text/event-stream`；`sessionId` 不空时带 `Mcp-Session-Id` 头；config `headers` 走 `resolveEnvVars` 注入。
- [ ] 响应头 `mcp-session-id` 自动赋值到 `sessionId` 字段（`McpManager.java:337`）。
- [ ] SSE 解析在 `McpManager.java:350-368`：按行扫 `data: ` 前缀，跳过空行与 `[DONE]`，匹配 `id` 后返回对应 JSON-RPC 帧；找不到则抛 `IOException("No JSON-RPC response found in SSE stream")`。
- [ ] `sendHttpNotification` 在 `McpManager.java:370-393` 不带 `id` 字段，响应走 `BodyHandlers.discarding()`。
- [ ] `close()` 在 `McpManager.java:395-398` 是空实现 + 注释 `// HTTP is stateless; nothing to close`。

### 1.5 Tool Wrapper
- [ ] `McpToolWrapper` 在 `McpManager.java:427-460` 实现 `com.cncode.tool.Tool`。
- [ ] `name()` 在 `McpManager.java:438-440` 输出 `mcp__<sanitized-server>__<sanitized-tool>`。
- [ ] `description()` 透传 `toolDef.description()`（行 442）。
- [ ] `category()` 返回 `ToolCategory.COMMAND`、`shouldDefer()` 返回 `true`（行 443~444）。
- [ ] `schema()` 在 `McpManager.java:446-450` 返回 `{name, description, input_schema}`；`inputSchema` 为 null 时回退 `{type: object, properties: {}}`。
- [ ] `execute(args)` 在 `McpManager.java:452-459`：成功 `ToolResult.success(output)`、异常 `ToolResult.error("MCP tool call failed: " + e.getMessage())`。

### 1.6 Manager 调度
- [ ] 构造函数 `McpManager(List<McpServerConfig>)` 在 `McpManager.java:31-35` 实现，null 安全按 `name` 装进 `LinkedHashMap`。
- [ ] `connectAll()` 在 `McpManager.java:37-73`，按 `command / url` 选传输；两者皆空时 `errors.add("MCP server '<name>': neither command nor url configured")` 并 `continue`。
- [ ] 单 server 失败收集到 `errors`，其他 server 继续连：见 `McpManager.java:67-69` 的 `try/catch`。
- [ ] `connectAll` 返回的 `ConnectResult` 三个列表均通过 `List.copyOf` 包裹，避免外部修改（行 72）。
- [ ] `registerAllTools(ToolRegistry registry)` 在 `McpManager.java:75-79`，遍历 `result.tools()` 调 `registry.register(t)`，返回 `result.errors()`。
- [ ] `shutdown()` 在 `McpManager.java:81-84`：遍历 `clients.values()` 调 `close()`，再 `clients.clear()`，幂等。

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `grep -rn "McpManager\|McpServerConfig" --include="*.java" src/main` 至少 5 处非测试调用方（实测应命中 `config/McpServerConfig.java` 定义 + `mcp/McpManager.java` 定义 + `CNCode.java` 传参 + `tui/CNCodeModel.java` 构造与生命周期）。
- [ ] 启动入口 `src/main/java/com/cncode/CNCode.java:35-39` 把 `config.getMcpServers()` 透传给 `CNCodeModel`。
- [ ] `CNCodeModel` 内构造 `new McpManager(...)` 并在异步线程（virtual thread / executor）调用 `registerAllTools(toolRegistry)`，错误清单通过 Msg 回主循环渲染。
- [ ] 退出路径调 `manager.shutdown()`（在 `program.run()` 的 `finally` 或 model 的清理钩子里）。
- [ ] 配置项 `mcp_servers` 已暴露到 `config.yaml`：`AppConfig.getMcpServers()` 返回 `List<McpServerConfig>`，YAML 反序列化能解析 `command / args / env / url / headers` 字段。
- [ ] 用户输入到本模块的路径可一句话描述: 启动 `CN Code.main` → `ConfigLoader.load` → `config.getMcpServers()` → `new CNCodeModel(..., mcpServers, ...)` → 异步 `new McpManager(mcpServers).registerAllTools(toolRegistry)` → LLM 把 `mcp__xxx` 当 deferred 工具按需取出。

## 3. 编译与测试

- [ ] `./gradlew build` 通过。
- [ ] `./gradlew test` 全过。
- [ ] `./gradlew test --tests "com.cncode.mcp.*"` 全过（含 sanitizeName / resolveEnvVars / extractTextContent 单测）。

## 4. 端到端验证

- [ ] 在 `config.yaml` 添加 context7 server（`command: npx, args: [-y, @upstash/context7-mcp]`），启动 TUI 后能看到 `mcp__context7__resolve_library_id` 出现在 deferred 工具列表中。
- [ ] 在 TUI 中提示 LLM 调 context7 工具，模型返回结果而非 `Tool not found`。
- [ ] 配置一个故意写错的 server（command 与 url 都不填），启动后看到错误清单含 `MCP server '<name>': neither command nor url configured`，其他 server 仍正常连上。
- [ ] HTTP MCP server（支持 SSE 响应）能跑通：返回 `text/event-stream` 时 `parseSseResponse` 解析得到 JSON-RPC 响应，`Mcp-Session-Id` 在后续请求里自动带上。
- [ ] 退出 TUI 后无 stdio 子进程残留（`ps aux | grep mcp` 看不到僵尸进程）。

## 5. 文档

- [ ] `docs/java/ch07/spec.md` 与本 checklist / tasks 三件套齐全且最新。
- [ ] commit 信息标注 `ch07` 与三件套关闭状态（验收阶段产物，待用户审阅后随后续 commit 一并打标）。






# ch08: 上下文管理 Spec

## 1. 背景

LLM 上下文窗口有上限，但长任务里 tool result（Bash 输出、长文件）很容易在几轮内把窗口顶爆。没有上下文管理就意味着 Agent 跑到一半被 API 退回 `prompt_too_long`，会话失败、上下文丢失、用户得手动重启。

本章用「先廉价救火再花钱总结」分层策略解决：Layer 1 不调 LLM，把单条超大或单条消息聚合超大的 tool result 写盘换 preview 字符串，并维护跨轮的「替换决策日志」`ContentReplacementState`，让每个 tool result 的「替换/不替换」决定只做一次、之后字节相同地复读 —— 这是 Anthropic prompt cache 命中所需的前缀稳定性的关键；按 token 估算占比逐级升档的 Snip / Microcompact / Collapse / Auto-compact 四档由 `ContextCompactor.manage` 在 Agent 主循环每轮开头调用，越晚动用 LLM 越好。Auto-compact 之后再附一段「恢复块」（最近读过的文件 / 已激活的技能 / 当前可用工具 / 收尾提示），把摘要替换掉的工作记忆补回去。

## 2. 目标

交付两个独立又互补的包：

- **`com.cncode.toolresult`**（新包）：Layer 1 决策日志 + Design B 应用。
  - `ContentReplacementState` 含 `Set<String> seenIds` 与 `Map<String, String> replacements`，构造空容器并支持 `copy()` 独立深拷贝。
  - `ToolResultBudget.apply(conv, sessionDir, state)` 返回 `ApplyResult(apiConv, newRecords)`：**不 mutate 入参 conv**，构造新 `ConversationManager` 应用决策；对新候选评估「单条超限」与「聚合超限」两规则；选中的 tool result 写盘换 `[Result of N chars saved to PATH ...]` preview 字符串，决定写入 state；过 `KEEP_RECENT_TURNS` 轮的陈旧 tool result 裁为 `[Stale output snipped: N chars]` 一行。
  - `ReplacementRecordsIO.append/load` 把新决策落盘到 `<sessionDir>/replacement_records.jsonl`，方便后续 resume 复盘。
  - `ContentReplacementLifecycle.reconstruct(messages, records, inheritedReplacements)` 从 transcript 重建 state。

- **`com.cncode.compact`**（保留 + 升档逻辑 + 恢复）：4 层升档、摘要与压缩后恢复。
  - `ContextCompactor.manage(conv, client, contextWindow, workDir, tracking, recovery, toolSchemas)` 算 token 估算 → 按 ratio 升档：> 0.80 走 Auto-compact，> 0.70 走 Collapse，> 0.60 走 Microcompact，> 0.50 走 Snip。
  - `forceCompact(conv, client, contextWindow, recovery, toolSchemas)` 给 `/compact` 与反应式恢复用，无视阈值直接走 Auto-compact。
  - 旧的 `applyToolResultBudget`（仅单条 spill 且从未被 `manage` 调用过）由 `ToolResultBudget.apply` 接管。
  - `RecoveryState` 跨轮记录 ReadFile 字节快照与 Skill SOP；`buildRecoveryAttachment(state, toolSchemas)` 把「最近读过的文件 / 已激活的技能 / 当前可用工具 / 收尾提示」四段拼成纯文本块，`autoCompact` 在生成摘要之后用 `\n\n---\n\n` 拼到摘要 user 消息末尾。

两层在 Agent 主循环里串联：Layer 2 先跑（`ContextCompactor.manage` 按 ratio 决定动作，需要时 mutate `conv` 装新对话）→ 各种 system reminder 写入 conv → Layer 1 在 `client.stream` 调用前最后一刻跑、把 apiConv 喂给 LLM。

Anthropic 客户端在 system / tools 末项 / 最后一条 user message 末尾三处加 `cache_control: ephemeral` 标记；配合 Layer 1 的字节稳定 replacements，前缀缓存就能命中。

## 3. 功能需求

### 3.1 `com.cncode.toolresult` 状态容器与持久化

- F1: `ContentReplacementState`（`Set<String> seenIds` + `Map<String, String> replacements`，HashSet/HashMap 默认初始化），方法 `copy()` 返回独立深拷贝（用 `new HashSet<>(src.seenIds)` 与 `new HashMap<>(src.replacements)`）。不变量：`keys(replacements) ⊆ seenIds`。
- F2: `ContentReplacementRecord(String kind, String toolUseId, String replacement)` record + 静态工厂 `toolResult(toolUseId, replacement)`；`KIND_TOOL_RESULT = "tool-result"` 常量。
- F3: `ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords)` record，用作 `ToolResultBudget.apply` 返回值。
- F4: `ReplacementRecordsIO.append(sessionDir, records)`：空列表直接 return；自动 `Files.createDirectories(sessionDir)`；每行一个 JSON 对象（Jackson）；`kind` 为空或 null 自动填 `KIND_TOOL_RESULT`。
- F5: `ReplacementRecordsIO.load(sessionDir)`：缺文件返回空列表；`Files.readAllLines` 后逐行 Jackson `readValue`。
- F6: `ContentReplacementLifecycle.reconstruct(messages, records, inheritedReplacements)`：先 seed `seenIds` = 所有 message 里 `getToolResults` 的 `tool_use_id`；按 `kind == "tool-result"` 过滤 records 并命中 candidate 才写入 `replacements`；可选 `inheritedReplacements` (Map) 做 gap-fill（candidate ∩ 未被 records 覆盖）。

### 3.2 Layer 1 应用

- F7: `ToolResultBudget.apply(conv, sessionDir, state) -> ApplyResult`，**不修改入参 conv**。算法：
  1. 阶段 1: 对每条 tr 分四类——`state.replacements` 命中 → 复读；`state.seenIds` 命中 → 冻结原文；外部已带 `[Result of ` 或 `[Stale output snipped:` 前缀 → 视为已知决策，写入 state 与 records；其余进 fresh。
  2. 阶段 2 (Pass 1): fresh 中 content 长度 > `SINGLE_RESULT_LIMIT` 调 `spillAndPreview` 写盘 + 生成 preview，写入 state 与 records；spill 失败 freeze 原文。
  3. 阶段 3 (Pass 2): 计算 `total = Σdecisions.values.length + Σremaining.content.length`；> `MESSAGE_AGGREGATE_LIMIT` 时按 content 长度降序挑直到压回上限。
  4. 阶段 4: 未决策的 fresh 全部加进 `state.seenIds`、`decisions.put(id, tr.content())`。
  5. 末段: 用 `decisions` 构造新 `List<ToolResultBlock>` 保持原顺序 → `copyMessageWithResults` → `snipStale` → `buildManager`（通过 `addAssistantFull / addToolResultsMessage / addUserMessage / addAssistantMessage` 重放消息）。
- F8: 阈值常量 `SINGLE_RESULT_LIMIT = 15_000`、`MESSAGE_AGGREGATE_LIMIT = 20_000`、`OLD_RESULT_SNIP_CHARS = 2_000`、`KEEP_RECENT_TURNS = 10`、`SPILL_SUBDIR = "tool_results"` 在 `ToolResultBudget` 顶部 `public static final` 定义。
- F9: spill 文件 `spillAndPreview` 用 `Files.writeString(spillDir.resolve(toolUseId), content)` 写到 `<sessionDir>/tool_results/<toolUseId>`；同 size 文件已存在则不重写（幂等）。
- F10: preview 格式 `[Result of N chars saved to PATH — read with ReadFile if needed]` 是 byte-stable anchor，一旦写入 `state.replacements`，后续每轮逐字节复读。
- F11: Pass 3 陈旧裁剪 `snipStale`：在 Pass 1/2 输出的 new history 上跑（不动原 conversation）；超过 `KEEP_RECENT_TURNS` 轮的消息里，超过 `OLD_RESULT_SNIP_CHARS` 字符且未被 `[Result of `/`[Stale output snipped:` 前缀标记的 tool result 整体替换为 `[Stale output snipped: N chars]`。

### 3.3 `com.cncode.compact` Layer 2 升档

- F12: `ContextCompactor.manage(conv, client, contextWindow)` 按 ratio 升档：
  - ratio > `AUTOCOMPACT_THRESHOLD = 0.80` → `autoCompact`。
  - ratio > `COLLAPSE_THRESHOLD = 0.70` → `contextCollapse`。
  - ratio > `MICROCOMPACT_THRESHOLD = 0.60` → `microcompact`。
  - ratio > `SNIP_THRESHOLD = 0.50` → `snip`，命中时回填 `"Snipped verbose tool results"`。
  - 未命中返回空串。
- F13: `estimateTokens(messages)` 按 `length / 3.5` 估算 + 常数偏置，覆盖 content + tool args (Jackson 序列化) + tool_results + thinking blocks 四类。
- F14: `snip` 把 `recentBoundary = size - KEEP_RECENT_TURNS*3` 之前的超过 `SNIP_CHAR_LIMIT = 2000` 的 tool result 换为 `[Output snipped: %d chars, %d lines]` 一行。
- F15: `microcompact` 对老 tool result 超过 `MICROCOMPACT_LIMIT = 5000` 的内容调 `truncatePreservingBoundaries` 做头 5 行 + 尾 5 行 + 省略提示。
- F16: `contextCollapse` 按 `splitIdx = size - 30` 切分早期段和最近段；早期段 `serializeForSummary(oldMessages, 500)` + `requestSummary` 走 LLM 摘要；新建 `ConversationManager` 装 `[Earlier conversation summary]` 用户消息 + assistant 确认消息 + 最近 30 条原样追加；`replaceConversation` 就地替换。
- F17: `autoCompact` 全量 `serializeForSummary` + `requestSummary` 走 LLM；新建 `ConversationManager` 装 `[Compacted conversation summary]\n\n<summary>` + assistant 确认消息；`replaceConversation` 就地替换。
- F18: 摘要系统提示 `SUMMARY_SYSTEM_PROMPT` 是 Text Block，明确要求保留 file paths / decisions / current goal / pending work / error states / code snippets 六类信息。
- F19: `requestSummary` 用一次性 `client.stream(summaryConv, null)`（tools 传 null 禁用工具调用），消费 `StreamEvent.TextDelta` 聚成 summary；遇 `Error` 抛 `RuntimeException`；`InterruptedException` 重置中断标志后抛。
- F20: `forceCompact(conv, client, contextWindow)` 手动入口，跳过 4 层升档直接调 `autoCompact`。

### 3.4 Anthropic 缓存断点与 Agent 集成

- F21: `AnthropicClient.doStream` 在请求构造期间打三处 `CacheControlEphemeral`：
  - `system` 包装成 `MessageCreateParams.System.ofTextBlockParams(List.of(TextBlockParam.builder().text(systemPrompt).cacheControl(CacheControlEphemeral.builder().build()).build()))`。
  - `buildTool(schema, markCache=true)` 给 tools 末项加 `.cacheControl(CacheControlEphemeral.builder().build())`。
  - `markLastUserTailForCache(messageParams)` 倒序找最后一条 user MessageParam，对其末块按 `text()` 或 `toolResult()` 重建并加 cache_control。
- F22: `Agent` 持有 `private ContentReplacementState replacementState = new ContentReplacementState()`，含 `getReplacementState() / setReplacementState(state)` 方法供 fork 路径替换。
- F23: Agent 主循环在 `client.stream` 调用前一刻：`ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState)` → 非空 `newRecords` 调 `ReplacementRecordsIO.append(sessionDir, applied.newRecords())` → `client.stream(applied.apiConv(), tools)`。
- F24: `AgentTool` 提供 `setParentReplacementState(state)` 与 `parentReplacementState` 字段；fork 路径 `runFork` 把 `parentReplacementState` 透传给 `SubAgentTaskManager.spawnSubAgent(..., parentState)` 的新 overload。
- F25: `SubAgentTaskManager.spawnSubAgent` 新增 6-arg overload 接受 `ContentReplacementState parentState`，在创建子 Agent 后调 `subAgent.setReplacementState(parentState.copy())`。

### 3.5 压缩后恢复

- F26: `com.cncode.compact.RecoveryState`（独立 `public final class`）含 `FileReadRecord(String path, String content, Instant timestamp)` 与 `SkillInvocationRecord(String name, String body, Instant timestamp)` 两个 record，内部用 `Object lock` 守护 `Map<String, FileReadRecord> files` 与 `Map<String, SkillInvocationRecord> skills`；`recordFileRead(path, content)` / `recordSkillInvocation(name, body)` 空 path / 空 name 直接 return，正常路径加锁写入并以 `Instant.now()` 打时间戳；`snapshotFiles(limit)` / `snapshotSkills()` 复制后按 `Comparator.comparing(...timestamp).reversed()` 排序，文件再切到 limit。
- F27: 限额常量 `RECOVERY_FILE_LIMIT = 5` / `RECOVERY_TOKENS_PER_FILE = 5_000` / `RECOVERY_SKILLS_BUDGET = 25_000` / `RECOVERY_TOKENS_PER_SKILL = 5_000` 在 `ContextCompactor` 上 `public static final` 定义；`RECOVERY_CHARS_PER_TOKEN = 3.5` 与 `RECOVERY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)` 在文件内 private。`approxTokens` 用 `len / 3.5`；`truncateByTokens` 按预算切尾追加 `\n… (content truncated)` 标记。
- F28: `public static String buildRecoveryAttachment(RecoveryState state, List<Map<String, Object>> toolSchemas)` 渲染四段（顺序：`## Recently read files` → `## Active skills` → `## Available tools` → `## Note`）；任一段为空就跳过；全空返回 `""`；技能累计字节超 `RECOVERY_SKILLS_BUDGET` 时 break。`autoCompact` 在生成 `summaryText` 后调 `buildRecoveryAttachment(recovery, toolSchemas)`，非空时用 `\n\n---\n\n` 拼到 `[Compacted conversation summary]\n\n<summary>` 之后；assistant 确认消息附在 user 消息之后。
- F29: `manage` 与 `forceCompact` 多两个参数 `RecoveryState recovery`、`List<Map<String, Object>> toolSchemas`，全部透传给 `autoCompact`。
- F30: `Agent` 持有 `private final RecoveryState recoveryState = new RecoveryState()`，加 `getRecoveryState() / getRegistry() / getProtocol()` 三个 public 方法（fork 子 Agent 与 TUI 命令需要）。Agent 主循环在 `manage` 之前先 `var iterToolSchemas = registry.getAllSchemas(protocol)` + `toolNameFilter` 过滤一次，把结果同时喂给 `manage` 与 `client.stream`；反应式 `forceCompact` 走 `*ContextTooLong` 类错误分支时把 `recoveryState` 与 `iterToolSchemas` 一起传过去。
- F31: `StreamingExecutor` 新增 6-arg 构造（兼容旧 5-arg 重载）接受 `RecoveryState recoveryState`；`executeSingle` 在 `tool.execute(call.args())` 之后调 `snapshotForRecovery(call, result)`，仅在 `recoveryState != null && !result.isError() && "ReadFile".equals(call.toolName())` 时 `Files.readString(Path.of(file_path))` 再写入 state；`IOException` 静默吞掉。`Agent` 在创建 executor 时传 `recoveryState`。
- F32: `SkillHost` 加 `default void recordSkillInvocation(String name, String body) {}` 方法（无副作用默认）；`SkillExecutor.executeInline` 在 `host.activateSkill(...)` 之后立刻调 `host.recordSkillInvocation(skill.meta().name(), body)`；`executeFork` 在 `body = substituteArguments(...)` 后调 `host.recordSkillInvocation(skill.meta().name(), skill.promptBody())`。
- F33: TUI `/compact` 命令在调 `ContextCompactor.forceCompact` 前先 `agent.getRegistry().getAllSchemas(agent.getProtocol())` 拿工具表，并把 `agent.getRecoveryState()` 一起传给新签名。

## 4. 非功能需求

- N1: Layer 1 必须廉价：纯本地文件 I/O + 字符串改写，不调 LLM；每轮 agent loop 都跑也不能成为瓶颈。
- N2: `ToolResultBudget.apply` 不能 mutate 入参 `conv` —— 通过新建 `Message` / `ToolResultBlock` 实例 + 重组 `newHistory` 产出 apiConv。测试用 `applyDoesNotMutateConv` 守住。
- N3: 已决策 id 的复读必须**字节一致**：从 `state.replacements` 拿出来的字符串直接 `decisions.put(id, ...)`，不重新读盘、不重新格式化。这是 prompt cache 命中的硬约束。
- N4: spill 写盘幂等：同 `tool_use_id` 重复运行写同一份内容，同 size 文件已存在则跳过；spill 文件路径稳定（`<sessionDir>/tool_results/<tool_use_id>`），不含时间戳。
- N5: Layer 2 期间不能再触发新的 tool call —— `requestSummary` 走一次性 `client.stream(summaryConv, null)`，tools 传 null 禁用工具调用。
- N6: `ContextCompactor.manage` 替换 conversation 用就地写法：通过 `getMessagesMutable().clear() + addAll(source.getMessages())` 让调用方持有的 `ConversationManager` 引用保持有效。
- N7: 摘要失败抛 `RuntimeException`，由调用方在 `Agent.agentLoop` 内 `try / catch (Exception ignored)` 兜底，确保压缩失败不中断对话。
- N8: 反应式 `forceCompact` 用 `try / catch (Exception ignored)` 包住，失败不影响 Agent 主流程退出错误处理分支。
- N9: 子 Agent fork 的 state 必须是父 state 的**独立深拷贝**：子端 mutate 不影响父端，反向亦然。`new HashSet<>(src.seenIds)` 与 `new HashMap<>(src.replacements)` 浅拷贝足够（值是字符串和 hash key，不需要 deepcopy）。测试用 `copyIsIndependent` 守住。
- N10: 工具类无副作用：`ContextCompactor` / `ToolResultBudget` / `ContentReplacementLifecycle` / `ReplacementRecordsIO` 全部 `final class` + `private` 构造函数 + `public static` 方法，状态完全无副作用。
- N11: `RecoveryState` 必须并发安全：`StreamingExecutor` 用 `Executors.newVirtualThreadPerTaskExecutor()` 并发跑 ReadFile，多个回写可能交错。结构体内 `synchronized (lock)` 保护两张 map；`record*` 方法在空 path / 空 name 上直接 return，方便测试与一次性脚本调用。
- N12: 恢复块限额是**硬上限**：5 个文件、单文件 5K token、技能预算 25K token、单技能 5K token。超出预算时静默丢弃（不抛错），保证压缩输出体积可预测——压缩后摘要 + 恢复总长稳定在约 60K token 以内，远低于 `AUTOCOMPACT_THRESHOLD = 0.80`。

## 5. 设计概要

- 核心包结构（两个 Java 包）:
  - `com.cncode.toolresult/`（新包，7 个类）:
    - `ContentReplacementState.java` — 状态容器 + `copy()`。
    - `ContentReplacementRecord.java` — record + 静态工厂。
    - `ApplyResult.java` — `apply` 返回值 record。
    - `ToolResultBudget.java` — 阈值常量 + `apply` 主流程 + 内部辅助（`spillAndPreview / isAlreadyReplaced / snipStale / copyMessageWithResults / buildManager`）。
    - `ReplacementRecordsIO.java` — JSONL append / load。
    - `ContentReplacementLifecycle.java` — `reconstruct`。
  - `com.cncode.compact/`（升档、摘要与恢复）:
    - `ContextCompactor.java` — 4 个 ratio 阈值 + 4 个尺寸常量 + 4 个恢复限额常量 + `SUMMARY_SYSTEM_PROMPT` + `manage` + `forceCompact` + 4 个 layer 方法 + `buildRecoveryAttachment` + helpers (`estimateTokens / requestSummary / serializeForSummary / truncatePreservingBoundaries / appendMessage / rebuildConversation / replaceConversation / approxTokens / truncateByTokens / firstLine`)。`applyToolResultBudget` 标 `@Deprecated`，过渡期保留兼容。
    - `RecoveryState.java` — `public final class` 含 `FileReadRecord` / `SkillInvocationRecord` 两个 record + `record*` / `snapshot*` 方法。
- 主流程（每轮 agent loop）:
  - 主循环开头先 `var iterToolSchemas = registry.getAllSchemas(protocol)` + `toolNameFilter` 过滤，避免重算并保证恢复消息与 API 请求看到的工具集一致。
  - `String compactMsg = ContextCompactor.manage(conv, client, contextWindow, wd, compactTracking, recoveryState, iterToolSchemas)`，非空时 `CompactEvent` 推到事件队列。
  - 各种 system reminder 写入 conv。
  - 在 `client.stream` 调用前一刻：`ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState)` → 非空 records 调 `ReplacementRecordsIO.append(sessionDir, applied.newRecords())` → `client.stream(applied.apiConv(), iterToolSchemas)`。
- 主流程（工具调用快照）:
  - `StreamingExecutor.executeSingle` 在 `tool.execute(...)` 之后调 `snapshotForRecovery(call, result)`；命中 ReadFile + 非错误时 `Files.readString(Path.of(file_path))` 写入 `recoveryState`。Agent 构造 `StreamingExecutor` 时把 `recoveryState` 透传过去。
- 主流程（Skill 调用快照）:
  - 上层命令调 `SkillExecutor.executeInline / executeFork` → `host.recordSkillInvocation(name, body)`（`SkillHost` 默认 no-op，Agent 实现把它桥接到 `recoveryState.recordSkillInvocation`）。
- 主流程（反应式恢复）:
  - LLM 流返回 context 类错误 → `agentLoop` 错误恢复分支 → `RetryEvent` 通知用户 → `ContextCompactor.forceCompact(conv, client, contextWindow, recoveryState, iterToolSchemas)` → `continue` 重试当前轮。
- 主流程（TUI `/compact`）:
  - 用户输入 `/compact` → `CNCodeModel` 取 `agent.getRegistry().getAllSchemas(agent.getProtocol())` + `agent.getRecoveryState()` → `ContextCompactor.forceCompact` 走 Auto-compact + 恢复块。
- 主流程（fork 子 Agent）:
  - `AgentTool.runFork` 调 `taskManager.spawnSubAgent(..., parentReplacementState)` → `SubAgentTaskManager` 6-arg overload 创建子 Agent 时 `subAgent.setReplacementState(parentState.copy())` → 子 Agent 用克隆状态独立演化。
- Anthropic 客户端缓存断点（`AnthropicClient.doStream`）:
  - `systemBlock` 用 `TextBlockParam` + `cacheControl`，包装成 `MessageCreateParams.System.ofTextBlockParams`。
  - `buildTool(schema, isLast)`：末项 `markCache=true`，调 `builder.cacheControl(...)`。
  - `markLastUserTailForCache(messageParams)` 倒序找最后一条 user MessageParam，对其末块（`text()` 或 `toolResult()`）用 `toBuilder().cacheControl(...).build()` 重建。
- 与其他模块的交互:
  - 依赖 `com.cncode.conversation`（操作 `ConversationManager / Message / ToolUseBlock / ToolResultBlock / ThinkingBlock`）。
  - 依赖 `com.cncode.llm`（`LlmClient.stream` 摘要、`StreamEvent.TextDelta` / `StreamEnd` / `Error`、Anthropic SDK 的 `CacheControlEphemeral`）。
  - 被 `com.cncode.agent.Agent`（主循环、错误恢复）、`com.cncode.subagent.AgentTool` + `SubAgentTaskManager`（fork clone）调用。

## 6. Out of Scope

- 跨进程 / 跨会话的压缩缓存。
- 持久化的 `RecoveryState`：JVM 退出后状态丢失，不做磁盘落盘。下一次启动靠用户自然触发 ReadFile / Skill 调用重新填充。
- Session memory compaction：与记忆系统配合，本章不做。
- 用真实 tokenizer 替代 `chars / 3.5` 近似估算。
- 进度回调或 UI 流式预览：本章只在压缩完成后回传一行 status。
- 熔断器：Java 版目前不做连续失败计数，失败直接 `catch (Exception ignored)` 跳过。
- 完整 resume 流程：transcript records 已落盘且 `ContentReplacementLifecycle.reconstruct` 可用，但 resume 主流程不在本章范围。
- Fork 入口本身的接入：`AgentTool.runFork` 现有路径上 `setParentConversation` 未被主流程调用（已知 dead branch），本章只确保 fork 一旦真正接入，state 继承的 API 是齐的。
- 配置化阈值：所有阈值是 `public static final` 常量，调整需改源码重编译。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch08: 上下文管理 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。每条任务记录实际落地的文件与行号。

## T1: `ContentReplacementState` + `copy()`

- 影响文件: `src/main/java/com/cncode/toolresult/ContentReplacementState.java`
- 依赖任务: 无
- 完成标准: `public final class ContentReplacementState`，含 `Set<String> seenIds = new HashSet<>()` 与 `Map<String, String> replacements = new HashMap<>()` 两 final field；accessors `seenIds()` / `replacements()` 返回可变引用；`copy()` 通过 `new HashSet<>(this.seenIds)` 与 `new HashMap<>(this.replacements)` 浅拷贝产出独立实例；测试 `newReturnsEmpty / copyIsIndependent` 通过。

## T2: `ContentReplacementRecord` record

- 影响文件: `src/main/java/com/cncode/toolresult/ContentReplacementRecord.java`
- 依赖任务: 无
- 完成标准: `public record ContentReplacementRecord(String kind, String toolUseId, String replacement)`，含静态常量 `KIND_TOOL_RESULT = "tool-result"` 与静态工厂 `toolResult(toolUseId, replacement)`。

## T3: `ApplyResult` record

- 影响文件: `src/main/java/com/cncode/toolresult/ApplyResult.java`
- 依赖任务: T1, T2
- 完成标准: `public record ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords)`。

## T4: `ReplacementRecordsIO` JSONL 持久化

- 影响文件: `src/main/java/com/cncode/toolresult/ReplacementRecordsIO.java`
- 依赖任务: T2
- 完成标准: `RECORDS_FILENAME = "replacement_records.jsonl"` 常量；`append(sessionDir, records)` 空 list 直接 return，自动 `Files.createDirectories(sessionDir)`，用 `BufferedWriter + APPEND` 选项追加，每行一个 Jackson `writeValueAsString(record)`，`kind` 为空或 null 自动填 `KIND_TOOL_RESULT`；`load(sessionDir)` 缺文件返回 `Collections.emptyList()`，`Files.readAllLines` 后逐行 `MAPPER.readValue`；测试 `appendAndLoadRoundtrip / loadMissingFile` 通过。

## T5: `ContentReplacementLifecycle.reconstruct`

- 影响文件: `src/main/java/com/cncode/toolresult/ContentReplacementLifecycle.java`
- 依赖任务: T1, T2
- 完成标准: 静态方法 `reconstruct(messages, records, inheritedReplacements)`：先 seed `seenIds` = 所有 `getToolResults` 的 `tool_use_id`；按 `kind == KIND_TOOL_RESULT` 过滤 records 并命中 candidate 才写入 `replacements`；可选 `inheritedReplacements` (Map) 用 `putIfAbsent` 在 candidate ∩ 未被覆盖时补全；测试 `reconstructFromRecords / reconstructWithInheritedParent` 通过。

## T6: `ToolResultBudget` 阈值与 Design B 主流程

- 影响文件: `src/main/java/com/cncode/toolresult/ToolResultBudget.java`
- 依赖任务: T1, T3
- 完成标准: 阈值常量 `SINGLE_RESULT_LIMIT = 15_000` / `MESSAGE_AGGREGATE_LIMIT = 20_000` / `OLD_RESULT_SNIP_CHARS = 2_000` / `KEEP_RECENT_TURNS = 10` / `SPILL_SUBDIR = "tool_results"` 在顶部 `public static final` 定义。`apply(conv, sessionDir, state) -> ApplyResult` 静态方法实现：
  1. 阶段 1 对每条 tr 四类分类（replacements 命中复读 / seenIds 命中冻结原文 / 外部 `[Result of` 或 `[Stale output snipped:` 前缀冻结作为已知决策 / fresh）。
  2. 阶段 2 Pass 1 单条 > `SINGLE_RESULT_LIMIT` 调 `spillAndPreview`，写入 state 与 records；spill 失败 freeze 原文。
  3. 阶段 3 Pass 2 聚合超限 + 按 size 降序选 fresh。
  4. 阶段 4 剩余 fresh 全部 `state.seenIds.add` + `decisions.put(id, tr.content())`。
  5. 末段 `copyMessageWithResults` 重组 message + `snipStale` + `buildManager` 重放产出新 `ConversationManager`。

## T7: spill / preview / snip / buildManager helpers

- 影响文件: `src/main/java/com/cncode/toolresult/ToolResultBudget.java`
- 依赖任务: T6
- 完成标准:
  - `spillAndPreview(spillDir, tr)`：`Files.createDirectories(spillDir)` + `Files.writeString(file, content)`；同 size 文件已存在则直接返回 preview；返回 `[Result of N chars saved to PATH — read with ReadFile if needed]`；IO 异常返回 null（caller 据此 freeze 为原文）。
  - `isAlreadyReplaced(s)` 识别 `[Result of ` 和 `[Stale output snipped:` 两种前缀。
  - `snipStale(messages)` 数 `assistant && (toolUses==null || toolUses.isEmpty())` 当作一轮；总轮数 ≤ `KEEP_RECENT_TURNS` 直接 return；超 boundary 的消息里超 `OLD_RESULT_SNIP_CHARS` 字符且未 `isAlreadyReplaced` 前缀的 tool result 整体替换为 `[Stale output snipped: N chars]`。
  - `copyMessageWithResults(src, newResults)` 产出新 `Message` 实例，复制 role/content/thinking/toolUses 引用，注入新 toolResults 列表。
  - `buildManager(messages)` 通过 `new ConversationManager()` + `addAssistantFull` / `addToolResultsMessage` / `addUserMessage` / `addAssistantMessage` 重放消息。

## T8: `ContextCompactor` 阈值与 4 层升档

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:33-83`
- 依赖任务: 无
- 完成标准: 4 个 ratio 阈值 `SNIP_THRESHOLD = 0.50` / `MICROCOMPACT_THRESHOLD = 0.60` / `COLLAPSE_THRESHOLD = 0.70` / `AUTOCOMPACT_THRESHOLD = 0.80`；4 个尺寸常量 `SNIP_CHAR_LIMIT = 2000` / `MICROCOMPACT_LIMIT = 5000` / `SINGLE_RESULT_LIMIT = 5000` / `KEEP_RECENT_TURNS = 10`；`SUMMARY_SYSTEM_PROMPT` 是 Text Block 含六类必须保留信息。`manage(conv, client, contextWindow)` 算 ratio 升档到对应层，未命中返回空串；命中 Snip 时回填 `"Snipped verbose tool results"`。

## T9: `estimateTokens`

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:131-161`
- 依赖任务: T8
- 完成标准: 函数对 `Message.content` / `ToolUseBlock.arguments`（Jackson 序列化）/ `ToolResultBlock.content` / `ThinkingBlock.thinking` 四类按 `length / 3.5` 估算并加常数偏置（content +4，tool_use +50，tool_result +10）；`safeLength` 兜空指针。

## T10: Layer 1 `snip`

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:165-199`
- 依赖任务: T8, T9
- 完成标准: 计算 `recentBoundary = max(0, size - KEEP_RECENT_TURNS * 3)`；遍历 boundary 之前的消息，超 `SNIP_CHAR_LIMIT = 2000` 的 tool result 换为 `[Output snipped: %d chars, %d lines]`；最后 `rebuildConversation` 重塑会话，返回 `boolean`。

## T11: Layer 2 `microcompact` 与 `truncatePreservingBoundaries`

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:203-237, 347-375`
- 依赖任务: T10
- 完成标准: `microcompact` 在 `recentBoundary` 之前对超过 `MICROCOMPACT_LIMIT = 5000` 的 tool result 调 `truncatePreservingBoundaries(lines, 500)` 改为头 5 行 + `... (N lines omitted) ...` + 尾 5 行；累计 `savedChars` 后返回 `"Microcompacted: saved ~%d chars from old tool results"`；`truncatePreservingBoundaries` 不足 10 行时直接 `String.join("\n", ...)` 并按 `maxChars` 兜底。

## T12: Layer 3 `contextCollapse`

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:241-274`
- 依赖任务: T9, T11
- 完成标准: 消息数不足 `KEEP_RECENT_TURNS * 3` 时降级到 `autoCompact`；按 `splitIdx = size - 30` 切分；早期段 `serializeForSummary(oldMessages, 500)` + `requestSummary` 走 LLM；新建 `ConversationManager` 装 `[Earlier conversation summary]` 用户消息 + assistant 确认消息 + 最近 30 条原样 `appendMessage`；`replaceConversation` 就地替换；返回 `"Context collapsed: N -> M estimated tokens (kept 10 recent turns)"`。

## T13: Layer 4 `autoCompact`

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:278-294`
- 依赖任务: T9, T11
- 完成标准: 全量 `serializeForSummary(messages, 500)` + `requestSummary` 走 LLM；新建 `ConversationManager` 装 `[Compacted conversation summary]\n\n<summary>` + assistant 确认消息；`replaceConversation` 就地替换；返回 `"Compacted: N -> M estimated tokens"`。

## T14: `forceCompact` 手动入口

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:86-88`
- 依赖任务: T13
- 完成标准: 跳过 4 层升档，直接调 `autoCompact(conv, client, contextWindow)`。

## T15: 摘要 helpers (`requestSummary` / `serializeForSummary` / `rebuildConversation` / `replaceConversation` / `appendMessage`)

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:298-405`
- 依赖任务: T13
- 完成标准:
  - `requestSummary` 新建临时 `ConversationManager`，调 `client.stream(summaryConv, null)`（tools = null 禁用工具），消费 `StreamEvent.TextDelta` 聚成 summary；遇 `Error` 抛 `RuntimeException`；`InterruptedException` 重置中断标志后抛。
  - `serializeForSummary` 按 `[role]: content` + `[tool_use name]: id` + `[tool_result]: content`（超过 cap 截断 + "..."）拼字符串。
  - `appendMessage` 根据 `toolUses` / `toolResults` / role 分发到 `addAssistantFull` / `addToolResultsMessage` / `addUserMessage`。
  - `rebuildConversation` 新建 ConversationManager 用 `appendMessage` 重放 + `replaceConversation` 就地替换。
  - `replaceConversation` 通过 `getMessagesMutable().clear() + addAll(source.getMessages())` 保证调用方持有的引用不失效。

## T16: Agent 集成（state 字段 + Apply 调用 + records 持久化）

- 影响文件: `src/main/java/com/cncode/agent/Agent.java:14-22, 51-54, 155-167`
- 依赖任务: T1, T4, T6
- 完成标准:
  - import 段加 `com.cncode.toolresult.{ApplyResult, ContentReplacementState, ReplacementRecordsIO, ToolResultBudget}` + `java.nio.file.{Path, Paths}`。
  - `Agent` 类新增 field `private ContentReplacementState replacementState = new ContentReplacementState()` 与 getter/setter。
  - 主循环 `client.stream` 调用前一刻：`Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".cncode/session")` → `ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState)` → 非空 `applied.newRecords()` 调 `ReplacementRecordsIO.append(sessionDir, applied.newRecords())`（失败 silently 忽略）→ `client.stream(applied.apiConv(), tools)`。

## T17: Fork 状态继承

- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java:104-108, 286-293`、`src/main/java/com/cncode/subagent/SubAgentTaskManager.java:108-137`
- 依赖任务: T1, T16
- 完成标准:
  - `AgentTool` 新增 field `private com.cncode.toolresult.ContentReplacementState parentReplacementState`，含 `setParentReplacementState` 方法。
  - `runFork` 把 `parentReplacementState` 传给 `taskManager.spawnSubAgent` 的 6-arg overload。
  - `SubAgentTaskManager.spawnSubAgent` 新增 6-arg overload 接受 `ContentReplacementState parentState`；原 5-arg overload 转调 6-arg 传 `null`；在创建 subAgent 后判断 `parentState != null` 时 `subAgent.setReplacementState(parentState.copy())`。

## T18: Anthropic 缓存断点

- 影响文件: `src/main/java/com/cncode/llm/AnthropicClient.java:65-100, 281-360`
- 依赖任务: 无
- 完成标准:
  - `systemBlock = TextBlockParam.builder().text(systemPrompt).cacheControl(CacheControlEphemeral.builder().build()).build()`；`paramsBuilder.system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))`。
  - `buildTool(schema, isLast)` 签名扩展 `boolean markCache` 参数，末项调 `builder.cacheControl(CacheControlEphemeral.builder().build())`。
  - 新增 `markLastUserTailForCache(messageParams)` 倒序找最后一条 user MessageParam，对其 `content()` 处理：string 上转 block 列表带 cache_control；block 列表对末块按 `text()` / `toolResult()` 分别用 `toBuilder().cacheControl(...).build()` 重建；最后用 `MessageParam.builder().role(USER).contentOfBlockParams(blocks).build()` 替换原 MessageParam（SDK 类型 immutable，必须替换整条消息）。
  - `messageParams = buildMessages(conv.getMessages())` 后立刻调 `markLastUserTailForCache(messageParams)`。

## T19: 测试

- 影响文件: `src/test/java/com/cncode/toolresult/ContentReplacementStateTest.java`、`src/test/java/com/cncode/toolresult/ToolResultBudgetTest.java`、`src/test/java/com/cncode/toolresult/ReplacementRecordsIOTest.java`
- 依赖任务: T1–T16
- 完成标准:
  - `ContentReplacementStateTest`: `newReturnsEmpty / copyIsIndependent` 2 case。
  - `ToolResultBudgetTest`: `applyDoesNotMutateConv / firstCallFreezesUnreplaced / replacementByteIdentical / frozenNeverReplaced / aggregateOnlyPicksFresh / reconstructFromRecords / reconstructWithInheritedParent` 7 case。
  - `ReplacementRecordsIOTest`: `appendAndLoadRoundtrip / loadMissingFile` 2 case。
  - `./gradlew test --tests "com.cncode.toolresult.*"` 全部通过。

## T20: 端到端验证

- 影响文件: 无（仅运行验证）
- 依赖任务: T16–T19
- 完成标准:
  - `./gradlew compileJava --no-daemon` 通过。
  - `./gradlew test --tests "com.cncode.toolresult.*"` 11 个测试全过。
  - 制造一个会产生大 tool result 的会话（连续 Bash 大输出），观察 `<sessionDir>/tool_results/<tool_use_id>` 文件落地、`<sessionDir>/replacement_records.jsonl` 有对应 records。
  - 制造一个会爆 context 的会话（连续 Bash 大输出），观察 Agent 事件流：按 token 占比依次出现 `CompactEvent("Snipped verbose tool results")` → `CompactEvent("Microcompacted: ...")` → `CompactEvent("Context collapsed: ...")` → `CompactEvent("Compacted: ...")`。
  - LLM 返回 context 类错误时 Agent 自动调 `forceCompact` 并 `RetryEvent` 通知用户后重试。

## T21: `RecoveryState` 类与限额常量

- 影响文件: `src/main/java/com/cncode/compact/RecoveryState.java`（新文件）、`src/main/java/com/cncode/compact/ContextCompactor.java:30-50`
- 依赖任务: T1
- 完成标准:
  - 新文件 `RecoveryState.java`：`public final class` 含两个 `public record`（`FileReadRecord(String path, String content, Instant timestamp)`、`SkillInvocationRecord(String name, String body, Instant timestamp)`）；内部 `Object lock` + `Map<String, FileReadRecord> files` + `Map<String, SkillInvocationRecord> skills`；`recordFileRead(path, content)` / `recordSkillInvocation(name, body)` 空 path / 空 name 直接 return，正常时 `synchronized (lock)` 写入并以 `Instant.now()` 打时间戳；`snapshotFiles(limit)` / `snapshotSkills()` 复制后按 `Comparator.comparing(...timestamp).reversed()` 排序，文件再切到 limit。
  - `ContextCompactor` 新增 `public static final int RECOVERY_FILE_LIMIT = 5` / `RECOVERY_TOKENS_PER_FILE = 5_000` / `RECOVERY_SKILLS_BUDGET = 25_000` / `RECOVERY_TOKENS_PER_SKILL = 5_000`；private 常量 `RECOVERY_CHARS_PER_TOKEN = 3.5` 与 `RECOVERY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)`。

## T22: `buildRecoveryAttachment` + `autoCompact` 签名扩展

- 影响文件: `src/main/java/com/cncode/compact/ContextCompactor.java:90-115, 248-360`
- 依赖任务: T21
- 完成标准:
  - `approxTokens(s)` 用 `(int)(s.length() / RECOVERY_CHARS_PER_TOKEN)`；`truncateByTokens(s, budget)` 超额时按 byte 上限切并追加 `\n… (content truncated)`；`firstLine(s)` 返回第一行非空 trim 文本。
  - `public static String buildRecoveryAttachment(RecoveryState state, List<Map<String, Object>> toolSchemas)` 渲染四段（`## Recently read files` / `## Active skills` / `## Available tools` / `## Note`）；空 state + 空 schemas 时返回 `""`；技能预算超 `RECOVERY_SKILLS_BUDGET` 时 break。
  - `manage` 与 `forceCompact` 加两个参数 `RecoveryState recovery, List<Map<String, Object>> toolSchemas`，全部透传给 `autoCompact`。
  - `autoCompact` 生成 `summaryText` 后调 `buildRecoveryAttachment(recovery, toolSchemas)`，非空时用 `\n\n---\n\n` 拼到 `[Compacted conversation summary]\n\n<summary>` 之后；assistant 确认消息依旧附在后面。
  - 新增测试 `src/test/java/com/cncode/compact/RecoveryAttachmentTest.java` 5 个用例：`emptyWhenNothingRecorded / emitsAllSections / fileLimitAndNewestFirst / truncatesPerFile / skillBudget` 全部通过。

## T23: Agent / StreamingExecutor / Skill / TUI 接入

- 影响文件: `src/main/java/com/cncode/agent/Agent.java:45-80, 100-145, 230-250, 300-320`、`src/main/java/com/cncode/agent/StreamingExecutor.java:1-60, 130-200`、`src/main/java/com/cncode/skill/SkillHost.java`、`src/main/java/com/cncode/skill/SkillExecutor.java:20-50`、`src/main/java/com/cncode/tui/CNCodeModel.java:880-900`
- 依赖任务: T21, T22
- 完成标准:
  - `Agent` 新增 `private final RecoveryState recoveryState = new RecoveryState()`，以及 `getRecoveryState() / getRegistry() / getProtocol()` 三个 public 方法。
  - Agent 主循环在 `ContextCompactor.manage` 之前先 `var iterToolSchemas = registry.getAllSchemas(protocol)` + `toolNameFilter` 过滤一次；删除原本紧贴 `client.stream` 的同段重算；`manage(...)` 与 `client.stream(applied.apiConv(), tools)` 共用 `iterToolSchemas`；`forceCompact` 在错误恢复分支处把 `recoveryState` + `iterToolSchemas` 一起传过去。
  - `StreamingExecutor` 加 6-arg 构造（兼容旧 5-arg 重载）接受 `RecoveryState recoveryState`；`executeSingle` 在 `tool.execute(call.args())` 之后调 `snapshotForRecovery(call, result)`，命中 ReadFile + 非错误时 `Files.readString(Path.of(file_path))` 写入 state；`IOException` 静默吞掉。Agent 在 `new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState)` 处传 `recoveryState`。
  - `SkillHost` 加 `default void recordSkillInvocation(String name, String body) {}`；`SkillExecutor.executeInline` 在 `host.activateSkill(...)` 之后调 `host.recordSkillInvocation(skill.meta().name(), body)`；`executeFork` 在 `body = substituteArguments(...)` 后调 `host.recordSkillInvocation(skill.meta().name(), skill.promptBody())`。
  - `CNCodeModel` 的 `/compact` 命令分支在调 `ContextCompactor.forceCompact` 之前取 `agent.getRegistry().getAllSchemas(agent.getProtocol())` + `agent.getRecoveryState()` 一并传给新签名；agent 为 null 时退化为 `List.<Map<String, Object>>of()`。

## T24: 端到端验证（恢复部分）

- 影响文件: 无（仅运行验证）
- 依赖任务: T21, T22, T23
- 完成标准:
  - `./gradlew test --tests "com.cncode.compact.*"` 含 `RecoveryAttachmentTest` 5 个用例全部通过。
  - `./gradlew test` 全套通过（旧测试不被破坏）。
  - 制造一次连续 ReadFile 6+ 文件后 `/compact` 的会话：摘要消息出现 `## Recently read files` 段且只列最近 5 个；任一 5K token 以上的文件出现 `(content truncated)` 标记。
  - 调用某个 skill 之后 `/compact`：摘要消息出现 `## Active skills` 段并包含 skill 名 + SOP 片段。
  - 摘要消息以 `## Note` 段收尾，强调若需要原文请重新读文件而不是靠摘要猜。

## 进度

- T1-T24（含「压缩后恢复」相关 T21-T24）


````markdown
# ch08: 上下文管理 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性

### 1.1 `com.cncode.toolresult` 包

- [ ] `ContentReplacementState` 在 `src/main/java/com/cncode/toolresult/ContentReplacementState.java:22` 定义：`final class` 含 `Set<String> seenIds = new HashSet<>()` + `Map<String, String> replacements = new HashMap<>()`；accessor `seenIds()` / `replacements()` 返回可变引用；`copy()` 通过 `new HashSet<>(this.seenIds)` + `new HashMap<>(this.replacements)` 浅拷贝产出独立实例。
- [ ] `ContentReplacementRecord` record 在 `ContentReplacementRecord.java:9` 定义：含 `kind / toolUseId / replacement` 三 component；`KIND_TOOL_RESULT = "tool-result"` 常量在 line 11；静态工厂 `toolResult(toolUseId, replacement)`。
- [ ] `ApplyResult` record 在 `ApplyResult.java:15` 定义：`ConversationManager apiConv` + `List<ContentReplacementRecord> newRecords` 两 component。
- [ ] `ReplacementRecordsIO` 在 `ReplacementRecordsIO.java:22` 定义：`final class` + `private` 构造；`RECORDS_FILENAME = "replacement_records.jsonl"` 常量。
- [ ] `ReplacementRecordsIO.append(sessionDir, records)`：空列表直接 return；`Files.createDirectories(sessionDir)`；用 `BufferedWriter` + `StandardOpenOption.APPEND` 追加 Jackson 序列化的对象；`kind` 为空或 null 自动填 `KIND_TOOL_RESULT`。
- [ ] `ReplacementRecordsIO.load(sessionDir)`：文件不存在返回 `Collections.emptyList()`；`Files.readAllLines` 后逐行 `MAPPER.readValue`。
- [ ] `ContentReplacementLifecycle.reconstruct(messages, records, inheritedReplacements)` 在 `ContentReplacementLifecycle.java:24` 实现，包含 candidate-only 过滤与 `putIfAbsent` 风格 gap-fill。
- [ ] `ToolResultBudget` 在 `ToolResultBudget.java:32` 定义：`final class` + `private` 构造。
- [ ] 阈值常量 `SINGLE_RESULT_LIMIT = 15_000`、`MESSAGE_AGGREGATE_LIMIT = 20_000`、`OLD_RESULT_SNIP_CHARS = 2_000`、`KEEP_RECENT_TURNS = 10`、`SPILL_SUBDIR = "tool_results"` 在 `ToolResultBudget.java:35-47` 定义。
- [ ] `ToolResultBudget.apply(conv, sessionDir, state) -> ApplyResult` 实现 Design B 主流程（4 阶段 + Pass 3 snipStale + buildManager）。
- [ ] `spillAndPreview` 输出 `[Result of N chars saved to PATH — read with ReadFile if needed]`（byte-stable anchor，不能轻改）；同 size 已存在的文件不重写。
- [ ] `snipStale` 基于轮数 boundary + `OLD_RESULT_SNIP_CHARS` 阈值，输出 `[Stale output snipped: N chars]`；不动入参 messages。
- [ ] `copyMessageWithResults` 产出新 `Message` 实例，复制 role/content/thinking/toolUses 引用，注入新 toolResults。
- [ ] `buildManager` 通过 `new ConversationManager()` + `addAssistantFull` / `addToolResultsMessage` / `addUserMessage` / `addAssistantMessage` 重放消息，产出独立 `ConversationManager`。

### 1.2 `com.cncode.compact.ContextCompactor`

- [ ] 4 ratio 阈值 `SNIP_THRESHOLD = 0.50` / `MICROCOMPACT_THRESHOLD = 0.60` / `COLLAPSE_THRESHOLD = 0.70` / `AUTOCOMPACT_THRESHOLD = 0.80` 在 `ContextCompactor.java:35-38` 定义。
- [ ] 4 个尺寸常量 `SNIP_CHAR_LIMIT = 2000` / `MICROCOMPACT_LIMIT = 5000` / `SINGLE_RESULT_LIMIT = 5000` / `KEEP_RECENT_TURNS = 10` 在 `ContextCompactor.java:40-43` 定义。
- [ ] `SUMMARY_SYSTEM_PROMPT` 在 `ContextCompactor.java:45-55` 定义（Text Block，明确列出 file paths / decisions / current task / pending work / error states / code snippets 六类必须保留信息）。
- [ ] `manage(conv, client, contextWindow)` 在 `ContextCompactor.java:66-83` 实现：按 ratio 升档到 4 层之一，未命中返回空串。
- [ ] `forceCompact(conv, client, contextWindow)` 在 `ContextCompactor.java:86-88` 实现：直接调 `autoCompact`，跳过 4 层升档。
- [ ] `estimateTokens(messages)` 在 `ContextCompactor.java:131-161` 实现：覆盖 content / tool args / tool_results / thinking_blocks 四源（共 4 个 for 分支）。
- [ ] `snip(conv)` 在 `ContextCompactor.java:165-199` 实现：遍历 `recentBoundary` 之前的消息，超 `SNIP_CHAR_LIMIT` 的 tool result 换为 `[Output snipped: %d chars, %d lines]` 一行。
- [ ] `microcompact(conv, contextWindow)` 在 `ContextCompactor.java:203-237` 实现：对超 `MICROCOMPACT_LIMIT` 的 tool result 调 `truncatePreservingBoundaries` 做头尾保留。
- [ ] `contextCollapse(conv, client, contextWindow)` 在 `ContextCompactor.java:241-274` 实现：消息数不足 30 条时降级到 `autoCompact`；否则早期段摘要后用 `[Earlier conversation summary]` + assistant 确认 + 最近 30 条原样组成新对话。
- [ ] `autoCompact(conv, client, contextWindow)` 在 `ContextCompactor.java:278-294` 实现：全量摘要 + `[Compacted conversation summary]` + assistant 确认替换整段对话；返回 `"Compacted: N -> M estimated tokens"`。
- [ ] `requestSummary` 在 `ContextCompactor.java:298-322` 实现：临时 `ConversationManager` + `client.stream(summaryConv, null)`（tools = null 禁用工具调用）；`StreamEvent.TextDelta` 聚 summary；`Error` 抛 `RuntimeException`；`InterruptedException` 重置中断标志后抛。
- [ ] `serializeForSummary` 在 `ContextCompactor.java:324-345` 实现：按 `[role]: content` + `[tool_use name]: id` + `[tool_result]: content`（cap 截断 + "..."）拼字符串。
- [ ] `truncatePreservingBoundaries` 在 `ContextCompactor.java:347-375` 实现：默认头 5 行 + `... (%d lines omitted) ...` + 尾 5 行；不足 10 行降级整段 `String.join` + `maxChars`。
- [ ] `appendMessage` / `rebuildConversation` / `replaceConversation` 在 `ContextCompactor.java:377-405` 实现，保证调用方持有的 `ConversationManager` 引用不失效。
- [ ] 工具类无副作用：`ContextCompactor` / `ToolResultBudget` / `ContentReplacementLifecycle` / `ReplacementRecordsIO` 全部 `final class` + `private` 构造函数 + `public static` 方法。
- [ ] 边界处理 `safeLength(null) == 0`、`recentBoundary = max(0, ...)`、`truncatePreservingBoundaries` 收到空数组返回空串、`contextCollapse` 在不足 30 条时降级。
- [ ] `manage` / `forceCompact` / `autoCompact` 都接受 `RecoveryState recovery` 与 `List<Map<String, Object>> toolSchemas`，`autoCompact` 在生成 `summaryText` 后用 `\n\n---\n\n` 拼接 `buildRecoveryAttachment` 的返回值。

### 1.3 `RecoveryState` 与恢复块

- [ ] 新文件 `src/main/java/com/cncode/compact/RecoveryState.java`：`public final class` + 两个 `public record`（`FileReadRecord(String path, String content, Instant timestamp)` / `SkillInvocationRecord(String name, String body, Instant timestamp)`），内部 `Object lock` 守护 `Map<String, FileReadRecord> files` + `Map<String, SkillInvocationRecord> skills`。
- [ ] `recordFileRead(path, content)` / `recordSkillInvocation(name, body)` 空 path / 空 name 直接 return；正常时 `synchronized (lock)` 写入并以 `Instant.now()` 打时间戳。
- [ ] `snapshotFiles(limit)` / `snapshotSkills()` 复制后按 `Comparator.comparing(...timestamp).reversed()` 排序，文件再切到 limit。
- [ ] `ContextCompactor` 顶部 `public static final int RECOVERY_FILE_LIMIT = 5` / `RECOVERY_TOKENS_PER_FILE = 5_000` / `RECOVERY_SKILLS_BUDGET = 25_000` / `RECOVERY_TOKENS_PER_SKILL = 5_000` 定义；private `RECOVERY_CHARS_PER_TOKEN = 3.5` + `RECOVERY_TS` (DateTimeFormatter)。
- [ ] `approxTokens(s)` / `truncateByTokens(s, budget)` / `firstLine(s)` 三个 helper；`truncateByTokens` 超额时按 byte 上限切并追加 `\n… (content truncated)`。
- [ ] `public static String buildRecoveryAttachment(RecoveryState state, List<Map<String, Object>> toolSchemas)` 依次输出 `## Recently read files` / `## Active skills` / `## Available tools` / `## Note`；空 state + 空 schemas 时返回 `""`；技能预算超 `RECOVERY_SKILLS_BUDGET` 时停止追加。

### 1.4 Agent / StreamingExecutor / Skill / TUI 接入

- [ ] `Agent` 持有 `private final RecoveryState recoveryState = new RecoveryState()`，提供 `getRecoveryState() / getRegistry() / getProtocol()` 三个 public 方法。
- [ ] Agent 主循环在 `ContextCompactor.manage` 之前先 `var iterToolSchemas = registry.getAllSchemas(protocol)` + `toolNameFilter` 过滤一次；后续 `manage` 与 `client.stream` 复用同一份；原本紧贴 `client.stream` 的同段重算被删除（避免恢复消息与请求看到的工具集不一致）。
- [ ] 反应式 `forceCompact` 调用点把 `recoveryState` + `iterToolSchemas` 一起传过去。
- [ ] `StreamingExecutor` 加 6-arg 构造重载接受 `RecoveryState recoveryState`（保留 5-arg 兼容）；`executeSingle` 在 `tool.execute(call.args())` 之后调 `snapshotForRecovery(call, result)`，仅在 `recoveryState != null && !result.isError() && "ReadFile".equals(call.toolName())` 时 `Files.readString(Path.of(file_path))` 写入 state；`IOException` 静默吞掉。Agent 在 `new StreamingExecutor(...)` 处传 `recoveryState`。
- [ ] `SkillHost` 加 `default void recordSkillInvocation(String name, String body) {}`；`SkillExecutor.executeInline` 在 `host.activateSkill(...)` 之后调 `host.recordSkillInvocation(skill.meta().name(), body)`；`executeFork` 在 `body = substituteArguments(...)` 之后调 `host.recordSkillInvocation(skill.meta().name(), skill.promptBody())`。
- [ ] `CNCodeModel` 的 `/compact` 命令分支在调 `ContextCompactor.forceCompact` 之前取 `agent.getRegistry().getAllSchemas(agent.getProtocol())` + `agent.getRecoveryState()` 一并传给新签名；agent 为 null 时退化为 `List.<Map<String, Object>>of()`。

### 1.5 Anthropic 缓存断点

- [ ] `systemBlock = TextBlockParam.builder().text(systemPrompt).cacheControl(CacheControlEphemeral.builder().build()).build()` 在 `AnthropicClient.java:72-75` 构造；`paramsBuilder.system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))` 在 `AnthropicClient.java:81`。
- [ ] `markLastUserTailForCache(messageParams)` 在 `AnthropicClient.java:323-359` 实现：倒序找最后一条 user MessageParam；string content 上转 block 列表带 marker；block 列表对末块 `text()` / `toolResult()` 用 `toBuilder().cacheControl(...).build()` 重建。
- [ ] `messageParams = buildMessages(conv.getMessages())` 后立刻调 `markLastUserTailForCache(messageParams)`（`AnthropicClient.java:76-77`）。
- [ ] `buildTool(schema, markCache)` 在 `AnthropicClient.java:294-310` 实现：`markCache=true` 时给 builder 加 `cacheControl(CacheControlEphemeral.builder().build())`；只有 tools 末项的调用传 `markCache=true`。

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `grep -rn "ContextCompactor\." src/main/java | grep -v "compact/"` 至少 3 处非测试调用方：
  - `src/main/java/com/cncode/agent/Agent.java`（`ContextCompactor.manage` 每轮 loop 调用）
  - `src/main/java/com/cncode/agent/Agent.java`（`ContextCompactor.forceCompact` 反应式恢复）
  - `src/main/java/com/cncode/tui/CNCodeModel.java`（`/compact` 命令调 `ContextCompactor.forceCompact`）
- [ ] `grep -rn "RecoveryState\b\|recoveryState" src/main/java | grep -v "compact/RecoveryState.java"` 至少 5 处：
  - `agent/Agent.java`（字段声明 + 三个 getter + `manage` / `forceCompact` / `StreamingExecutor` 传参共 5 处）
  - `agent/StreamingExecutor.java`（构造重载 + 字段 + `snapshotForRecovery`）
  - `skill/SkillExecutor.java`（`recordSkillInvocation` 两处）
  - `tui/CNCodeModel.java`（`/compact` 调 `agent.getRecoveryState()`）
- [ ] `grep -rn "ToolResultBudget\|ReplacementRecordsIO\|ContentReplacementState\|ContentReplacementRecord\|ContentReplacementLifecycle" src/main/java | grep -v "toolresult/"` 至少 5 处非测试调用方：
  - `Agent.java:16-18`（import）
  - `Agent.java:51-54`（`replacementState` 字段 + getter/setter）
  - `Agent.java:159-162`（`ToolResultBudget.apply` + `ReplacementRecordsIO.append`）
  - `subagent/AgentTool.java:105`（`parentReplacementState` 字段）
  - `subagent/AgentTool.java:107-108`（`setParentReplacementState` setter）
  - `subagent/AgentTool.java:292`（fork 调 `spawnSubAgent(..., parentReplacementState)`）
  - `subagent/SubAgentTaskManager.java:124-137`（`spawnSubAgent` 6-arg overload）
- [ ] 调用入口位于 `agent` 模块主循环（`Agent.java:159` 在 `agentLoop` 的 `for (int iteration = 1; ; iteration++)` 内、`client.stream` 调用之前）。
- [ ] 用户输入到本模块的路径可一句话描述:
  - 自动: agent loop 进入新一轮 → `ContextCompactor.manage` 按 ratio 升档跑 Snip / Microcompact / Collapse / Auto-compact 之一 → 非空 status 经 `CompactEvent` 推到事件队列 → 各种 reminder 写入 conv → `ToolResultBudget.apply` 产出 apiConv → `client.stream(apiConv, ...)`。
  - 反应式: LLM 流返回 `context` / `too long` / `prompt` 类错误 → `agentLoop` 错误恢复分支 → `RetryEvent("Context too long, compacting...", 0)` → `ContextCompactor.forceCompact` → `continue` 重试。
  - Fork: 父 Agent 调 Agent 工具 → `AgentTool.runFork` → `SubAgentTaskManager.spawnSubAgent(..., parentReplacementState)` → 子 Agent 用 `parentState.copy()` 独立演化。
- [ ] **死代码核查**：
  - `ContextCompactor.applyToolResultBudget`（仅单条 spill 的旧方法）：`grep` 不到非测试调用方，可继续保留作过渡期兼容 API，但实际工作走 `ToolResultBudget.apply`。
  - `ToolResultBudget` 公开 API 在 `Agent.agentLoop` 与 fork 路径中被引用。
  - `estimateTokens` 虽未被外部包直接调用，但仍是 package 内 `manage / autoCompact / contextCollapse` 调用所必需，非死代码。
  - `AgentTool.setParentConversation`：当前 cncode-java 主流程不调用 fork，该 setter 与 `parentReplacementState` 是预接入 API，等 fork 接入主线后自动生效。

## 3. 编译与测试

- [ ] `./gradlew compileJava --no-daemon` 通过（只允许 deprecation / unchecked 警告）。
- [ ] `./gradlew test --tests "com.cncode.toolresult.*" --no-daemon` 通过，覆盖 11 个用例：
  - `ContentReplacementStateTest`: `newReturnsEmpty / copyIsIndependent` 2 case。
  - `ToolResultBudgetTest`: `applyDoesNotMutateConv / firstCallFreezesUnreplaced / replacementByteIdentical / frozenNeverReplaced / aggregateOnlyPicksFresh / reconstructFromRecords / reconstructWithInheritedParent` 7 case。
  - `ReplacementRecordsIOTest`: `appendAndLoadRoundtrip / loadMissingFile` 2 case。
- [ ] `./gradlew test --tests "com.cncode.compact.*" --no-daemon` 通过，含 `RecoveryAttachmentTest` 5 个用例：`emptyWhenNothingRecorded / emitsAllSections / fileLimitAndNewestFirst / truncatesPerFile / skillBudget`。

## 4. 端到端验证

- [ ] Layer 1 字节稳定性：制造一轮内并行调多个 Bash、累计 > 20K 字符的会话（触发 Pass 2）；`ToolResultBudget.apply` 返回的 `apiConv` 里相关 tool_result content 变成 `[Result of ... chars saved to ...]`；下一轮再调一次，同一 `toolUseId` 的 content 与上一轮完全相等。
- [ ] Layer 1 不 mutate 原 conv：`applyDoesNotMutateConv` 守住；调 `apply` 前后 `conv.getMessages()` 各 `ToolResultBlock.content` 完全相等。
- [ ] Layer 1 frozen 不再替换：`frozenNeverReplaced` 验证「第一轮未替换的 id 在后续轮即使聚合超限也不被选中」。
- [ ] Layer 2 4 级升档：制造长对话（连续 Bash 大输出），事件流按 token 占比依次出现 `CompactEvent("Snipped verbose tool results")` → `CompactEvent("Microcompacted: saved ~K chars from old tool results")` → `CompactEvent("Context collapsed: N -> M estimated tokens (kept 10 recent turns)")` → `CompactEvent("Compacted: N -> M estimated tokens")`。
- [ ] Spill 落盘：长 Bash 输出后 `<sessionDir>/tool_results/` 目录下出现以 `toolUseId` 命名的文件。
- [ ] Transcript 落盘：`<sessionDir>/replacement_records.jsonl` 出现新条目，`jq .` 可解析。
- [ ] Fork 隔离（API 层）：通过单元测试或手动调用确认 `subAgent.setReplacementState(parent.copy())` 后子端 mutate 不影响父端。fork 主流程在 Java 当前版本上未接入，等接入后该路径自然生效。
- [ ] 反应式: LLM 返回含 `context` / `too long` / `prompt` 的错误时 Agent 自动调 `forceCompact` 并通过 `RetryEvent("Context too long, compacting...", 0)` 通知用户后重试。
- [ ] 恢复块文件段：先 ReadFile 两个不同路径再触发 `/compact`，摘要消息出现 `## Recently read files` 段、两个 `### <绝对路径>` 子段，每段内容用 ``` 包住。
- [ ] 恢复块技能段：先触发 skill 调用再 `/compact`，摘要消息出现 `## Active skills` 段并包含 skill 名 + SOP 片段。
- [ ] 恢复块工具段：摘要消息出现 `## Available tools` 段，并把当前 registry 里的工具按 `- 名字 — 描述首行` 列出。
- [ ] 恢复块收尾提示：摘要消息以 `## Note` 段收尾。
- [ ] 限额硬上限：人造 6+ 个 ReadFile 后压缩，恢复块只列最近 5 个；任一 5K token 以上的文件出现 `(content truncated)` 标记。

## 5. 文档

- [ ] spec.md / tasks.md / checklist.md 三件套齐全且最新（位于 `docs/java/ch08/`）。
- [ ] 跨分支设计文档存在：`docs/extras/content-replacement-state.md` 描述 ContentReplacementState 三分支统一设计与 Design B（不 mutate）契约。
- [ ] commit 信息标注 `ch08` 与三件套关闭状态。

````





# ch09: 记忆系统 Spec

## 1. 背景

Coding Agent 在单次会话里能聊得有上下文，但会话结束 ConversationManager 一销毁，所有「用户偏好 / 项目约定 / 重要决策」全部归零，下一次启动得从零开始解释。Claude Code 的 Memory 子系统就是为了解决这个问题：每隔几轮自动从对话里抽出值得记住的事实落盘，下次会话开头再把这些记忆注入到对话最前面，让 Agent 自己「记得」上次聊过什么。本章把这条 memory 流水线落地到 CN Code Java 版。

## 2. 目标

交付一套自动记忆系统：按 LLM 抽取的事实条目 type 双路存储——`user` / `feedback` 类记忆跟人走，写到用户级 `~/.cncode/memory/auto_memory.json`（跨项目共享）；`project` / `reference` 类记忆跟项目走，写到项目级 `<workDir>/.cncode/memory/auto_memory.json`（仅本仓库）。TUI 在 agent loop 结束时按固定轮次间隔触发后台 LLM 抽取（要求按 4 个 `### user/feedback/project/reference` 段输出，本地解析后按 type 路由到对应文件）；新会话第一条用户消息发出之前，自动把两边记忆合并后作为「Auto Memory」标题注入到 conversation 最前面（user + assistant ack 两条消息）；同时通过 system prompt 的 Memory section 把记忆同步给模型。提供 `loadInstructions` 入口读取项目根 `CNCODE.md` 或 `.cncode/INSTRUCTIONS.md` 作为 custom instructions，与 memory section 一起拼进系统提示词。TUI 暴露清除入口让用户随时重置记忆（清两个文件）。

## 3. 功能需求

- F1: `MemoryManager(workDir)` 构造时同时计算两个文件路径——`userFilePath = ~/.cncode/memory/auto_memory.json`（取自 `System.getProperty("user.home")`）与 `projectFilePath = <workDir>/.cncode/memory/auto_memory.json`，构造尾部调 `load()` 把两边的已有记忆合并到内存 `entries`。
- F2: `MemoryEntry(content, timestamp, type)` record 作为持久化单元，含可选 `type` 字段（user / feedback / project / reference 之一，旧数据缺失时为 null）；带 `@JsonInclude(NON_NULL)` 与一个 2 元便捷构造子 `MemoryEntry(content, timestamp)`，使 Jackson 既能反序列化旧 JSON（无 type 字段）也能写新数据。Jackson 序列化为 JSON 数组，pretty-printed 写回磁盘。
- F3: `load()` 容错：调用 `loadFile(userFilePath)` 与 `loadFile(projectFilePath)` 分别读取并 append 到 `entries`；任一文件不存在或 JSON 解析失败都不抛出，单边失败不影响另一边。
- F4: `save()` 按 type 拆成 `userScoped`（user / feedback）与 `projectScoped`（project / reference）两个列表，分别通过 `writeJson(path, list)` 写到两个文件；legacy 无 type 的 entry 默认归到项目级；父目录不存在时 `Files.createDirectories` 创建；IOException 静默吞掉（best-effort），不阻塞主流程。
- F5: `getMemories()` 返回当前两个目录合并后的 `content` 字符串列表；`clear()` 把 entries 清空并对两个文件都 `writeJson(path, List.of())`。
- F6: `shouldExtract()` 每次调用自增 `turnCount`，仅在 `turnCount % EXTRACTION_INTERVAL == 0` 时返回 true，对外只暴露这一个判断接口，不让调用方自己 mod。
- F7: `extract(client, conv)` 流程：消息不足 4 条直接返回；把 conversation 序列化为 `[role]: content` 行回放；起一个临时 ConversationManager 加抽取 prompt（明确要求 LLM 按 `### user / ### feedback / ### project / ### reference` 四段输出，并指出每个 type 对应的 scope）；调 `client.stream` 收 TextDelta 串文本；调 `parseTypedSections(text)` 把输出按 `### ` 标题切成 `Map<String, String>`；遍历每个 section，type 不属于 `USER_TYPES ∪ PROJECT_TYPES` 的 silently drop（避免 LLM 幻觉造类）；其余追加 `MemoryEntry(content, now, type)` 并最后调一次 `save()`。
- F8: `injectMemories(conv)` 仅当目标 conversation 为空时生效：把所有 memory（user-level + project-level 合并）拼成 `## Auto Memory\n\n<mem>\n\n` 形式的 user 消息和一条 assistant 确认（`Understood, I'll keep this context in mind.`）写入 conversation。
- F9: `loadInstructions(workDir)` 静态方法依次尝试读 `<workDir>/CNCODE.md` → `<workDir>/.cncode/INSTRUCTIONS.md`，命中即返回内容，全部失败返回空串。
- F10: `PromptBuilder.BuildOptions` 字段 `memorySection`：非空时以 priority 95 加入系统提示词，与 customInstructions（80）、skillSection（90）共同决定最终 system prompt 装配顺序。
- F11: TUI 主模型 `CNCodeModel.initializeProvider()` 初始化时构造 `new MemoryManager(workDir)`，调用 `loadInstructions` 拿到 custom instructions，调用 `buildMemorySection()` 拿到 memory section（内部已经合并 user / project 两边），三者一起进 `PromptBuilder.BuildOptions`，再走 `PromptBuilder.buildSystemPrompt`。
- F12: TUI 在用户首次发消息（conversation 为空）时调 `memoryManager.injectMemories(conversation)`；在 agent loop 结束（`loopDone`）时调 `triggerMemoryExtraction()` 后台抽取，不阻塞 UI。
- F13: TUI 把 `memoryManager::getMemories` 与 `memoryManager::clear` 通过 `CommandContext` 暴露给 slash 命令（清除入口，操作覆盖两个文件）。

## 4. 非功能需求

- N1: `extract` 必须在虚拟线程里跑（`Thread.startVirtualThread`），绝不能阻塞 TUI 主线程或 agent loop。
- N2: `injectMemories` 只在 conversation 为空时注入，重启同一会话或继续轮次时不能重复堆积「Auto Memory」消息。
- N3: 两个 `auto_memory.json` 都必须 pretty-printed 写回，方便人工 review / 手动编辑。
- N4: `load / save` 全部 IO 异常静默吞掉（包括按目录拆开后的单边失败），绝不向上抛出导致 MemoryManager 构造失败或 save 中断业务流程。
- N5: `MemoryEntry` 的 `timestamp` 用 ISO-8601 `Instant` 字符串（`DateTimeFormatter.ISO_INSTANT.format(Instant.now())`），方便排序与人读。
- N6: `EXTRACTION_INTERVAL = 5`、`MEMORY_DIR = ".cncode/memory"`、`MEMORY_FILE = "auto_memory.json"`、`USER_TYPES = Set.of("user", "feedback")`、`PROJECT_TYPES = Set.of("project", "reference")` 必须是模块级常量，不随工作目录变化。
- N7: 未知 type 的 `### ?` 段在 `parseTypedSections → extract` 流程里被显式 drop，不允许 silently 归入 project 或 user，避免 LLM 幻觉造出 USER_TYPES / PROJECT_TYPES 以外的分类。

## 5. 设计概要

- 核心数据结构:
 - `MemoryManager{workDir, userFilePath, projectFilePath, entries, turnCount}`：每实例绑定一个 workDir，分别持久化到 `~/.cncode/memory/auto_memory.json` 和 `<workDir>/.cncode/memory/auto_memory.json`。
 - `MemoryEntry(content, timestamp, type)` record：单条记忆 + ISO 时间戳 + 可选 type（4 类之一或 null），带 `@JsonInclude(NON_NULL)` + 2 元便捷构造子兼容旧 JSON。
 - 模块级常量：`EXTRACTION_INTERVAL = 5`、`MEMORY_DIR = ".cncode/memory"`、`MEMORY_FILE = "auto_memory.json"`、`USER_TYPES = Set.of("user", "feedback")`、`PROJECT_TYPES = Set.of("project", "reference")`、`MAPPER = new ObjectMapper()`。
- 主流程（启动加载）:
 - `CNCodeModel.initializeProvider()` → `new MemoryManager(workDir)` → 构造器内 `load()` 调 `loadFile(userFilePath) + loadFile(projectFilePath)` 把两个磁盘文件合并读进 `entries`。
 - 同步调 `MemoryManager.loadInstructions(workDir)` 拿 CNCODE.md / INSTRUCTIONS.md 内容作 customInstructions。
 - `buildMemorySection()` 把两个目录合并后的现有记忆拼成 `# Auto Memory\n\n<mem>\n\n` 字符串。
 - 三者塞进 `PromptBuilder.BuildOptions(customInstructions, null, memorySection)` → `PromptBuilder.buildSystemPrompt` 装配。
- 主流程（首次注入）:
 - 用户在 TUI 敲下第一条消息 → `sendUserMessage()` 看 `conversation.getMessages().isEmpty() && memoryManager != null` → `memoryManager.injectMemories(conversation)` 在用户消息入栈前先放一对「Auto Memory」user + assistant 消息。
- 主流程（后台抽取）:
 - agent loop 完成（`loopDone`）→ `triggerMemoryExtraction()` → `memoryManager.shouldExtract()` 仅在第 5 / 10 / 15 ... 轮返回 true → 虚拟线程跑 `memoryManager.extract(client, conversation)` → 拼回放（含双路 routing 指令）→ `client.stream` 拿到带 `### user/feedback/project/reference` 标题的文本 → `parseTypedSections` 切成 Map → 按 USER_TYPES / PROJECT_TYPES 过滤掉未知 type → `entries.add(new MemoryEntry(content, ts, type))` × N → `save()` 按 type 拆成两个文件分别 `writeJson`。
- 主流程（清除入口）:
 - 用户 slash 命令通过 `CommandContext` 拿到 `memoryClear` Runnable → `memoryManager.clear()` → entries 清空 + 对两个文件都 `writeJson(path, List.of())`。
- 与其他模块的交互:
 - 依赖 `com.cncode.conversation`（Message / ConversationManager）。
 - 依赖 `com.cncode.llm`（LlmClient / StreamEvent）。
 - 被 `com.cncode.prompt.PromptBuilder.BuildOptions` 通过 memorySection 字段消费。
 - 被 `com.cncode.tui.CNCodeModel` 持有、初始化、调度抽取与注入。

## 6. Out of Scope

- 记忆条目的去重 / 合并 / 自动过期；本章 entries 只 append，清理交给 `clear()`。
- 记忆条目的人工编辑 UI（用户直接改 JSON 文件即可）。
- 与 ch08 上下文压缩协同：autoCompact 后是否补充 memory 由 compact 链路自行决定，本章不做。
- 抽取粒度的进一步拆分：当前 LLM 输出每个 `### type` 段的整段文本作为一条 entry 入库；如果未来需要把 bullet 列表拆成多条独立 entry，留给后续迭代。
- 向量检索 / 语义相关性挑选：当前注入是「全部 dump」，不做相关性过滤。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch09: 记忆系统 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。本章已课程核对完成，所有 T 任务标记 [x]，每条任务记录实际落地的文件与行号。

## T1: 包结构与 `MemoryEntry` record（含 type）、模块常量
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java`（顶部包/imports + record + 常量段）
- 依赖任务: 无
- 完成标准:
 - 包 `com.cncode.memory` 建好；imports 含 `com.fasterxml.jackson.annotation.JsonInclude`、`TypeReference`、`ObjectMapper`、`Locale` 等。
 - `MemoryEntry(String content, String timestamp, String type)` record 带 `@JsonInclude(JsonInclude.Include.NON_NULL)`；附 2 元便捷构造子 `MemoryEntry(content, timestamp)` 委托 3 元构造子并把 type 置 null，确保旧的无 type JSON 反序列化与写入兼容。
 - 模块级常量齐备：`MAPPER`（ObjectMapper）、`EXTRACTION_INTERVAL = 5`、`MEMORY_DIR = ".cncode/memory"`、`MEMORY_FILE = "auto_memory.json"`、`USER_TYPES = Set.of("user", "feedback")`、`PROJECT_TYPES = Set.of("project", "reference")`。

## T2: `MemoryManager` 字段与构造器（双 filePath）
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java`
- 依赖任务: T1
- 完成标准: 字段 `userFilePath`、`projectFilePath`、`entries`（默认 `new ArrayList<>()`）、`turnCount` 齐备；构造器接收 `workDir`，把 `projectFilePath` 拼成 `<workDir>/.cncode/memory/auto_memory.json`、`userFilePath` 拼成 `<user.home>/.cncode/memory/auto_memory.json`，最后调 `load()` 合并加载两个文件。

## T3: 持久化 `load` / `save`（按 type 拆双路）
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java`
- 依赖任务: T2
- 完成标准:
 - `load()` 把 `entries` 重置后调 `loadFile(userFilePath)` 与 `loadFile(projectFilePath)` 分别追加；`loadFile(path)` 文件不存在直接 return，Jackson `readValue` 失败 silently 不抛（不影响另一边继续）。
 - `save()` 遍历 `entries` 按 `e.type()` 路由：USER_TYPES → `userScoped` 列表；PROJECT_TYPES → `projectScoped`；null 或未知 type → 默认归到 `projectScoped`（向前兼容旧 entry）。最后 `writeJson(userFilePath, userScoped)` + `writeJson(projectFilePath, projectScoped)`。
 - `writeJson(path, list)` 通过 `Files.createDirectories(path.getParent())` 保证父目录；`writerWithDefaultPrettyPrinter` 写回 JSON；IOException 静默吞掉。

## T4: 访问器 `getMemories` / `shouldExtract` / `clear`（覆盖双路）
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java`
- 依赖任务: T3
- 完成标准: `getMemories()` 返回 `entries.stream().map(MemoryEntry::content).toList()`（两路合并）；`shouldExtract()` 自增 `turnCount` 并仅在 `% EXTRACTION_INTERVAL == 0` 时返回 true；`clear()` 重置 entries 并对 `userFilePath` / `projectFilePath` 都 `writeJson(path, List.of())`，让两个文件都变成空数组。

## T5: LLM 抽取流程 `extract(client, conv)`（四段输出 + 双路写回）
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java`
- 依赖任务: T4
- 完成标准:
 - 消息少于 4 条直接 return；构造 `[role]: content\n` 形式回放。
 - 抽取 prompt 明确要求 LLM 按 4 个 `### user / ### feedback / ### project / ### reference` 段输出，并标注每个 type 对应的 scope（user-level / project-level）；指示模型「Output nothing else」+「skip empty categories」。
 - `client.stream` 收 TextDelta 拼字符串；`StreamEnd / Error` 退出循环。
 - 结果非空时调 `parseTypedSections(text)` 切成 `LinkedHashMap<String, String>`：扫描行，遇到 `### <type>` 设当前 type、清空 buffer；其余行追加到 buffer；遇到下一个 header 或结束时把 trim 后的 body merge 到 map。
 - 遍历 map：未知 type（既不在 USER_TYPES 也不在 PROJECT_TYPES）silently drop，避免 LLM 幻觉造类；其余 append `new MemoryEntry(content, ISO_INSTANT.now(), type)` 并最后调一次 `save()`。
 - `parseTypedSections` 是 package-private static 方法，便于将来加单测。

## T6: 启动时注入 `injectMemories(conv)`
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java:123-138`
- 依赖任务: T4
- 完成标准: 空 memory 直接 return；目标 conversation 必须为空才注入；拼接 `## Auto Memory\n\n<mem>\n\n` 文本作为 user 消息；紧跟一条 assistant 消息 `Understood, I'll keep this context in mind.`。

## T7: 静态入口 `loadInstructions(workDir)`
- 影响文件: `src/main/java/com/cncode/memory/MemoryManager.java:142-155`
- 依赖任务: 无
- 完成标准: 依次尝试 `<workDir>/CNCODE.md` → `<workDir>/.cncode/INSTRUCTIONS.md`；命中返回文件内容；IOException 切换到下一个；全部失败返回空串。

## T8: `PromptBuilder.BuildOptions` 引入 memorySection
- 影响文件: `src/main/java/com/cncode/prompt/PromptBuilder.java:29-32, 108-134`
- 依赖任务: T6
- 完成标准: `BuildOptions(String customInstructions, String skillSection, String memorySection)` record 第 29-32 行落位；`buildSystemPrompt` 在 129-131 行把非空 memorySection 以 priority 95 加入 sections，确保比 customInstructions（80）和 skillSection（90）更靠后输出。

## T9: TUI 初始化挂载 MemoryManager
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java:14, 104, 380-389`
- 依赖任务: T2, T7, T8
- 完成标准: import 第 14 行有 `com.cncode.memory.MemoryManager`；字段 `private MemoryManager memoryManager`（第 104 行）；`initializeProvider()` 内 380 行 `new MemoryManager(workDir)`、383 行 `MemoryManager.loadInstructions(workDir)`、384 行 `buildMemorySection()`、385-388 行装 `BuildOptions` 并调 `PromptBuilder.buildSystemPrompt`。

## T10: TUI 首次注入与 slash 命令暴露
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T9
- 完成标准:
 - `CommandContext` 构造把 `memoryManager::getMemories` 与 `memoryManager.clear` 暴露给 slash 命令（`clear` 操作会清两个文件）。
 - `sendUserMessage()` 在 `conversation.getMessages().isEmpty() && memoryManager != null` 时调 `memoryManager.injectMemories(conversation)`。
 - 私有方法 `buildMemorySection()` 拼 `# Auto Memory\n\n<mem>\n\n` 字符串（`mem` 取自 `memoryManager.getMemories()`，已经是双路合并后的列表）。

## T11: 后台抽取调度 `triggerMemoryExtraction`
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java:1137, 1165-1169`
- 依赖任务: T5, T9
- 完成标准: `loopDone` 分支（1137 行）调用 `triggerMemoryExtraction()`；该方法（1165-1169 行）守护 `memoryManager != null && client != null`，再调 `memoryManager.shouldExtract()` 决定是否启动；命中时 `Thread.startVirtualThread(() -> memoryManager.extract(client, conversation))` 不阻塞 UI。

## T12: 端到端验证（双路）
- 影响文件: 无（仅运行验证）
- 依赖任务: T9, T10, T11
- 完成标准:
 - `./gradlew build` 通过。
 - 启动 CN Code，与 Agent 聊 5 轮以上，让对话覆盖至少一个 user 偏好和一个 project 信息（如「我喜欢函数式」+「项目用 PostgreSQL 15」）；loop 结束后 `~/.cncode/memory/auto_memory.json`（user 条目）与 `<workDir>/.cncode/memory/auto_memory.json`（project 条目）分别出现至少 1 条 `MemoryEntry`（pretty-printed JSON，含 `type` 字段）。
 - 重启 CN Code，发出第一条消息前，对话顶端能看到 `## Auto Memory` user 消息与 assistant 确认消息各一条，且消息内容包含两个目录的记忆；模型回复体现出对上次会话内容的记忆。
 - 项目根放一份 `CNCODE.md`，重启后 system prompt 应包含 `# Project Instructions` 段（来自 `loadInstructions`）。
 - 在 TUI 通过 slash 命令清除记忆后，两个 `auto_memory.json` 都变成空数组 `[]`。

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10
- [ ] T11
- [ ] T12（开发者本机已跑 `./gradlew build` 与端到端记忆抽取/注入验证）


# ch09: 记忆系统 Checklist

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性

- [ ] 常量 `EXTRACTION_INTERVAL = 5`、`MEMORY_DIR = ".cncode/memory"`、`MEMORY_FILE = "auto_memory.json"`、`USER_TYPES = Set.of("user", "feedback")`、`PROJECT_TYPES = Set.of("project", "reference")` 在 `src/main/java/com/cncode/memory/MemoryManager.java` 模块级定义。
- [ ] 静态字段 `MAPPER = new ObjectMapper()` 在 `MemoryManager.java` 定义，被 `loadFile / writeJson` 共用。
- [ ] record `MemoryEntry(String content, String timestamp, String type)` 在 `MemoryManager.java` 定义，带 `@JsonInclude(NON_NULL)` 与 2 元便捷构造子（委托 3 元构造，type=null），兼容旧 JSON 反序列化。
- [ ] 字段 `userFilePath / projectFilePath / entries / turnCount` 在 `MemoryManager.java` 定义；`entries` 初始为 `new ArrayList<>()`。
- [ ] 构造器 `MemoryManager(String workDir)` 实现：`userFilePath = <user.home>/.cncode/memory/auto_memory.json`、`projectFilePath = <workDir>/.cncode/memory/auto_memory.json`，调 `load()` 合并加载两边。
- [ ] `load()` 实现：把 `entries` 重置后调 `loadFile(userFilePath)` + `loadFile(projectFilePath)` 分别 append；`loadFile(path)` 文件不存在直接 return，`readValue` 失败 silently（不抛、不影响另一边）。
- [ ] `save()` 实现：遍历 `entries` 按 `type` 路由到 `userScoped`（USER_TYPES）/ `projectScoped`（PROJECT_TYPES，含 null 兼容旧数据）；`writeJson(userFilePath, userScoped)` + `writeJson(projectFilePath, projectScoped)`。
- [ ] `writeJson(path, list)` 实现：`Files.createDirectories(path.getParent())`；`writerWithDefaultPrettyPrinter` 写回；IOException 静默吞掉（best-effort）。
- [ ] `getMemories()` 实现：返回 `entries.stream().map(MemoryEntry::content).toList()`（两路合并）。
- [ ] `shouldExtract()` 实现：先自增 `turnCount`，再返回 `turnCount % EXTRACTION_INTERVAL == 0`。
- [ ] `clear()` 实现：清空 entries 并对 userFilePath / projectFilePath 都 `writeJson(path, List.of())`，让两个文件都变成空数组。
- [ ] `extract(LlmClient, ConversationManager)` 实现：消息 < 4 条 return；拼 `[role]: content\n` 回放；抽取 prompt 要求 LLM 按 `### user / ### feedback / ### project / ### reference` 四段输出（含每个 type 的 scope 说明）；`client.stream` 收 TextDelta 串到 StringBuilder；遇 StreamEnd / Error 退出；调 `parseTypedSections(text)` 切段；按 USER_TYPES / PROJECT_TYPES 过滤掉未知 type 段；每个有效段 append `MemoryEntry(content, ISO_INSTANT.now(), type)`；最后调一次 `save()`。
- [ ] `parseTypedSections(text)` 静态 package-private 方法实现：按行扫描，`### <type>` 开新 section 并把当前 buffer trim 后 merge 到 LinkedHashMap，type 小写化保证后续 set lookup 准确；非 header 行追加到当前 buffer；EOF 时再 flush 一次。
- [ ] `injectMemories(ConversationManager)` 实现：空记忆 return；目标 conversation 为空才注入；拼 `## Auto Memory\n\n<mem>\n\n` 作为 user 消息 + 一条 assistant 确认 `Understood, I'll keep this context in mind.`。
- [ ] 静态方法 `loadInstructions(String workDir)` 实现：依次尝试 `<workDir>/CNCODE.md`、`<workDir>/.cncode/INSTRUCTIONS.md`；命中返回内容；全部失败返回 `""`。
- [ ] `PromptBuilder.BuildOptions` record 含 `memorySection` 字段。
- [ ] `PromptBuilder.buildSystemPrompt` 把非空 `memorySection` 以 priority 95 加入 builder（高于 customInstructions 的 80 与 skillSection 的 90）。

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `grep -rn "MemoryManager" src/main/java` 至少 3 处非测试调用点：
 - `src/main/java/com/cncode/tui/CNCodeModel.java:14`（import）
 - `src/main/java/com/cncode/tui/CNCodeModel.java:104`（字段声明）
 - `src/main/java/com/cncode/tui/CNCodeModel.java:380`（`initializeProvider` 内 `new MemoryManager(workDir)`）
- [ ] `MemoryManager.loadInstructions(workDir)` 在 `CNCodeModel.java:383` 被调用并塞进 `BuildOptions.customInstructions`。
- [ ] 私有方法 `buildMemorySection()` 在 `CNCodeModel.java:1154-1163` 定义；被 `initializeProvider()`（384 行）调用，结果塞进 `BuildOptions.memorySection`。
- [ ] `memoryManager.injectMemories(conversation)` 在 `CNCodeModel.java:1000-1002` 被调用，且守护条件是 `conversation.getMessages().isEmpty() && memoryManager != null`，防止重复注入。
- [ ] `memoryManager::getMemories` 和 `memoryManager.clear` 通过 `CommandContext`（`CNCodeModel.java:981-982`）暴露给 slash 命令链路。
- [ ] `triggerMemoryExtraction()` 在 `CNCodeModel.java:1165-1169` 定义，被 `loopDone` 分支（1137 行）调用；内部用 `Thread.startVirtualThread` 跑 `extract`，不阻塞 UI 线程。
- [ ] 用户输入到本模块的路径可一句话描述:
 - 启动加载: `CNCodeModel.initializeProvider()` → `new MemoryManager(workDir)` → 构造器内 `load()` 合并加载 user-level + project-level 两个 JSON → memories 进 system prompt（priority 95）。
 - 首次注入: 用户敲第一条消息 → `sendUserMessage()` 判 conversation 空 → `injectMemories(conv)` 写入 user + assistant ack（含双路合并后的记忆）。
 - 后台抽取: agent loop 结束 → `triggerMemoryExtraction()` → `shouldExtract()` 第 5/10/15... 轮 → 虚拟线程 `extract(client, conv)` → LLM 输出 4 段 ### → `parseTypedSections` 切段 → 按 type 路由后 save → 两个 `auto_memory.json` 分别新增条目。
 - 清除: slash 命令调 `memoryClear` Runnable → `MemoryManager.clear()` → 两个文件都落回 `[]`。
- [ ] **死代码核查**：`MemoryManager` 所有 public 方法（构造器 / `getMemories` / `shouldExtract` / `clear` / `extract` / `injectMemories` / `loadInstructions`）在 `CNCodeModel` 中均有调用方；`MemoryEntry`（含可选 `type` 字段）通过 Jackson 间接消费，不是死代码。

## 3. 编译与测试

- [ ] `./gradlew build` 通过。
- [ ] `./gradlew test` 通过（若有覆盖 memory 的单测）。
- [ ] IDE / `./gradlew compileJava` 无 unused import 警告（`MemoryManager` 在 `CNCodeModel.java:14` 真实被使用）。

## 4. 端到端验证

- [ ] 启动 CN Code 与 Agent 自然聊 5 轮以上，对话至少覆盖一个 user 偏好（如「我喜欢函数式」）和一个 project 信息（如「项目用 PostgreSQL 15」）；loop 结束触发抽取后，`~/.cncode/memory/auto_memory.json`（含 type=user / feedback 的条目）与 `<workDir>/.cncode/memory/auto_memory.json`（含 type=project / reference 的条目）分别出现至少 1 条 `{"content": "...", "timestamp": "...", "type": "..."}` 条目，文件是 pretty-printed JSON。
- [ ] 重启 CN Code（同一 workDir），发出第一条消息前，对话顶部能看到 `## Auto Memory` 起头的 user 消息 + assistant 一句 `Understood, I'll keep this context in mind.`，消息内容包含两个目录的记忆；模型回答时能引用上次会话提到过的事实。
- [ ] 换到一个新的 workDir 启动 CN Code，对话顶部仍能看到 user-level 记忆（来自 `~/.cncode/memory/`），但不再看到旧 workDir 的 project 记忆 —— 证明 user/feedback 跨项目共享、project/reference 只在本仓库可见。
- [ ] 项目根放一份 `CNCODE.md`，重启后 `PromptBuilder.buildSystemPrompt` 输出里能找到 `# Project Instructions` 段（验收方式：用 IDE 调试或在 `LlmClient.create` 前打日志看 systemPrompt 字符串）。
- [ ] 在 TUI 通过 slash 命令调用 `memoryClear` 后，再看两个 `auto_memory.json` 内容都变成空数组 `[]`，下次启动不再注入 Auto Memory 块。
- [ ] 当 conversation 起点已经有消息时，再调一次 `memoryManager.injectMemories(conv)` 不会重复堆叠 Auto Memory（验收：`if (conv.getMessages().isEmpty())` 守护条件）。

## 5. 文档

- [ ] spec.md / tasks.md / checklist.md 三件套齐全且最新（位于 `/Users/codemelo/cncode/docs/java/ch09/`）。
- [ ] commit 信息标注 `ch09` 与三件套关闭状态（验收阶段产物，待用户审阅后随后续 commit 一并打标）。






# ch10: Slash 命令系统 Spec（Java 版）

## 1. 背景

TUI 在没有命令机制之前只能做一件事：把输入框里的文本当成用户消息扔给 LLM。但用户在终端里其实有大量「非对话」诉求——查看当前状态、清空会话、压缩上下文、列出记忆、切换权限模式、恢复历史会话、复用预置 Prompt。这些诉求每次都靠自然语言重复表达既费 token 又不可预测。CN Code 用 Slash 命令解决：以 `/` 开头的输入直接被 TUI 拦截，分发到本地处理器，要么打印一段同步输出，要么直接驱动 TUI 状态切换，要么生成一段 Prompt 注入到对话里。本章把整套机制在 Java 端落地，并把 Skill 注册成动态 `PROMPT` 命令，让用户像调用 `/help` 一样调 `/lark-mail`。

## 2. 目标

交付一套 Slash 命令注册中心：内置 11 个常用命令、提供注册扩展接口、按前缀模糊搜索、TUI 输入区出现「以 `/` 开头」的字符串时自动弹出菜单（上下箭头选中、Enter/Tab 执行、Esc 退出），按命令的 `CommandType` 分别走「本地输出」「TUI 状态切换」「Prompt 注入」三条路径。同时把 SkillCatalog 中的 Skill 自动注册成 `[skill]` 后缀的 `PROMPT` 命令，让 `/lark-mail`、`/spec-prompt` 等技能可以走完全相同的菜单与执行链路。

## 3. 功能需求

### 命令模型

- F1: `Command` 是不可变 `record`，含 `name / description / aliases / type / hidden` 五个字段；提供 `matches(input)` 做精确匹配（含别名）。
- F2: `CommandType` 枚举 3 种：`LOCAL`（同步处理器，返回文本输出）、`LOCAL_UI`（TUI 副作用，无文本输出）、`PROMPT`（生成 Prompt 字符串注入会话）。
- F3: `CommandContext` 是不可变 `record`，封装命令执行所需的运行时上下文（`args / workDir / model / permissionMode / toolCount / tokenCount / memoryList / memoryClear / sessionInfo / skillList`），其中各类信息以 `Supplier` / `Runnable` / `IntSupplier` 懒求值，避免提前查询不必要的状态。

### 注册中心

- F4: `CommandRegistry` 构造时调 `registerDefaults()`，默认注册 11 个命令：`help` / `clear` / `compact` / `status` / `memory` / `plan` / `do` / `session` / `permission` / `resume` / `skills` / `review`。
- F5: `register(cmd, handler)` 同时把 handler 注册到命令名与所有别名上；handler 可为 `null`（仅 `LOCAL_UI` 类型使用）。
- F6: `search(prefix)` 在所有非 hidden 命令上做大小写不敏感前缀匹配（命令名 + 任意别名），结果按命令名升序排列，供菜单弹窗使用。
- F7: `find(name)` 通过 `Command#matches` 在命令清单中精确匹配命令名或别名，返回 `Optional<Command>`。
- F8: `execute(name, ctx)` 是 `LOCAL` / `PROMPT` 的统一执行入口：优先用 `name` 直接命中 handler；命中不到时退化到 `find` 再取规范名 handler；都没有时返回 `"Unknown command: "` 或 `"No handler registered for /…"`。
- F9: `listAll()` 返回所有命令的不可变视图；`listVisible()` 按名字升序返回所有非 hidden 命令，供 `/help` 渲染。

### 内置命令

- F10: `/help [name]`（别名 `h / ?`，`LOCAL`）。无参时输出可见命令清单 + 别名 + 简介 + 末尾「Type /help <command> for details.」；有参时输出对应命令的详情，找不到时回退「Unknown command: <name>」。
- F11: `/clear`（`LOCAL_UI`）。清空 `chatMessages` 并重置 `ConversationManager`。
- F12: `/compact`（别名 `c`，`LOCAL_UI`）。调 `ContextCompactor.forceCompact`，成功时打印 `⟳ <summary>`，失败时打印 `Compact failed: <error>`。
- F13: `/status`（别名 `s`，`LOCAL`）。打印 6 行状态卡片：`Mode / Tokens / Tools / Memories / Model / Directory`。
- F14: `/memory [list|clear]`（`LOCAL`）。无参或 `list` 列出全部 `[type] name — description`（空时输出 `"No memories stored yet."`）；`clear` 调 `ctx.memoryClear()` 后输出 `"All auto-memories cleared."`；其他子命令返回 `"Usage: /memory [list|clear]"`。
- F15: `/plan`（别名 `p`，`LOCAL_UI`）。把 `permChecker` 切到 `PermissionMode.PLAN`，记录前一个模式到 `prePlanMode`，调 `PlanFile.getOrCreatePlanPath` 拿 plan 路径并打印 banner。
- F16: `/do`（`LOCAL_UI`）。把 `permChecker` 还原到 `prePlanMode`（缺省 `DEFAULT`），重置 `PlanFile`，如果 plan 已落地则追加 plan 路径提示。
- F17: `/session [list|info]`（`LOCAL`）。当前两个子命令复用 `ctx.sessionInfo()` 返回当前会话标识；其他子命令返回 `"Usage: /session [list|info]"`。
- F18: `/permission [info|mode|rules]`（别名 `perm`，`LOCAL`）。`info` 打印当前权限模式；`mode` 打印 `"Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>"`；其他子命令统一返回 `"Usage: /permission [info|mode <mode>|rules]"`。
- F19: `/resume`（别名 `r`，`LOCAL_UI`）。读 `SessionManager.listSessions(workDir)` 填 `resumeSessions / resumeFiltered`，把 `state` 切到 `AppState.RESUME` 进入会话恢复选择界面。
- F20: `/skills`（`LOCAL`）。打印 `ctx.skillList()` 内容，空时提示 `"No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md"`。
- F21: `/review [focus]`（`PROMPT`）。生成「review current git diff」的固定 Prompt，模板含「Logic errors / Security issues / Performance problems / Code style」四个 review 维度；用户传参时拼接 `"Additional focus: …"`。

### Skill 命令

- F22: `wireSkillsToAgent()` 在 Skill 与 Agent 接入完成后调用，把 `SkillCatalog#list()` 中的每个 Skill 注册成 `[skill]` 后缀的 `PROMPT` 命令。
- F23: `registerSkillCommand(name)` 幂等：已注册的命令不重复注册；命令描述为 `meta.description() + " [skill]"`；handler 在执行时再从 `SkillCatalog` 取最新 prompt body。

### TUI 集成

- F24: `CNCodeModel` 保留一个 `cmdRegistry` 单例字段，构造期间通过 `new CommandRegistry()` 初始化。
- F25: `updateSlashMenu()` 监听 `inputBuffer`：当文本以 `/` 开头且尚未出现空格时调 `cmdRegistry.search(prefix)` 填充 `slashMatches`，命中即弹菜单；否则关闭菜单。
- F26: 菜单弹出时上下箭头移动 `slashCursor`，Enter/Tab 选中后调 `executeSlashCommand(cmd, "")`，Esc 关闭菜单；其他字符继续追加到 `inputBuffer` 并重新刷新菜单。
- F27: Enter 提交时若输入以 `/` 开头，按 `/<cmd>[ args]` 切分后通过 `cmdRegistry.find` 命中并执行；未命中时输出 `"Unknown command: /<cmd> — type /help to see available commands"`。
- F28: `executeSlashCommand` 按 `CommandType` 分支：`LOCAL` 把 `cmdRegistry.execute` 的返回值塞进 `chatMessages` 作为 system 行；`LOCAL_UI` 直接执行 TUI 副作用（clear / compact / plan / do / resume）；`PROMPT` 把生成的 prompt + 用户参数依次 `conversation.addUserMessage`，再调 `agent.run` 进入流式回答（命令描述以 `[skill]` 结尾时额外打印一行 `skill(<name>) Successfully loaded skill`）。
- F29: `buildCommandContext(args)` 把 `args` 与当前运行时状态打包成 `CommandContext`，所有字段以 `Supplier` 形式延迟求值。

## 4. 非功能需求

- N1: `Command` / `CommandContext` 必须是 `record`（不可变值类型）。
- N2: 别名匹配在 `register` 阶段写入 `handlers` map，避免每次执行都做线性遍历。
- N3: `search` 与 `find` 不修改命令列表；菜单结果以 `Comparator.comparing(Command::name)` 稳定排序。
- N4: `LOCAL` 处理器返回 `null` 或空串时不向 `chatMessages` 追加，避免出现空 system 行。
- N5: `PROMPT` 命令必须先 `addUserMessage(prompt)` 再 `addUserMessage(args)`，确保 LLM 看到的对话顺序与用户预期一致。
- N6: Skill 注册必须幂等，重复调用 `wireSkillsToAgent` 不会产生重复命令。
- N7: `cmdRegistry` 在 `CNCodeModel` 构造后立即可用，菜单弹出不依赖 Agent / Client 已经初始化。

## 5. 设计概要

### 核心数据结构

- `Command`：`record(String name, String description, String[] aliases, CommandType type, boolean hidden)`，含 `matches` 方法。
- `Command.CommandType`：枚举 `LOCAL / LOCAL_UI / PROMPT`。
- `CommandContext`：`record(args, workDir, model, permissionMode, toolCount, tokenCount, memoryList, memoryClear, sessionInfo, skillList)`，含 `Supplier / Runnable / IntSupplier` 字段。
- `CommandRegistry`：`commands: List<Command>` + `handlers: Map<String, Function<CommandContext, String>>`，构造时调 `registerDefaults`。

### 主流程

- 启动：`CNCodeModel` 构造 → `cmdRegistry = new CommandRegistry()`（含 11 个默认命令）→ Provider 选择完成后 Skill 加载 → `wireSkillsToAgent` 把每个 Skill 注册成 `PROMPT` 命令。
- 输入监听：用户在 `inputBuffer` 输入 `/` → `updateSlashMenu` 触发 → 菜单弹出 → 上下箭头浏览 / Enter 选中。
- 命令执行：Enter 提交 `/<name> <args>` → `cmdRegistry.find(name)` 命中 → `executeSlashCommand(cmd, args)` 按 `type` 分支。
- `LOCAL` 路径：`buildCommandContext(args)` → `cmdRegistry.execute(name, ctx)` → 输出塞进 `chatMessages`。
- `LOCAL_UI` 路径：根据 `cmd.name()` 调对应 TUI 副作用（clear / compact / plan / do / resume）。
- `PROMPT` 路径：`cmdRegistry.execute` 生成 prompt → `conversation.addUserMessage(prompt)` →（如有 args）`addUserMessage(args)` → `agent.run` → 进入 streaming 状态。

### 调用链（模块层级）

- `CN Code#main` → `CNCodeModel` 构造（`cmdRegistry` 初始化）→ TUI 主循环。
- `CNCodeModel#update` → 键盘事件分发 → `updateSlashMenu` / `executeSlashCommand`。
- `executeSlashCommand` → `CommandRegistry#execute` → 命令 handler → `chatMessages` / `conversation`。
- Skill 加载完成时：`wireSkillsToAgent` → `registerSkillCommand` → `CommandRegistry#register`。

### 与其他模块的交互

- `com.cncode.tui.CNCodeModel`：持有 `cmdRegistry`、维护 `slashMenuOpen / slashMatches / slashCursor` 菜单状态、提供 `buildCommandContext` 与 `executeSlashCommand`。
- `com.cncode.conversation.ConversationManager`：`LOCAL_UI/clear` 重置之；`PROMPT` 通过 `addUserMessage` 写入。
- `com.cncode.compact.ContextCompactor`：`/compact` 调用 `forceCompact`。
- `com.cncode.permission.PermissionChecker`：`/plan` 与 `/do` 切换权限模式。
- `com.cncode.plan.PlanFile`：`/plan` 创建 plan 文件路径、`/do` 重置。
- `com.cncode.session.SessionManager`：`/resume` 通过 `listSessions` 读取历史会话进入 RESUME 状态。
- `com.cncode.memory.MemoryManager`：`CommandContext` 通过 `Supplier` 暴露 `getMemories` / `clear`。
- `com.cncode.skill.SkillCatalog`：Skill 列表渲染（`/skills`）+ 动态注册 Skill 命令。

### 新增文件 / 类清单

新增（位于 `/Users/codemelo/cncode/src/main/java/com/cncode/command/`）：

- `Command.java`：`Command` record + `CommandType` 枚举 + `matches`。
- `CommandContext.java`：`CommandContext` record（10 个字段）。
- `CommandRegistry.java`：`commands / handlers` + `register / search / find / execute / listAll / listVisible` + `registerDefaults`（11 个内置命令）。

修改（位于 `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java`）：

- 新增字段 `cmdRegistry / slashMenuOpen / slashMatches / slashCursor`。
- 构造期 `cmdRegistry = new CommandRegistry()`。
- 新增 `wireSkillsToAgent` / `registerSkillCommand` / `updateSlashMenu` / `executeSlashCommand` / `buildCommandContext`。
- `update` 键盘事件中嵌入 slash 菜单导航与 Enter 命令分发。
- `view` 渲染区追加 slash 菜单展示。

## 6. Out of Scope

- 用户自定义命令的硬盘持久化（仅运行期注册）。
- 命令执行的撤销 / 历史回放（执行即生效）。
- 命令参数的复杂解析（仅按首个空格切分 `<cmd>` 与 `args`）。
- 远程命令同步与团队共享。
- 命令权限隔离（所有命令在同一进程内权限相同）。
- Tab 自动补全到部分匹配的最长公共前缀（当前 Tab 直接执行选中命令）。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch10: Slash 命令系统 Tasks（Java 版）

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。所有 T 任务标记 [x]，每条任务记录实际落地的文件与行号。

## T1: Command record 与 CommandType 枚举
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/command/Command.java:13-46`
- 依赖任务: 无
- 完成标准:
 - `Command` 是 `record(String name, String description, String[] aliases, CommandType type, boolean hidden)`。
 - 内嵌 `CommandType { LOCAL, LOCAL_UI, PROMPT }`，三个值的 Javadoc 与 spec F2 描述对齐。
 - `matches(String input)` 精确比较 `name` 与每一个 `alias`，命中任一即返回 `true`。

## T2: CommandContext record
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandContext.java:11-22`
- 依赖任务: 无
- 完成标准:
 - `CommandContext` 是 `record`，含 10 个字段：`args / workDir / model / permissionMode / toolCount / tokenCount / memoryList / memoryClear / sessionInfo / skillList`。
 - `permissionMode / sessionInfo / model` 使用 `Supplier<String>`；`toolCount` 使用 `IntSupplier`；`tokenCount` 使用 `Supplier<int[]>`；`memoryList / skillList` 使用 `Supplier<List<String>>`；`memoryClear` 使用 `Runnable`。

## T3: CommandRegistry 核心
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandRegistry.java:13-107`
- 依赖任务: T1, T2
- 完成标准:
 - 字段 `commands: ArrayList<Command>` + `handlers: HashMap<String, Function<CommandContext, String>>`。
 - 构造函数调 `registerDefaults()`。
 - `register(cmd, handler)` 把 handler 同时挂到 `name` 与每个 `alias`；handler 为 `null` 时仅追加命令记录。
 - `search(prefix)` 大小写不敏感前缀匹配（命令名 + 任意别名）+ 排除 hidden + `Comparator.comparing(Command::name)` 排序。
 - `find(name)` 通过 `Command#matches` 在命令清单中精确匹配，返回 `Optional<Command>`。
 - `execute(name, ctx)` 三段：直接 `handlers.get(name)` → 失败则 `find` 取规范名 → 都失败返回错误字符串。
 - `listAll()` / `listVisible()` 提供两类视图，`listVisible` 按命令名升序。

## T4: 内置命令注册（help / clear / compact / status / memory）
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandRegistry.java:113-201`
- 依赖任务: T3
- 完成标准:
 - `/help` 别名 `h / ?`，无参时输出可见命令列表 + 末尾 `"Type /help <command> for details."`；有参时输出对应命令详情，未命中返回 `"Unknown command: <name>"`。
 - `/clear` 仅 `LOCAL_UI`，handler 为 `null`。
 - `/compact` 别名 `c`，`LOCAL_UI`，handler 为 `null`。
 - `/status` 别名 `s`，`LOCAL`，输出 `Mode / Tokens / Tools / Memories / Model / Directory` 6 行。
 - `/memory`：无参或 `list` 输出 `[type] name — desc`，空时输出 `"No memories stored yet."`；`clear` 调 `ctx.memoryClear()` 后输出 `"All auto-memories cleared."`；其他子命令输出 `"Usage: /memory [list|clear]"`。

## T5: 内置命令注册（plan / do / session / permission / resume / skills / review）
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandRegistry.java:203-281`
- 依赖任务: T3, T4
- 完成标准:
 - `/plan` 别名 `p`，`LOCAL_UI`，handler 为 `null`。
 - `/do` 仅 `LOCAL_UI`，handler 为 `null`。
 - `/session`：`info`/`list` 都调 `ctx.sessionInfo()`；其他返回 `"Usage: /session [list|info]"`。
 - `/permission` 别名 `perm`：`info` 输出 `"Current permission mode: <mode>"`；`mode` 输出 `"Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>"`；其他统一 `"Usage: /permission [info|mode <mode>|rules]"`。
 - `/resume` 别名 `r`，`LOCAL_UI`，handler 为 `null`。
 - `/skills`：列出 `ctx.skillList()`，空时输出 `"No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md"`。
 - `/review` `PROMPT` 类型：固定模板含 `"Logic errors / Security issues / Performance problems / Code style"`；有 args 时追加 `"Additional focus: …"`。

## T6: CNCodeModel slash 菜单状态
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:106-110, 190`
- 依赖任务: T3
- 完成标准:
 - 新增字段 `cmdRegistry: CommandRegistry` / `slashMenuOpen: boolean` / `slashMatches: List<Command>` / `slashCursor: int`。
 - 构造函数中 `this.cmdRegistry = new CommandRegistry();`。

## T7: CNCodeModel slash 菜单刷新与导航
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:637-677, 825-835`
- 依赖任务: T6
- 完成标准:
 - `updateSlashMenu()`：仅当 `inputBuffer` 以 `/` 开头且不含空格时调 `cmdRegistry.search(prefix)`；命中即 `slashMenuOpen=true`。
 - `update` 内菜单导航分支：`up/down` 移动 `slashCursor`；`enter/tab` 选中命令调 `executeSlashCommand(cmd, "")`；`escape` 关闭菜单；其他可见字符追加到 `inputBuffer` 并重新 `updateSlashMenu`。
 - 文本回退（`backspace`）后同步刷新菜单。

## T8: CNCodeModel Enter 命令分发
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:712-735`
- 依赖任务: T6, T7
- 完成标准:
 - Enter 提交时若 `inputBuffer` 以 `/` 开头，按首空格切分为 `cmdName + cmdArgs`。
 - `cmdRegistry.find(cmdName)` 命中则清空 `inputBuffer` 并 `executeSlashCommand(cmd.get(), cmdArgs)`。
 - 未命中输出 `"Unknown command: /<cmd> — type /help to see available commands"`。

## T9: CNCodeModel executeSlashCommand 三分支
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:866-969`
- 依赖任务: T3, T6
- 完成标准:
 - `LOCAL`：`buildCommandContext(args)` → `cmdRegistry.execute(name, ctx)`；输出非空则塞进 `chatMessages` 作为 `system` 行。
 - `LOCAL_UI`：按 `cmd.name()` switch 到 `clear / compact / plan / do / resume` 对应 TUI 副作用；`clear` 重置 `chatMessages` 与 `ConversationManager`，`compact` 调 `ContextCompactor.forceCompact`，`plan` 切 `PermissionMode.PLAN` 并保存 `prePlanMode`，`do` 还原 `prePlanMode` 并重置 `PlanFile`，`resume` 填充 `resumeSessions` 并切到 `AppState.RESUME`。
 - `PROMPT`：调 `cmdRegistry.execute` 拿 prompt → `conversation.addUserMessage(prompt)` →（args 非空时）`addUserMessage(args)` → 进入 streaming → `agent.run(conversation)`；描述以 `[skill]` 结尾时额外 `Command.println` 一行 `skill(<name>) Successfully loaded skill`。

## T10: CNCodeModel buildCommandContext 工厂
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:971-988`
- 依赖任务: T2, T9
- 完成标准:
 - `buildCommandContext(args)` 返回 `new CommandContext(args, workDir, model, …)`。
 - `permissionMode = () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default"`。
 - `toolCount = () -> registry != null ? registry.listTools().size() : 0`。
 - `tokenCount = () -> new int[]{totalInput, totalOutput}`。
 - `memoryList = () -> memoryManager != null ? memoryManager.getMemories() : List.of()`。
 - `memoryClear = () -> { if (memoryManager != null) memoryManager.clear(); }`。
 - `sessionInfo = () -> sessionId != null ? "Session: " + sessionId : "No active session"`。
 - `skillList = () -> skillCatalog != null ? skillCatalog.list().stream().map(s -> s.name()).toList() : List.of()`。

## T11: Skill 动态注册成 PROMPT 命令
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:500, 511-533`
- 依赖任务: T9
- 完成标准:
 - `wireSkillsToAgent()` 遍历 `skillCatalog.list()` 调 `registerSkillCommand(meta.name())`。
 - `registerSkillCommand` 幂等：`cmdRegistry.find(name).isPresent()` 时直接返回。
 - 注册描述为 `meta.description() + " [skill]"`，`CommandType.PROMPT`。
 - handler 内部按 `captured` 重新从 `skillCatalog.get` 取最新 `promptBody`，未命中返回 `"[skill error] not found: <name>"`。

## T12: CNCodeModel slash 菜单渲染
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:1739-1748`
- 依赖任务: T7
- 完成标准:
 - `view` 渲染分隔线之后追加 slash 菜单：最多 8 行，每行 `marker + "/" + cmd.name() + " — " + cmd.description()`。
 - 选中项 marker 为 ` ❯ ` + `Styles.selectedItem`；其他项 marker 为 `   ` + `Styles.normalItem`。
 - 菜单仅在 `slashMenuOpen && !slashMatches.isEmpty()` 时显示。

## T13: 接入主流程（Skill + 默认命令）
- 影响文件: `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:500, 502`
- 依赖任务: T11
- 完成标准:
 - Provider 选择完毕初始化路径中调 `wireSkillsToAgent()`，确保 Skill 命令在 Agent 启动前已注册。
 - 启动失败路径（catch 块）不阻塞 `cmdRegistry` 已经持有的内置命令——`/help`、`/clear` 等仍可使用。

## T14: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T1-T13
- 完成标准:
 - `./gradlew build` 通过。
 - 启动 TUI 输入 `/` 弹出菜单，可见命令至少 11 个（内置）+ Skill 数量。
 - `/help` 输出包含 `clear / compact / status / memory / plan / do / session / permission / resume / skills / review`。
 - `/status` 输出 6 行（Mode / Tokens / Tools / Memories / Model / Directory）。
 - `/memory list` 在 memory 为空时输出 `"No memories stored yet."`；`/memory clear` 后输出 `"All auto-memories cleared."`。
 - `/plan` 切到 plan 模式，banner 提示 plan 文件路径；`/do` 切回 default 模式。
 - 输入 `/lark-mail` 等已安装 Skill 命令，控制台先打印 `skill(lark-mail) Successfully loaded skill`，然后 agent 进入 streaming。
 - 输入 `/notexist` 命中 `"Unknown command: /notexist — type /help to see available commands"`。

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10
- [ ] T11
- [ ] T12
- [ ] T13
- [ ] T14


# ch10: Slash 命令系统 Checklist（Java 版）

> 所有条目必须可勾选、可观测。验收方式写在每项后面的括号里。

## 1. 实现完整性

### Command record
- [ ] `Command` 在 `/Users/codemelo/cncode/src/main/java/com/cncode/command/Command.java:13-19` 是 `record(String name, String description, String[] aliases, CommandType type, boolean hidden)`。
- [ ] `CommandType` 在 `Command.java:22-29` 是内嵌枚举，三个值 `LOCAL / LOCAL_UI / PROMPT`，Javadoc 与 spec F2 一致。
- [ ] `matches(String input)` 在 `Command.java:35-45` 精确匹配 `name` 与所有 `alias`，命中任一返回 `true`。

### CommandContext record
- [ ] `CommandContext` 在 `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandContext.java:11-22` 是 `record`，含 10 个字段。
- [ ] `permissionMode / sessionInfo / model` 为 `Supplier<String>`；`toolCount` 为 `IntSupplier`；`tokenCount` 为 `Supplier<int[]>`；`memoryList / skillList` 为 `Supplier<List<String>>`；`memoryClear` 为 `Runnable`。

### CommandRegistry 核心
- [ ] 字段 `commands / handlers` 在 `/Users/codemelo/cncode/src/main/java/com/cncode/command/CommandRegistry.java:15-16` 定义。
- [ ] 构造函数 `CommandRegistry.java:19-21` 调 `registerDefaults()`。
- [ ] `register(cmd, handler)` 在 `CommandRegistry.java:33-41` 把 handler 同时注册到 `name` 与每个 alias。
- [ ] `search(prefix)` 在 `CommandRegistry.java:47-64` 大小写不敏感前缀匹配 + 排除 hidden + 按命令名升序。
- [ ] `find(name)` 在 `CommandRegistry.java:67-71` 通过 `Command#matches` 命中并返回 `Optional<Command>`。
- [ ] `execute(name, ctx)` 在 `CommandRegistry.java:80-94` 三段：直接 handler → `find` 取规范名 handler → 错误字符串（`"Unknown command: <name>"` / `"No handler registered for /<name>"`）。
- [ ] `listAll()` / `listVisible()` 在 `CommandRegistry.java:97-107` 提供两类视图，`listVisible` 按命令名升序。

### 内置命令注册
- [ ] `/help` 在 `CommandRegistry.java:113-146` 注册，别名 `h / ?`；无参时输出可见命令列表 + 末尾 `"Type /help <command> for details."`；有参时输出对应命令详情；未命中返回 `"Unknown command: <name>"`。
- [ ] `/clear` 在 `CommandRegistry.java:148-153` 注册（`LOCAL_UI`，handler=null）。
- [ ] `/compact` 在 `CommandRegistry.java:155-160` 注册，别名 `c`（`LOCAL_UI`，handler=null）。
- [ ] `/status` 在 `CommandRegistry.java:162-180` 注册，别名 `s`；输出 `Mode / Tokens / Tools / Memories / Model / Directory` 6 行。
- [ ] `/memory` 在 `CommandRegistry.java:182-201` 注册；无参或 `list` 列出全部记忆（空时 `"No memories stored yet."`）；`clear` 调 `ctx.memoryClear()` 后输出 `"All auto-memories cleared."`；其他子命令 `"Usage: /memory [list|clear]"`。
- [ ] `/plan` 在 `CommandRegistry.java:203-208` 注册，别名 `p`（`LOCAL_UI`，handler=null）。
- [ ] `/do` 在 `CommandRegistry.java:210-215` 注册（`LOCAL_UI`，handler=null）。
- [ ] `/session` 在 `CommandRegistry.java:217-230` 注册；`info`/`list` 都调 `ctx.sessionInfo()`；其他返回 `"Usage: /session [list|info]"`。
- [ ] `/permission` 在 `CommandRegistry.java:232-245` 注册，别名 `perm`；`info` 输出 `"Current permission mode: <mode>"`；`mode` 输出 `"Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>"`；其他返回 `"Usage: /permission [info|mode <mode>|rules]"`。
- [ ] `/resume` 在 `CommandRegistry.java:247-252` 注册，别名 `r`（`LOCAL_UI`，handler=null）。
- [ ] `/skills` 在 `CommandRegistry.java:254-265` 注册；空时输出 `"No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md"`。
- [ ] `/review` 在 `CommandRegistry.java:267-280` 注册（`PROMPT`）；prompt 文本含 `"Logic errors"` / `"Security issues"` / `"Performance problems"` / `"Code style"`；有 args 时附加 `"Additional focus: …"`。

### CNCodeModel 状态字段
- [ ] `cmdRegistry / slashMenuOpen / slashMatches / slashCursor` 在 `/Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java:106-110` 声明。
- [ ] 构造函数中 `CNCodeModel.java:190` 初始化 `this.cmdRegistry = new CommandRegistry();`。

### CNCodeModel 菜单刷新与导航
- [ ] `updateSlashMenu()` 在 `CNCodeModel.java:825-835` 实现：以 `/` 开头且不含空格时调 `cmdRegistry.search(prefix)`，命中弹菜单。
- [ ] 菜单导航分支 `CNCodeModel.java:637-677`：`up/down` 移动 `slashCursor`、`enter/tab` 选中后 `executeSlashCommand(cmd, "")`、`escape` 关菜单、其他字符追加并刷新菜单。
- [ ] `backspace` 路径 `CNCodeModel.java:662-664` 同步 `updateSlashMenu()`。

### CNCodeModel Enter 分发
- [ ] Enter 分支 `CNCodeModel.java:712-735`：以 `/` 开头时按首空格切分；`cmdRegistry.find(cmdName)` 命中即 `executeSlashCommand`；未命中输出 `"Unknown command: /<cmd> — type /help to see available commands"`。

### CNCodeModel executeSlashCommand
- [ ] `executeSlashCommand` 在 `CNCodeModel.java:866-969` 实现三分支。
- [ ] `LOCAL` 分支：`buildCommandContext` → `cmdRegistry.execute` → 非空输出塞进 `chatMessages`（type=`system`）。
- [ ] `LOCAL_UI` 分支：`clear` 重置 `chatMessages` 与 `conversation`；`compact` 调 `ContextCompactor.forceCompact`；`plan` 保存 `prePlanMode` 并切到 `PermissionMode.PLAN`，打印 plan 路径；`do` 还原 `prePlanMode` 并重置 `PlanFile`；`resume` 填充 `resumeSessions` 切到 `AppState.RESUME`。
- [ ] `PROMPT` 分支：`cmdRegistry.execute` 拿 prompt → `conversation.addUserMessage(prompt)` →（args 非空）`addUserMessage(args)` → streaming → `agent.run(conversation)`；描述以 `[skill]` 结尾时附加 `skill(<name>) Successfully loaded skill` 一行。

### CNCodeModel buildCommandContext
- [ ] `buildCommandContext(args)` 在 `CNCodeModel.java:971-988` 构造 `CommandContext`，所有字段使用 `Supplier / Runnable / IntSupplier`，懒求值。

### Skill 动态注册
- [ ] `wireSkillsToAgent()` 在 `CNCodeModel.java:511-516` 遍历 `skillCatalog.list()` 调 `registerSkillCommand`。
- [ ] `registerSkillCommand` 在 `CNCodeModel.java:518-533` 幂等：已注册的命令直接返回。
- [ ] Skill 命令描述为 `meta.description() + " [skill]"`，`CommandType.PROMPT`。
- [ ] Skill handler 内部按 `captured` 重新查 `skillCatalog`，未命中返回 `"[skill error] not found: <name>"`。

### CNCodeModel 渲染
- [ ] slash 菜单渲染在 `CNCodeModel.java:1739-1748`：最多 8 行；选中项 marker ` ❯ `，其他项 `   `；行模板 `marker + "/" + cmd.name() + " — " + cmd.description()`。
- [ ] 菜单仅在 `slashMenuOpen && !slashMatches.isEmpty()` 时显示。

## 2. 接入完整性（必查，杜绝死代码）
- [ ] `grep -rn "com.cncode.command" /Users/codemelo/cncode/src/main/java | grep -v "src/main/java/com/cncode/command/"` 至少命中 `CNCodeModel.java` 4 处（import + slashMatches 字段 + executeSlashCommand 签名 + 注册 Skill 命令）。
- [ ] `grep -rn "cmdRegistry\." /Users/codemelo/cncode/src/main/java | grep -v "CommandRegistry.java"` 在 `CNCodeModel.java` 命中 ≥6 处：`new CommandRegistry()` / `cmdRegistry.find` / `cmdRegistry.search` / `cmdRegistry.execute` / `cmdRegistry.register`（含 Skill 注册）。
- [ ] `grep -n "wireSkillsToAgent\|registerSkillCommand" /Users/codemelo/cncode/src/main/java/com/cncode/tui/CNCodeModel.java` 命中定义点 + 调用点。
- [ ] 用户输入到本模块的路径可一句话描述：
 - 用户输入 `/` → `updateSlashMenu` → `cmdRegistry.search` → 菜单弹出 / 上下箭头浏览 / Enter 执行。
 - 用户输入 `/<name> args` → Enter → `cmdRegistry.find` → `executeSlashCommand` → 按 `CommandType` 三分支处理。
 - Skill 注册：Provider 选择完成 → `wireSkillsToAgent` → `registerSkillCommand` → `cmdRegistry.register`。

## 3. 编译与运行
- [ ] `./gradlew build` 通过。
- [ ] 启动 TUI 输入 `/` 弹出菜单。
- [ ] 命令清单包含 11 个内置命令；已安装 Skill 显示为 `[skill]` 后缀的命令。
- [ ] `/help` 输出可见命令列表 + `"Type /help <command> for details."`。
- [ ] `/help compact` 输出 `/compact — Compress conversation context` + `Aliases: c`。

## 4. 端到端验证
- [ ] 启动 TUI，输入 `/`，菜单出现 ≥11 行命令；按下方向键 5 次后高亮变化；按 Esc 菜单消失。
- [ ] 输入 `/status` 回车，输出包含 `Mode`、`Tokens`、`Tools`、`Memories`、`Model`、`Directory` 6 个标签。
- [ ] 输入 `/memory list`，记忆为空时输出 `No memories stored yet.`；agent 写入若干记忆后再 `/memory list` 输出 `Auto-memories (<N>):` 与列表。
- [ ] 输入 `/memory clear`，输出 `All auto-memories cleared.`，再次 `/memory list` 回到 `No memories stored yet.`。
- [ ] 输入 `/plan`，status bar 中权限模式变为 `plan`，TUI 打印 `Entered Plan mode. Plan file: …`；输入 `/do` 还原。
- [ ] 输入 `/clear`，会话被清空且 `chatMessages` 中只剩 banner / 系统提示；下一条用户消息走全新会话。
- [ ] 输入 `/compact`，触发上下文压缩，TUI 顶部出现 `⟳ <summary>`。
- [ ] 输入 `/resume`，进入 `AppState.RESUME` 列表界面，可看到历史会话；Esc 返回输入界面。
- [ ] 已安装 `lark-mail` Skill 后输入 `/lark-mail` 回车，TUI 立即追加一行 `skill(lark-mail) Successfully loaded skill`，随后 agent 进入 streaming 输出 skill 引导。
- [ ] 输入 `/notexist`，输出 `Unknown command: /notexist — type /help to see available commands`。
- [ ] 输入 `/he` 不回车，菜单只剩 `/help` 一行；继续输入 `lp` 后菜单仍命中 `/help`。
- [ ] 留存证据：`docs/java/ch10/spec.md`、`tasks.md`、`checklist.md` 三件套可重复审查。

## 5. 文档
- [ ] spec.md / tasks.md / checklist.md 三件套齐全且最新（位于 `/Users/codemelo/cncode/docs/java/ch10/`）。
- [ ] commit 信息标注 `ch10` 与三件套关闭状态。






# ch11: Skills 系统 Spec

## 1. 背景

Slash Command 让用户绕过 LLM 直接触发本地动作，但所有 handler 都硬编码在源码里：想加一个 `/commit` 让 Agent 自动分析 diff、生成 message、提交，就得改 Java 再重编。Slash Command 是确定性的快车道，Skill 系统则把可扩展性补上——用户在 `.cncode/skills/<name>/` 或 `~/.cncode/skills/<name>/` 放一个 `SKILL.md`（可选 frontmatter）或 `skill.yaml + prompt.md`，启动时被发现并注册成提示型命令，运行时按 inline 或 fork 模式注入 SOP，让 Agent 借助 LLM 能力完成更复杂的工作流。

## 2. 目标

交付一套进程内的技能编目与执行链路：`SkillCatalog` 三层扫描（builtin + 用户全局 `~/.cncode/skills/` + 项目 `.cncode/skills/`）发现技能；phase-1 仅读 frontmatter 加快启动，`getFull` 触发 phase-2 重读 body 实现热更新；`SkillExecutor` 提供 `executeInline` 与 `executeFork` 两种执行模式，前者把 SOP 注入主 Agent 并按 `allowed_tools` 过滤工具，后者跑隔离的子 Agent，按 `fork_context`（none / recent / full）决定父消息种子；`SkillHost` / `SkillForkHost` 通过接口而非具体类把 Agent 状态切片暴露给 executor，避免 `com.cncode.skill` 反向依赖 agent 包。`CNCodeModel` 在 provider 就绪后调用 `loadFromDirectory` 加载项目目录，再把每个技能注册为 PROMPT 类型的 Slash Command，输入 `/<skill-name>` 时把 promptBody 当作 user message 发给 LLM，UI 上紧跟 `Successfully loaded skill` 系统消息。

## 3. 功能需求

- F1: `SkillCatalog` 暴露 `register / get / getFull / list / source / reload / loadCatalog / loadFromDirectory / buildActiveContext` 方法，内部 `skills` 与 `sources` 用 `LinkedHashMap` 保序。
- F2: 三层目录加载 `loadCatalog(workDir)`：tier 1 builtin（占位，由 agent 层装入）、tier 2 用户 `~/.cncode/skills/`、tier 3 项目 `<workDir>/.cncode/skills/`，按名字后者覆盖前者。
- F3: 单技能加载策略两选一：优先 `skill.yaml + prompt.md`（`loadFromYamlAndPrompt`），否则 `SKILL.md`（`parseSkillMD`，可选 YAML frontmatter，缺描述时回退到 body 第一行非标题）。
- F4: `getFull(name)` 触发热重载：对 `sourceDir != null` 的技能每次重读 body，读失败时保留旧缓存，避免编辑过程中读到半成品。
- F5: `SkillMeta` 字段包含 `name / description / whenToUse / tags / allowedTools / mode / model / forkContext`；name 缺省时取目录名小写化并把空格换 `-`；mode 缺省 `inline`，向后兼容 `context: fork`；`fork_context` 缺省 `none`。
- F6: `SkillExecutor.executeInline(skill, args, host)`：先 `assertAllowedToolsExist` 校验白名单工具均在 `ToolRegistry`；再 `substituteArguments` 渲染 prompt；最后通过 `host.activateSkill` 注入 SOP 并按 `allowed_tools` 调 `host.setToolFilter`，返回渲染后的 body。
- F7: `SkillExecutor.executeFork(skill, args, host)`：构造 prompt + `buildForkSeed` 种子消息，调 `host.runSubAgent` 起隔离子 Agent，把最终 assistant 文本回传。
- F8: `substituteArguments(body, args)`：args 为空原样返回；body 含 `$ARGUMENTS` 时占位符替换；否则追加 `## User Request` 段。
- F9: `buildForkSeed(mode, parent)`：`full` 全量拷贝；`recent` 取尾部最多 5 条；其他（含 `none`）返回空。
- F10: `SkillHost` / `SkillForkHost` 接口：`activateSkill / setToolFilter / toolRegistry` 由 TUI/Agent 层实现；fork 主机额外提供 `runSubAgent / snapshotParentMessages`。
- F11: `CNCodeModel.wireSkillsToAgent` 把 catalog 内每个技能注册为 PROMPT 命令，description 后缀 `[skill]` 用作分支判断；handler 返回 `promptBody`，executeCommand 在 PROMPT 分支把它当 user message。
- F12: PROMPT 分发命中 `[skill]` 后缀时，在 UI 上追加 `skill(<name>) Successfully loaded skill` 系统消息，提示用户技能已激活。

## 4. 非功能需求

- N1: `loadTier` 必须容错：目录缺失、不可读、单个技能解析失败都不中断其他技能。
- N2: phase-1 加载不能读 body：仅 frontmatter / yaml meta，避免大文件拖慢启动；body 由 `getFull` 按需加载。
- N3: `parseSkillMD` 的 YAML 解析失败要降级到「无 frontmatter」分支而不是抛异常。
- N4: `com.cncode.skill` 不允许 import `com.cncode.agent` / `com.cncode.tui`——通过 `SkillHost` / `SkillForkHost` 接口反向解耦。
- N5: `assertAllowedToolsExist` 在工具未注册时抛 `IllegalStateException`，让上层在执行前暴露配置错误，而不是运行到一半才失败。
- N6: `register(skill)` 允许同名覆盖，调用方按 tier 顺序决定优先级（后注册者胜出）。
- N7: 注册成 PROMPT 命令时 `description` 必须以 `[skill]` 结尾，作为 UI 分支识别 marker。

## 5. 设计概要

- 核心数据结构:
 - `SkillCatalog.Skill`：record(`meta`, `promptBody`, `sourceDir`, `bodyLoaded`)，`withBody` 返回带新 body 的副本
 - `SkillCatalog.SkillMeta`：record(name, description, whenToUse, tags, allowedTools, mode, model, forkContext)
 - `SkillCatalog` 内部 `Map<String, Skill> skills` + `Map<String, String> sources` 全部 `LinkedHashMap`
 - `SkillHost`：`activateSkill(name, body)` + `setToolFilter(Predicate<String>)` + `toolRegistry()`
 - `SkillForkHost extends SkillHost`：追加 `runSubAgent(body, seed, allowedTools, model)` + `snapshotParentMessages()`
- 主流程（启动期）:
 1. `CN Code.main` 装好配置 → 构造 `CNCodeModel`
 2. provider 就绪后（`CNCodeModel` line 494-498）`new SkillCatalog()` + `loadFromDirectory(<workDir>/.cncode/skills)`
 3. `wireSkillsToAgent`（line 511-516）遍历 `list()`，对每个 meta 调 `registerSkillCommand`
 4. `registerSkillCommand`（line 518-533）跳过已有命令、把技能注册为 PROMPT 类型的 `Command`，handler 在执行时从 catalog 取 `promptBody`
- 主流程（运行期 inline 模式）:
 1. 用户输入 `/<skill-name> <args>` → `executeCommand` → PROMPT 分支
 2. `cmdRegistry.execute` 返回 promptBody → `conversation.addUserMessage(promptBody)` → 若有 args 追加 `conversation.addUserMessage(args)`
 3. `agent.run` 启动新一轮 → UI 推送 `skill(<name>) Successfully loaded skill` 系统消息
 4. 后续 turn 与普通 Agent loop 一致
- 主流程（运行期 fork 模式 / Executor 直调）:
 1. 调用方持 `SkillForkHost` 实例，调用 `SkillExecutor.executeFork(skill, args, host)`
 2. `assertAllowedToolsExist` 校验工具白名单 → `substituteArguments` 渲染 prompt
 3. `buildForkSeed(skill.forkContext, host.snapshotParentMessages())` 决定种子消息
 4. `host.runSubAgent` 跑隔离 Agent，回最终文本
- 调用链:
 - 启动: `CN Code.main` → `CNCodeModel` 构造 → provider 就绪回调 → `new SkillCatalog().loadFromDirectory` → `wireSkillsToAgent` → `cmdRegistry.register`
 - 执行 inline: TUI `executeCommand`(PROMPT) → `cmdRegistry.execute` → catalog handler → 返回 promptBody → conversation → agent
 - 执行 fork（programmatic）: 外部调用 `SkillExecutor.executeFork` → `host.runSubAgent`
- 与其他模块的交互:
 - 上行: `com.cncode.tui.CNCodeModel`（注册 / 分发 / UI 提示）、`com.cncode.command.CommandRegistry`（命令注册）
 - 下行: `com.cncode.conversation.Message`（fork 种子）、`com.cncode.tool.ToolRegistry`（白名单校验）
 - 接口反转: `SkillHost` / `SkillForkHost` 由 TUI / agent 层实现，避免循环依赖

## 6. Out of Scope

- Builtin skill 真正加载（当前 tier 1 是占位，由 agent 层装入，本章不实现具体内置技能集）
- Skill 远程仓库 / 包管理：用户必须手动放文件到指定目录
- Skill 权限模型：fork 模式不再二次校验权限，沿用父 Agent 的 PermissionChecker
- Skill 链式调用 / pipeline：一次只能激活一个技能
- 文件 watcher 自动热加载目录新增技能：`getFull` 仅热重载已注册技能的 body，目录新增需 `reload(workDir)` 或重启
- Skill 配额 / 计费 / 超时控制：fork 模式不限制子 Agent 步数

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch11: Skills 系统 Tasks

## T1: 定义 SkillCatalog 数据类型与状态
- 影响文件: `src/main/java/com/cncode/skill/SkillCatalog.java`
- 依赖任务: 无
- 完成标准: `SkillMeta` / `Skill` record 字段齐全；`Skill.withBody` 副本构造可用；内部 `skills` / `sources` 用 `LinkedHashMap` 保序；`register / get / list / source` 行为对齐参考。
- 实际产出: `SkillCatalog.java:24-39`（record）、`SkillCatalog.java:43-45`（state）、`SkillCatalog.java:49-97`（公共方法）

## T2: 实现单技能加载策略
- 影响文件: `src/main/java/com/cncode/skill/SkillCatalog.java`
- 依赖任务: T1
- 完成标准: `loadSkill(dir)` 优先 `skill.yaml + prompt.md`，否则 `SKILL.md`；`loadFromYamlAndPrompt` 用 snakeyaml 解析 meta + 读取 prompt.md；`parseSkillMD` 处理可选 frontmatter，缺描述时回退到 body 第一行非标题行。
- 实际产出: `SkillCatalog.java:184-199`（loadSkill）、`SkillCatalog.java:201-219`（loadFromYamlAndPrompt）、`SkillCatalog.java:221-262`（parseSkillMD）、`SkillCatalog.java:264-313`（metaFromMap）

## T3: 实现三层目录加载与热重载
- 影响文件: `src/main/java/com/cncode/skill/SkillCatalog.java`
- 依赖任务: T1, T2
- 完成标准: `loadCatalog(workDir)` 按 builtin → 用户 → 项目顺序加载，后者覆盖前者；`loadTier` 容错；`getFull` 触发 phase-2 重读 body，读失败保留旧缓存；`reload(workDir)` 整体刷新。
- 实际产出: `SkillCatalog.java:107-123`（loadCatalog）、`SkillCatalog.java:125-132`（reload）、`SkillCatalog.java:138-158`（loadFromDirectory + loadTier）、`SkillCatalog.java:66-89`（getFull）

## T4: 定义 SkillHost / SkillForkHost 接口
- 影响文件: `src/main/java/com/cncode/skill/SkillHost.java`, `src/main/java/com/cncode/skill/SkillForkHost.java`
- 依赖任务: 无
- 完成标准: `SkillHost.activateSkill(name, body) / setToolFilter(Predicate<String>) / toolRegistry()`；`SkillForkHost extends SkillHost` 增加 `runSubAgent(body, seed, allowedTools, model) / snapshotParentMessages()`。
- 实际产出: `SkillHost.java:12-19`、`SkillForkHost.java:12-17`

## T5: 实现 SkillExecutor（inline / fork 双模式）
- 影响文件: `src/main/java/com/cncode/skill/SkillExecutor.java`
- 依赖任务: T1, T4
- 完成标准: `executeInline(skill, args, host)` 校验工具白名单 + 渲染 prompt + `activateSkill` + `setToolFilter`；`executeFork(skill, args, host)` 渲染 prompt + `buildForkSeed` + `runSubAgent`；`substituteArguments` 处理 `$ARGUMENTS` 占位符与缺占位符追加 `## User Request`；`buildForkSeed` 支持 `none / recent (≤5) / full`。
- 实际产出: `SkillExecutor.java:25-37`（executeInline）、`SkillExecutor.java:43-48`（executeFork）、`SkillExecutor.java:50-58`（substituteArguments）、`SkillExecutor.java:60-74`（buildForkSeed）、`SkillExecutor.java:76-88`（assertAllowedToolsExist）

## T6: buildActiveContext 系统提示注入助手
- 影响文件: `src/main/java/com/cncode/skill/SkillCatalog.java`
- 依赖任务: T1
- 完成标准: `buildActiveContext(Set<String> activeSkillNames)` 在系统提示里拼 `## Active Skills` 段 + 每个技能的 `### name` + body；空集合返回空串。
- 实际产出: `SkillCatalog.java:166-180`

## T7: 接入主流程 —— TUI 加载技能 / 注册为命令
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T1, T3
- 完成标准: provider 就绪后构造 `SkillCatalog` + `loadFromDirectory(<workDir>/.cncode/skills)`；`wireSkillsToAgent` 遍历 `list()` 调 `registerSkillCommand`；`registerSkillCommand` 跳过已存在命令，注册 PROMPT 类型 `Command`，description 以 `[skill]` 结尾，handler 从 catalog 取 `promptBody`。
- 实际产出: `CNCodeModel.java:102`（字段）、`CNCodeModel.java:494-500`（加载）、`CNCodeModel.java:511-516`（wireSkillsToAgent）、`CNCodeModel.java:518-533`（registerSkillCommand）

## T8: 接入主流程 —— PROMPT 分发的 skill 分支
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T7
- 完成标准: `executeCommand` 命中 PROMPT 类型时判断 description 是否以 `[skill]` 结尾；是则把 promptBody 当 user message 推入 conversation、附加 args、起 agent.run，并在 UI 上 println `skill(<name>) Successfully loaded skill`；`/skills` 命令列出当前 catalog。
- 实际产出: `CNCodeModel.java:928-967`（PROMPT 分支）、`CommandRegistry.java:255-265`（/skills handler）、`CNCodeModel.java:984-986`（skillList supplier）

## T9: 端到端验证
- 影响文件: 无
- 依赖任务: T7, T8
- 完成标准: `./gradlew build` 通过；在 `.cncode/skills/demo/SKILL.md` 放最小 frontmatter（name: demo, description: demo skill）+ body，启动 CN Code 后 `/skills` 列出 `demo`；输入 `/demo hello` 触发 PROMPT 分发，UI 显示 `skill(demo) Successfully loaded skill`，Agent 收到 promptBody + `hello` 作为新对话起点；`origin/java` 仓库已自带 `.cncode/skills/skill-creator/SKILL.md` 可作真实样本。
- 实际产出: `./gradlew build` 全绿、`CNCodeModel.java:494-500` 启动加载、`CNCodeModel.java:961-965` UI 提示

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9


# ch11: Skills 系统 Checklist

## 1. 实现完整性

- [ ] `SkillCatalog.SkillMeta` record 在 `src/main/java/com/cncode/skill/SkillCatalog.java:24-33` 含 `name / description / whenToUse / tags / allowedTools / mode / model / forkContext` 八个字段
- [ ] `SkillCatalog.Skill` record 在 `SkillCatalog.java:35-39` 含 `meta / promptBody / sourceDir / bodyLoaded`，提供 `withBody` 副本构造
- [ ] `SkillCatalog` 状态在 `SkillCatalog.java:43-45`：`skills / sources` 全部 `LinkedHashMap` 保序
- [ ] `register / get / getFull / list / source / reload / loadFromDirectory` 在 `SkillCatalog.java:49-158` 实现
- [ ] `getFull` 在 `SkillCatalog.java:71-89` 触发 phase-2 热重载，sourceDir 为 null 直接返回缓存，读失败 `IOException ignored` 后保留旧缓存
- [ ] `loadCatalog(workDir)` 在 `SkillCatalog.java:107-123` 按 tier1 builtin（占位）→ tier2 `~/.cncode/skills/` → tier3 `<workDir>/.cncode/skills/` 顺序加载
- [ ] `loadTier` 在 `SkillCatalog.java:142-158` 容错：目录不存在 / list 抛 IOException 都静默跳过
- [ ] `loadSkill(dir)` 在 `SkillCatalog.java:184-199` 优先 `skill.yaml + prompt.md`，否则 `SKILL.md`，都不存在返回 null
- [ ] `parseSkillMD` 在 `SkillCatalog.java:221-262` 处理可选 YAML frontmatter；YAML 解析失败降级为「无 frontmatter」；缺描述时从 body 第一行非标题行回退
- [ ] `metaFromMap` 在 `SkillCatalog.java:264-313`：name 缺省取目录名小写+空格换 `-`；mode 缺省 `inline` 并兼容 `context: fork`；`fork_context` 缺省 `none`
- [ ] `buildActiveContext(activeSkillNames)` 在 `SkillCatalog.java:166-180` 拼 `## Active Skills` 段，空集合返回 ""
- [ ] `SkillHost` 接口在 `src/main/java/com/cncode/skill/SkillHost.java:12-19` 提供 `activateSkill / setToolFilter / toolRegistry`
- [ ] `SkillForkHost extends SkillHost` 在 `src/main/java/com/cncode/skill/SkillForkHost.java:12-17` 追加 `runSubAgent / snapshotParentMessages`
- [ ] `SkillExecutor.executeInline` 在 `src/main/java/com/cncode/skill/SkillExecutor.java:25-37` 顺序：`assertAllowedToolsExist` → `substituteArguments` → `activateSkill` → 按 `allowed_tools` 调 `setToolFilter`
- [ ] `SkillExecutor.executeFork` 在 `SkillExecutor.java:43-48` 顺序：校验 → 渲染 → `buildForkSeed` → `runSubAgent`
- [ ] `substituteArguments` 在 `SkillExecutor.java:50-58`：args 空白原样返回；含 `$ARGUMENTS` 占位符替换；否则追加 `## User Request` 段
- [ ] `buildForkSeed` 在 `SkillExecutor.java:60-74`：`full` 全量、`recent` 取尾 5 条（`FORK_RECENT_COUNT = 5`）、其他（含 `none`）返回 `List.of()`
- [ ] `assertAllowedToolsExist` 在 `SkillExecutor.java:76-88` 工具未注册时抛 `IllegalStateException`
- [ ] 边界处理: 空目录、目录不存在、坏 yaml、`allowed_tools` 为空都不抛异常

## 2. 接入完整性

- [ ] `grep -rn "new SkillCatalog" --include="*.java" /Users/codemelo/cncode/src` 命中 `CNCodeModel.java:494` 的非测试调用
- [ ] `grep -rn "skillCatalog.loadFromDirectory" --include="*.java" /Users/codemelo/cncode/src` 命中 `CNCodeModel.java:497`
- [ ] `grep -rn "wireSkillsToAgent" --include="*.java" /Users/codemelo/cncode/src` 命中 `CNCodeModel.java:500` / `CNCodeModel.java:511`
- [ ] 字段 `skillCatalog` 在 `CNCodeModel.java:102`；provider 就绪后初始化 `CNCodeModel.java:494-498`
- [ ] `registerSkillCommand(name)` 在 `CNCodeModel.java:518-533`：跳过已存在命令、注册 PROMPT 类型 `Command`、description 后缀 `[skill]`、handler 从 catalog 取 promptBody
- [ ] PROMPT 分发的 skill 分支在 `CNCodeModel.java:928-967`：`isSkill = cmd.description().endsWith("[skill]")`，命中后在 UI 上 println `skill(<name>) Successfully loaded skill`
- [ ] `/skills` 命令 handler 在 `src/main/java/com/cncode/command/CommandRegistry.java:255-265` 列出 `skillList` supplier 返回的技能名
- [ ] `skillList` supplier 在 `CNCodeModel.java:984-986`：`skillCatalog != null` 时返回 `list().stream().map(s -> s.name()).toList()`
- [ ] 入口路径：用户输入 `/<skill-name>` → `executeCommand`（CNCodeModel）→ PROMPT 分支 → `cmdRegistry.execute` 返回 promptBody → `conversation.addUserMessage` → `agent.run`

## 3. 编译与测试

- [ ] `cd /Users/codemelo/cncode && ./gradlew build` 通过
- [ ] `cd /Users/codemelo/cncode && ./gradlew compileJava` 无警告
- [ ] `com.cncode.skill` 包不 import `com.cncode.agent` / `com.cncode.tui`，仅通过 `SkillHost` / `SkillForkHost` 接口与外界交互

## 4. 端到端验证

- [ ] 启动 CN Code 后输入 `/skills`，若 `.cncode/skills/` 下无技能则提示 `No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md`（`CommandRegistry.java:260`）
- [ ] 在 `.cncode/skills/skill-creator/SKILL.md` 现成样本下，启动后 `/skills` 列出 `skill-creator`
- [ ] 输入 `/skill-creator <args>` 触发 PROMPT 分支，UI 紧接出现 `skill(skill-creator) Successfully loaded skill`（`CNCodeModel.java:961-965`）
- [ ] Agent 新一轮 conversation 中可见两条 user message：第一条是 promptBody，第二条是 `<args>`（`CNCodeModel.java:937-942`）
- [ ] 修改 `.cncode/skills/skill-creator/SKILL.md` 的 body 后，下次执行该技能时通过 `getFull` 热重载到新内容（`SkillCatalog.java:71-89`）
- [ ] 留存证据：未提供截图（手动 TUI 验证不在课程验收流程要求范围内）

## 5. 文档

- [ ] `docs/java/ch11/spec.md` 存在
- [ ] `docs/java/ch11/tasks.md` 存在
- [ ] `docs/java/ch11/checklist.md` 存在
- [ ] Java 实现位于 `origin/java` 分支，包路径 `com.cncode.skill` / `com.cncode.command`






# ch12: Hook 系统 Spec

## 1. 背景

Agent 主流程在工具调用前后、session 起止、turn 起止等关键节点都有「副作用钩子」的需求：工具调用前阻断危险命令、调用后推日志到外部系统、session 起来时注入额外提示词。把这些写死在 agent 循环里既不优雅又难配置。Hook 系统把这层做成可声明（yaml）+ 简单条件匹配 + 两种动作类型的引擎，并通过 `reject` 字段让 `pre_tool_use` 钩子能阻断工具调用。Java 版以「最小可用」为目标，仅覆盖 command 和 prompt 两种动作，复杂语义（async / once / on_error / http）留到后续章节增量补齐。

## 2. 目标

交付 `com.cncode.hook.HookEngine`，从 `config.yaml` 加载 hook 列表，按事件名提供两种入口：普通事件用 `runHooks(ctx)` 跑全部命中钩子；`pre_tool_use` 用 `runPreToolHooks(toolName, args)` 返回 `PreToolResult` 允许阻断工具调用。Condition 支持 `==` 等值 和 `=~` 正则两种 leaf 操作符，变量覆盖 tool / event / file_path / message / args.<key>。CNCodeModel 在 TUI 初始化阶段从 `List<HookConfig>` 装配 Engine，session_start / turn_start / turn_end 由 `fireHook` 在生命周期节点触发，工具级 pre / post hook 由 `StreamingExecutor.executeSingle` 调用。

## 3. 功能需求

- F1: 提供 9 个事件枚举值（SESSION_START / SESSION_END / TURN_START / TURN_END / PRE_SEND / POST_RECEIVE / PRE_TOOL_USE / POST_TOOL_USE / SHUTDOWN），覆盖会话与工具生命周期。
- F2: 提供 3 个动作枚举值（COMMAND / SCRIPT / PROMPT），其中 SCRIPT 仅占位、不实际执行（落到 default 分支返回 unknown action type）。HTTP / agent 类型不在本章范围（见 Out of Scope）。
- F3: Condition DSL（极简版）:
 - leaf 操作符：`==`（等值）、`=~`（正则匹配）
 - 不支持复合（`&&`/`||`）、不支持反向（`!`）
 - 未识别操作符时按「真」处理（与 Go 版兼容，不报错）
 - 变量：tool、event、file_path、message、`args.<key>`
- F4: `runHooks(HookContext ctx)` 按事件名过滤、按 condition 决定是否触发；同步执行全部命中钩子，结果同时写入 `notifications` 队列。
- F5: `runPreToolHooks(String toolName, Map<String, Object> args)` 专门跑 PRE_TOOL_USE 事件：构造 ctx → 按 condition 过滤 → 命中 reject 钩子时执行 action 并立即返回 `PreToolResult(true, output)`；无 reject 命中时返回 `PreToolResult(false, "")`。
- F6: 动作执行器:
 - COMMAND：`ProcessBuilder("bash", "-c", h.action().command())` 启子进程；环境变量注入 `CNCODE_EVENT` / `CNCODE_TOOL`；stdout + stderr 同步读取并合并；`waitFor()` 退出码 0 视作 success
 - PROMPT：直接把 `action.message()` 当 output 返回，success = true
 - SCRIPT 及未知 type：返回 `HookResult(id, "Unknown action type: ...", false, false)`
- F7: 数据结构使用 Java record:
 - `Action(ActionType type, String command, String message)`
 - `Hook(String id, EventName event, String condition, Action action, boolean reject)`
 - `HookContext(EventName event, String toolName, Map<String,Object> toolArgs, String filePath, String message, String error)`
 - `HookResult(String hookId, String output, boolean success, boolean reject)`
 - `PreToolResult(boolean rejected, String message)`
- F8: 提供 `loadHooks(List<Hook>)` 替换内部 hooks 列表、`addHook(Hook)` 增量追加；`drainNotifications()` 取走积累的执行结果并清空队列（当前 TUI 未消费，留作后续接入）。
- F9: 配置数据类 `com.cncode.config.HookConfig`：字段 id / event / condition / type / command / message / reject 用经典 POJO + getter / setter 形式，便于 SnakeYAML 反序列化。
- F10: 入口透传链路：`config.yaml.hooks` → `AppConfig.hooks` → `CN Code.main` 把 `cfg.getHooks()` 传给 `CNCodeModel` 构造函数 → `CNCodeModel` 构造期把 `List<HookConfig>` 翻译成 `List<HookEngine.Hook>` 并 `loadHooks`，agent 初始化路径上调 `agent.setHookEngine(hookEngine)`。

## 4. 非功能需求

- N1: hook 执行不能抛出异常打断 Agent 主流程：command 子进程 `IOException / InterruptedException` 必须被捕获，返回 `success=false` 的 HookResult；condition 解析失败（如正则非法）按「不命中」处理。
- N2: `runCommand` 中断处理：catch `InterruptedException` 时必须调 `Thread.currentThread().interrupt()` 保留中断状态，避免上层虚拟线程丢失取消信号。
- N3: stdout / stderr 必须用 `readAllBytes()` 一次性读完再 `waitFor()`，避免子进程因 stdout 缓冲区满而死锁。
- N4: condition 字符串末尾的引号（`"`、`'`、`/`）必须 strip 后再比较，让 yaml 里写 `tool == "Bash"` 或 `event =~ /session.*/` 都能匹配。
- N5: Engine 状态目前不要求并发安全：hooks 列表只在 TUI 构造期写入、运行期只读；notifications 当前无消费方，并发竞态留待后续接入消费者时再加锁。

## 5. 设计概要

- 核心数据结构:
 - `HookEngine.EventName`：9 个 enum 值 + `value()` 返回 snake_case 字符串
 - `HookEngine.ActionType`：3 个 enum 值（command / script / prompt）
 - 5 个 record（Action / Hook / HookContext / HookResult / PreToolResult）封装数据流
 - `private final List<Hook> hooks`：注册的钩子列表
 - `private final List<HookResult> notifications`：执行结果累计，留给 `drainNotifications` 取
- 主流程:
 1. main 启动 → ConfigLoader 读 yaml → `AppConfig.hooks` 拿到 `List<HookConfig>`
 2. `CN Code.main` 把 `cfg.getHooks()` 透传给 `new CNCodeModel(providers, mcpServers, hooks)`
 3. `CNCodeModel` 构造函数里 `new HookEngine()` + 把 `HookConfig` 翻译成 `HookEngine.Hook` + `loadHooks`
 4. provider 就绪 → `agent.setHookEngine(hookEngine)` + `fireHook(SESSION_START, null, null)`
 5. 用户每次发消息 → `sendUserMessage` / 命令分支调 `fireHook(TURN_START, ...)`
 6. Agent loop 工具调用：`StreamingExecutor.executeSingle` 先 `hookEngine.runPreToolHooks(toolName, args)` → 被阻断时把 `"Rejected by hook: <msg>"` 当 ToolResult 返回；通过后正常执行工具 → 结束调 `hookEngine.runHooks(post_tool_use ctx)`
 7. agent loop 结束 → `LoopComplete` 事件触发 `fireHook(TURN_END, null, null)`
- 调用链（模块层级）:
 - 启动: `CN Code.main` → `CNCodeModel.<init>` → `HookEngine` 初始化 → `loadHooks` 挂到 `CNCodeModel.hookEngine` 字段 → `agent.setHookEngine` 透传到 `Agent.hookEngine`
 - 触发: `Agent.run` → `agentLoop` → `new StreamingExecutor(registry, checker, hookEngine, queue)` → `executeAll` → `executeSingle` → `runPreToolHooks` → `tool.execute` → `runHooks(post_tool_use)`
- 与其他模块的交互:
 - 上行依赖：`com.cncode.agent`（`Agent` 持引用，`StreamingExecutor` 调用 pre / post 入口）、`com.cncode.tui.CNCodeModel`（生命周期 + 配置装配）、`com.cncode.config`（POJO 反序列化）
 - 下行：无（hook 包仅依赖 JDK 标准库）

## 6. Out of Scope

- `agent` 动作类型：依赖 SubAgent 系统，本章不实现，留到 ch13 之后再补
- `http` 动作类型：当前 Java 版没有 HTTP 调用栈也没有响应体大小约束，等业务需要时再加；ActionType 枚举先不引入 HTTP 占位
- `script` 动作类型：虽然枚举里有 SCRIPT，但 `executeAction` 落到 default 分支返回 unknown action type；本章不补 script 执行路径，等场景需要时再补
- `once` / `async` / `on_error` 三种执行控制：当前所有钩子同步执行、每次都触发、出错就当失败处理；不补复杂的 fire-once / 异步 goroutine / 失败回滚语义
- Condition DSL 的 `!=` 反向、`~=` glob、`&&` / `||` 复合表达式：Java 版只实现 `==` 和 `=~` 两种 leaf；多条件需求由用户拆成多个独立 hook 来表达
- 加载期 `Validate`：当前 `loadHooks` 不做合法性校验；非法的 ActionType / EventName 字符串走 `parseEventName` / `parseActionType` 的 default 分支落到 SESSION_START / COMMAND，安静兜底
- Hook 命令的超时：`runCommand` 当前用同步 `waitFor()` 等到底，不带 timeout；长命令需要超时控制时再补 `waitFor(long, TimeUnit)` 或 `destroyForcibly()` 路径
- `drainNotifications` 的消费方：当前 TUI 没有消费 `notifications` 队列，hook 输出不会进入 system reminder；等通知中心模块就绪时再接入
- 缺失事件触发：`SESSION_END` / `PRE_SEND` / `POST_RECEIVE` / `SHUTDOWN` 当前没有 emit 点，等业务场景出现再在 TUI / Agent loop / 进程信号处理器里补 fireHook
- Hook 配置的热更新：必须重启或重新选 provider 才生效

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


````markdown
# ch12: Hook 系统 Tasks

## T1: 定义事件 / 动作枚举与数据 record

- 影响文件: `src/main/java/com/cncode/hook/HookEngine.java`
- 依赖任务: 无
- 完成标准: 9 个 `EventName` 枚举（带 `value()` 返回 snake_case 字符串）+ 3 个 `ActionType` 枚举（command / script / prompt）；5 个 record（Action / Hook / HookContext / HookResult / PreToolResult）齐全且字段对齐 Go 版语义。
- 实际产出: `HookEngine.java:12-55`

## T2: Condition DSL —— `==` 与 `=~` leaf 操作符

- 影响文件: `src/main/java/com/cncode/hook/HookEngine.java`
- 依赖任务: T1
- 完成标准: `evaluateCondition` 支持 `==` 等值匹配和 `=~` 正则匹配；变量解析覆盖 tool / event / file_path / message / `args.<key>`；`stripQuotes` 自动剥离 `"..."` / `'...'` / `/.../` 三种包裹；未识别操作符返回 true（与 Go 版兼容兜底）；正则编译失败时返回 false。
- 实际产出: `HookEngine.java:121-177`（`evaluateCondition` / `resolveVar` / `stripQuotes`）

## T3: Engine 核心 —— `loadHooks` / `addHook` / `runHooks`

- 影响文件: `src/main/java/com/cncode/hook/HookEngine.java`
- 依赖任务: T1, T2
- 完成标准: `loadHooks(List<Hook>)` 清空旧列表再追加；`addHook(Hook)` 增量追加；`runHooks(HookContext)` 按事件名过滤 + condition 过滤 + 调 `executeAction` + 把结果写入 `notifications` 队列；`drainNotifications()` 取一份快照并清空。
- 实际产出: `HookEngine.java:64-90`（`addHook` / `loadHooks` / `runHooks`）、`HookEngine.java:113-117`（`drainNotifications`）

## T4: Pre-tool 阻断专用入口 `runPreToolHooks`

- 影响文件: `src/main/java/com/cncode/hook/HookEngine.java`
- 依赖任务: T3
- 完成标准: `runPreToolHooks(String toolName, Map<String,Object> args)` 构造 PRE_TOOL_USE ctx → 按事件 / condition 过滤 → 命中且 `h.reject() == true` 时执行 action 并立即返回 `PreToolResult(true, result.output())`；无 reject 命中时返回 `PreToolResult(false, "")`。
- 实际产出: `HookEngine.java:92-109`

## T5: 两种动作执行器（command / prompt）

- 影响文件: `src/main/java/com/cncode/hook/HookEngine.java`
- 依赖任务: T3
- 完成标准:
 - `executeAction` 按 ActionType 分发：COMMAND 走 `executeCommand`；PROMPT 直接把 `action.message()` 当 output 返回 `HookResult(id, message, true, reject)`；其余（含 SCRIPT）落 default 分支返回 `HookResult(id, "Unknown action type: ...", false, false)`
 - `executeCommand`：`ProcessBuilder("bash", "-c", command)` 启子进程；环境变量注入 `CNCODE_EVENT` 和 `CNCODE_TOOL`；同步读 stdout / stderr 后再 `waitFor()`；stderr 非空时拼到 stdout（两者均非空用换行连接）；退出码 0 视作 success；`IOException` / `InterruptedException` 捕获后返回 `success=false` 的 HookResult，且 `InterruptedException` 分支必须 `Thread.currentThread().interrupt()` 保留中断状态
- 实际产出: `HookEngine.java:181-214`（`executeAction` / `executeCommand`）

## T6: 配置 POJO `HookConfig` 与 `AppConfig.hooks` 绑定

- 影响文件: `src/main/java/com/cncode/config/HookConfig.java`、`src/main/java/com/cncode/config/AppConfig.java`
- 依赖任务: T1
- 完成标准:
 - 新建 `HookConfig` POJO，字段 id / event / condition / type / command / message / reject，全部 getter / setter
 - `AppConfig` 新增 `private List<HookConfig> hooks` + getter / setter，让 SnakeYAML 能反序列化 `hooks: [...]` 列表
- 实际产出: `HookConfig.java:1-33`、`AppConfig.java:10`、`AppConfig.java:21-22`

## T7: 入口透传 —— `CN Code.main` 把 hook 列表传给 TUI

- 影响文件: `src/main/java/com/cncode/CNCode.java`
- 依赖任务: T6
- 完成标准: `CN Code.main` 加载完 `AppConfig` 后，把 `config.getHooks() != null ? config.getHooks() : List.of()` 作为第三个参数传给 `new CNCodeModel(...)`。
- 实际产出: `CNCode.java:35-39`

## T8: TUI 装配 —— `CNCodeModel` 构造 Engine + 翻译 HookConfig

- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T1, T6, T7
- 完成标准:
 - `CNCodeModel` 构造函数新增 `List<HookConfig> hookConfigs` 形参，存到字段
 - 构造期 `new HookEngine()`，若 hookConfigs 非空则把每个 HookConfig 翻译成 `HookEngine.Hook` 后 `loadHooks`
 - `parseEventName(String)` / `parseActionType(String)` 静态方法把 yaml 字符串映射到枚举，未知字符串落 default 分支兜底
- 实际产出: `CNCodeModel.java:66`、`CNCodeModel.java:174-205`（构造）、`CNCodeModel.java:208-232`（两个 parse 方法）

## T9: Agent 接入 —— `Agent.hookEngine` 字段 + `StreamingExecutor` 调用

- 影响文件: `src/main/java/com/cncode/agent/Agent.java`、`src/main/java/com/cncode/agent/StreamingExecutor.java`、`src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T3, T4, T5, T8
- 完成标准:
 - `Agent` 新增 `private HookEngine hookEngine` 字段 + `setHookEngine` / `getHookEngine` 访问器
 - `Agent.agentLoop` 在每轮工具调用前构造 `new StreamingExecutor(registry, checker, hookEngine, queue)`
 - `StreamingExecutor.executeSingle` 在 tool.execute 之前调 `hookEngine.runPreToolHooks(call.toolName(), call.args())`，rejected 时立即返回 `"Rejected by hook: <msg>"` 当 ToolResult
 - `StreamingExecutor.executeSingle` 在 tool.execute 之后构造 POST_TOOL_USE ctx 调 `hookEngine.runHooks(ctx)`
 - `CNCodeModel` 在 provider 就绪路径调 `agent.setHookEngine(hookEngine)` 并 `fireHook(SESSION_START, null, null)`
- 实际产出: `Agent.java:29`（字段）、`Agent.java:43`/`Agent.java:48`（访问器）、`Agent.java:249`（构造 executor）、`StreamingExecutor.java:27`/`StreamingExecutor.java:33-39`（字段 + 构造）、`StreamingExecutor.java:82-89`（pre）、`StreamingExecutor.java:142-146`（post）、`CNCodeModel.java:502-503`（setHookEngine + SESSION_START）

## T10: 生命周期事件触发 —— `fireHook` 在 turn_start / turn_end 调用

- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`
- 依赖任务: T9
- 完成标准:
 - 新增 `private void fireHook(EventName event, String toolName, Map<String,Object> args)` 助手方法，hookEngine 为 null 时直接 return；非 null 时构造 ctx 调 `hookEngine.runHooks(ctx)`
 - `TURN_START`：在用户消息提交后、agent 启动前调用（slash command 分支和普通消息分支两处）
 - `TURN_END`：在 `LoopComplete` 事件处理分支调用
- 实际产出: `CNCodeModel.java:949`（slash command 分支）、`CNCodeModel.java:1025`（sendUserMessage 分支）、`CNCodeModel.java:1104`（LoopComplete 分支）、`CNCodeModel.java:1148-1152`（fireHook 实现）

## T11: 端到端验证

- 影响文件: 无
- 依赖任务: T1-T10
- 完成标准: 在项目根目录 `config.yaml` 中配置一条 pre_tool_use reject hook：
 ```yaml
 hooks:
   - id: block-rm
     event: pre_tool_use
     condition: 'tool == Bash'
     type: prompt
     message: "blocked"
     reject: true

 ```

启动 TUI 让 LLM 调用 Bash 工具，看到工具结果是 `Rejected by hook: blocked`，且 ChatMessage 的 toolBlocks 把 isError 标为 true。

- 实际产出: 由人工或集成测试覆盖；手工验证步骤见 checklist §4。

## 进度

- [ ] T1

- [ ] T2

- [ ] T3

- [ ] T4

- [ ] T5

- [ ] T6

- [ ] T7

- [ ] T8

- [ ] T9

- [ ] T10

- [ ] T11

````

# ch12: Hook 系统 Checklist

## 1. 实现完整性

- [ ] 9 个 `EventName` 枚举在 `src/main/java/com/cncode/hook/HookEngine.java:12-28`：SESSION_START / SESSION_END / TURN_START / TURN_END / PRE_SEND / POST_RECEIVE / PRE_TOOL_USE / POST_TOOL_USE / SHUTDOWN，每个枚举值 `value()` 返回对应 snake_case 字符串
- [ ] 3 个 `ActionType` 枚举在 `HookEngine.java:32-42`：COMMAND / SCRIPT / PROMPT
- [ ] 5 个 record 在 `HookEngine.java:46-55`：`Action / Hook / HookContext / HookResult / PreToolResult` 字段对齐 spec §3.F7
- [ ] Engine 私有字段 `private final List<Hook> hooks` 与 `private final List<HookResult> notifications` 在 `HookEngine.java:59-60`
- [ ] `addHook(Hook)` 和 `loadHooks(List<Hook>)` 在 `HookEngine.java:64-71`：loadHooks 必须 `hooks.clear()` 后再 `addAll`
- [ ] `runHooks(HookContext)` 在 `HookEngine.java:75-90`：按事件名过滤 → condition 过滤 → 调 `executeAction` → 把 HookResult 追加到 `notifications` 队列
- [ ] `runPreToolHooks(String, Map)` 在 `HookEngine.java:92-109`：构造 PRE_TOOL_USE ctx → 命中 reject 钩子时执行 action 并立即返回 `PreToolResult(true, output)`；无命中返回 `PreToolResult(false, "")`
- [ ] `drainNotifications()` 在 `HookEngine.java:113-117`：返回不可变快照 + 清空内部队列
- [ ] Condition DSL 支持 `==` 与 `=~` 两种 leaf：实现在 `HookEngine.java:121-147`；变量解析在 `HookEngine.java:149-164`；引号剥离在 `HookEngine.java:166-177`
- [ ] 未识别操作符走 `return true` 兜底（`HookEngine.java:146`）；正则编译失败 `PatternSyntaxException` 走 `return false`（`HookEngine.java:140-142`）
- [ ] `executeAction` 在 `HookEngine.java:181-188` 按 ActionType 分发：COMMAND 走 `executeCommand`、PROMPT 走 `new HookResult(id, message, true, reject)`、SCRIPT / 未知走 `"Unknown action type: ..."` 失败结果
- [ ] `executeCommand` 在 `HookEngine.java:190-214`：`ProcessBuilder("bash", "-c", command)` 启子进程；env 注入 `CNCODE_EVENT` 和 `CNCODE_TOOL`；stdout / stderr 同步读完后 `waitFor()`；exit code 0 ↔ success；stderr 非空时拼到 stdout（两者均非空用 `\n` 分隔）
- [ ] `executeCommand` 异常分支必须捕获 `IOException | InterruptedException`，且 `InterruptedException` 分支调 `Thread.currentThread().interrupt()` 保留中断状态（`HookEngine.java:208-213`）
- [ ] `HookConfig` POJO 在 `src/main/java/com/cncode/config/HookConfig.java:1-33`：字段 id / event / condition / type / command / message / reject + 配套 getter / setter

## 2. 接入完整性

- [ ] `grep -rn "new HookEngine" --include="*.java" src/main/java` 命中 `CNCodeModel.java:196` 这条非测试构造点
- [ ] `grep -rn "runPreToolHooks\|runHooks(" --include="*.java" src/main/java | grep -v Test` 命中 `StreamingExecutor.java:83` 与 `StreamingExecutor.java:145` 两个 agent loop 触发点，以及 `CNCodeModel.java:1151` 一个生命周期触发点
- [ ] `grep -rn "setHookEngine\|getHookEngine" --include="*.java" src/main/java | grep -v Test` 至少命中 `CNCodeModel.java:502`（setHookEngine）和 `Agent.java:43`/`Agent.java:48`（访问器声明）
- [ ] Config 绑定：`AppConfig.java:10` 含 `private List<HookConfig> hooks` 字段，`AppConfig.java:21-22` 含 getter / setter
- [ ] 入口透传：`CNCode.java:35-39` 把 `config.getHooks() != null ? config.getHooks() : List.of()` 传给 `CNCodeModel` 第三个参数
- [ ] TUI 装配：`CNCodeModel.java:66`（字段）、`CNCodeModel.java:174-205`（构造函数翻译 HookConfig → HookEngine.Hook 并 loadHooks）
- [ ] `parseEventName / parseActionType` 在 `CNCodeModel.java:208-232`：未知 yaml 字符串落 default 分支兜底到 SESSION_START / COMMAND
- [ ] Agent 字段：`Agent.java:29` 含 `private HookEngine hookEngine`；构造 StreamingExecutor 处 `Agent.java:249` 把 hookEngine 透传
- [ ] StreamingExecutor 字段：`StreamingExecutor.java:27` 含 `private final HookEngine hookEngine`，构造函数 `StreamingExecutor.java:33-39` 接收
- [ ] Pre-tool 调用：`StreamingExecutor.java:82-89` 走 `if (hookEngine != null) { ... }`，rejected 时把 `"Rejected by hook: <msg>"` 当 ToolResult 返回并发出 `AgentEvent.ToolResultEvent`
- [ ] Post-tool 调用：`StreamingExecutor.java:142-146` 在 tool.execute 完成后构造 POST_TOOL_USE ctx 调 `hookEngine.runHooks(ctx)`
- [ ] 生命周期触发：`CNCodeModel.java:1148-1152` 实现 `fireHook` 助手，并在 `CNCodeModel.java:503`（SESSION_START）、`CNCodeModel.java:949` 与 `CNCodeModel.java:1025`（TURN_START）、`CNCodeModel.java:1104`（TURN_END）调用
- [ ] 入口路径：`config.yaml.hooks → AppConfig.hooks → CN Code.main → new CNCodeModel(..., hooks) → CNCodeModel.hookConfigs → new HookEngine + loadHooks → agent.setHookEngine → Agent.hookEngine → StreamingExecutor.executeSingle 调 runPreToolHooks / runHooks`
- [ ] 死代码记录 1：`HookEngine.ActionType.SCRIPT` 当前在 `executeAction` 落 default 分支（永远返回 "Unknown action type"），spec §6 已明示「不实现」；接入前可保留枚举占位、后续接入时单独删除或补 case 分支
- [ ] 死代码记录 2：`HookEngine.drainNotifications` 当前没有非测试消费方，`grep -rn "drainNotifications" --include="*.java" src/main/java | grep -v Test` 应返回 0 条；spec §6 已记录留作后续通知中心模块接入

## 3. 编译与测试

- [ ] `cd /Users/codemelo/cncode && ./gradlew build` 通过
- [ ] `cd /Users/codemelo/cncode && ./gradlew compileJava` 通过（hook 包被 agent / tui / config 引用）
- [ ] `cd /Users/codemelo/cncode && ./gradlew test` 通过；若新增 `HookEngineTest`，至少覆盖 condition 解析（== 与 =~）、runPreToolHooks 阻断、runCommand 注入环境变量三类用例
- [ ] `javac -Xlint:all` 或 Gradle build 输出中 `com.cncode.hook` 与 `StreamingExecutor` 无未检查警告

## 4. 端到端验证

- [ ] 在项目根目录 `config.yaml` 配置一条 pre_tool_use reject hook（参考 tasks.md T11 的 yaml）；启动 TUI 后让 LLM 调用 Bash 工具，看到工具结果文本是 `Rejected by hook: blocked`，且 `ChatMessage.ToolBlockInfo.isError == true`
- [ ] 在 `config.yaml` 配置一条 post_tool_use command hook，命令使用 `CNCODE_TOOL` 环境变量（如 `echo "tool=$CNCODE_TOOL" >> /tmp/cncode-hook.log`）；触发工具调用后查看日志文件包含正确的工具名
- [ ] 测试 condition 正则匹配：配置 `condition: 'tool =~ Bash|Read'`，验证 Bash 和 ReadFile 工具都触发 hook，其他工具不触发
- [ ] 测试 prompt 动作：配置 `type: prompt` + `message: "test message"`，触发后通过 `HookEngine.drainNotifications` 看到 `HookResult.output == "test message"`（需在 TUI 接入消费方或编写直接调 Engine 的单元测试）
- [ ] 测试 condition 引号剥离：`condition: 'tool == "Bash"'`、`condition: "event =~ /session.*/"`、`condition: "tool == 'Bash'"` 三种写法都能正确匹配

## 5. 文档

- [ ] `docs/java/ch12/spec.md` 存在
- [ ] `docs/java/ch12/tasks.md` 存在
- [ ] `docs/java/ch12/checklist.md` 存在
- [ ] commit message 包含章节号 `ch12` 与三件套关闭标记，建议形如 `docs(ch12): close spec/tasks/checklist for hooks system`






# ch13: SubAgent Spec（Java 版）

## 1. 背景

主 Agent 做大任务时会塞满上下文：研究、规划、写代码、跑测试都堆在一个对话里，单一窗口很快耗尽。这一章把"开一个上下文隔离的新 Agent 去做一件事"做成主 Agent 可以直接调用的工具，让主 Agent 学会分发工作，避免上下文爆炸，同时通过专门角色（plan / explore）和后台异步执行扩展并发能力。

## 2. 目标

提供 `Agent` 工具（`AgentTool implements Tool`），主 Agent 在对话里写一次工具调用即可：1) 按 `subagent_type` 启动一个定义式专家子 Agent（系统提示词、模型、工具白名单都按 Markdown 定义文件来），2) 不带 `subagent_type` 时直接 fork 当前对话上下文跑一个临时子 Agent，3) 带 `team_name` 时把这个 spawn 注册成长期团队成员（衔接 ch15）。后台任务的完成通过 `TaskNotification` 由父 Agent 在下一轮抽取注入。

## 3. 功能需求

- F1: `AgentTool` 实现 `com.cncode.tool.Tool` 接口，注册到主 Agent 的 `ToolRegistry`，被 LLM 当成普通工具调用；`shouldDefer()` 返回 `true`，只在 ToolSearch 选中时才把 schema 暴露给模型。
- F2: 三档内建 Agent 类型 `general-purpose` / `plan` / `explore`（`SubAgentSpec.GENERAL_PURPOSE / PLAN / EXPLORE` 静态实例），每档可定制工具黑名单（`disallowedTools`）、最大轮数（`maxTurns`）、模型（`model`）、系统提示词覆盖（`systemPromptOverride`）。
- F3: `AgentLoader.loadAll(projectRoot)` 按 builtin → `~/.cncode/agents/*.md`（用户级）→ `<projectRoot>/.cncode/agents/*.md`（项目级）顺序加载，同名后注册覆盖前者；Markdown frontmatter 解析为 `SubAgentSpec`。
- F4: 三种执行路径：sync（前台阻塞、`AgentTool.runSync` 流式回写 LLM）/ async（后台虚拟线程、立即返回 `task_N`）/ fork（fork 父对话上下文，强制后台）。
- F5: `SubAgentTaskManager` 跟踪后台子 Agent 生命周期（`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`），完成或失败时把 `TaskNotification` 入队，主 Agent 下一轮通过 `drainNotifications()` 取出并注入到 conversation。
- F6: 六层工具过滤（`ToolFilter.filterForAgent`）：MCP 豁免 → 全局禁（`ALWAYS_DISALLOWED`：`Agent` / `AskUserQuestion` 等 7 项防递归）→ custom agent 额外禁（`CUSTOM_AGENT_DISALLOWED`）→ async 白名单（`ASYNC_ALLOWED` 仅 15 项基础工具）→ definition 级黑名单 → definition 级白名单交集。
- F7: Fork 路径：构造完整 forked conversation（拷贝父消息，给悬挂的 `toolUses` 补 placeholder `ToolResultBlock("(tool execution interrupted by fork)")`），追加 fork boilerplate 系统约束 + 任务文本；fork-of-fork 通过扫描父对话内容中的 `<fork_boilerplate>` 标签拒绝。
- F8: 可选 worktree 隔离与 `WorktreeManager` 配合，子 Agent 在临时 git worktree 中跑；执行结束按 `WorktreeChanges.hasChanges(...)` 决定保留 / 移除。
- F9: 可选团队模式与 `TeamManager` 配合，走 `SpawnDispatcher.spawnTeammate` 注册长期团队成员（详见 ch15）。
- F10: in-process teammate 在 async 白名单层额外放行 `Agent` + `IN_PROCESS_TEAMMATE_ALLOWED`（`TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / CronCreate / CronDelete / CronList`）。
- F11: 子 Agent 后台执行通过 `Thread.startVirtualThread` 启动；`cancelTask(id)` 通过 `Thread.interrupt()` 取消。
- F12: 模型选择 `selectClient` 优先用调用级 `model` 参数，其次用 spec 的 `model`，都没设或为 `inherit` / 空字符串时复用父 client；`ModelResolver` 把 `haiku/sonnet/opus` 别名解析为具体 model ID。
- F13: 父对话引用 (`parentConversation`) 由 TUI 通过 `setParentConversation` 注入；缺失时 fork 路径报错。

## 4. 非功能需求

- N1: 子 Agent 不能再调 `Agent` 工具（防止无限递归 / 上下文爆炸），任意层级的子 Agent 都通过 `ALWAYS_DISALLOWED` 屏蔽。
- N2: 后台 Agent 通过 `Thread.interrupt()` 受控；`cancelTask` 状态置为 `CANCELLED` 并发出对应 `TaskNotification`。
- N3: `SubAgentTaskManager` 所有公共方法用 `synchronized` 守护（虚拟线程与主线程同时操作 `tasks` / `notifications`）。
- N4: fork 操作必须先在父对话所有消息内容里搜 `<fork_boilerplate>` 标签拒绝嵌套 fork。
- N5: Sync 路径要走子 Agent 的完整 `BlockingQueue<AgentEvent>` 事件流：`StreamText` 累积输出 / `ToolResultEvent` 发 progress / `ErrorEvent` 报错退出 / `LoopComplete` 结束并清理 worktree。
- N6: Fork 子 Agent 复用父池工具（直接传 `parentRegistry`）与对话内容（含 `ThinkingBlock`），通过 `conv.addAssistantFull(content, thinkingBlocks, toolUses)` 保形。
- N7: 工具集传递使用 `ToolRegistry.listTools()` 枚举 + `register(tool)` 复制，避免污染父 registry。
- N8: 子 Agent 定义 frontmatter 字段集合需在解析层完整保留；未来章节扩展字段必须在解析层先存得下，避免重复迁移。

## 5. 设计概要

- 核心类型:
 - `AgentTool`（`src/main/java/com/cncode/subagent/AgentTool.java`）：承载 `client` / `parentRegistry` / `protocol` / `modelResolver` / `agentSpecs` / `progressListener` / `taskManager` / `parentConversation` / `worktreeManager` / `teamManager` 等运行时依赖；`description()` 动态把可用 agent 类型拼进描述文案。
 - `SubAgentSpec`（record）：`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`；`PLAN_AGENT_SYSTEM_PROMPT` 为 plan 角色的硬编码系统提示。
 - `SubAgentTaskManager`：内部 `TaskEntry`（id / name / status / output / error / thread）状态机；`TaskNotification` record；`spawnSubAgent` 启动虚拟线程。
 - `SubAgentProgress`（record）：进度事件，含 `agentType / description / toolName / toolOutput / toolError / done / toolCount / totalTime`。
 - `ToolFilter`：四个 `Set<String>`（`ALWAYS_DISALLOWED` 7 项 / `CUSTOM_AGENT_DISALLOWED` 7 项 / `ASYNC_ALLOWED` 15 项 / `IN_PROCESS_TEAMMATE_ALLOWED` 8 项）实现六层过滤。
 - `AgentLoader`：`VALID_MODELS = {"", "inherit", "haiku", "sonnet", "opus"}`；`parseAgentFile` 用 SnakeYAML 解析 frontmatter。
- 主流程:
 - 同步：用户消息 → 主 Agent → LLM 输出 `Agent` 工具调用 → `AgentTool.execute(args)` → 解析 `subagent_type` → `resolveSpec` → `runSync` → `ToolFilter.filterForAgent` → 构造子 `Agent` → `subAgent.run(conv)` → 消费 `BlockingQueue<AgentEvent>` 直到 `LoopComplete` → 返回结果。
 - 异步：调 `taskManager.spawnSubAgent`，立即返回 `Agent "..." launched in background (task task_N).`；后台虚拟线程跑完写 `setCompleted` 或 `setFailed`，主 Agent 下一轮 `drainNotifications` 抽出 `TaskNotification` 注入对话。
 - Fork：扫父对话 → 拷贝消息（含 `ThinkingBlock` 与悬挂 `toolUses` 占位 `ToolResultBlock`）→ 追加 `FORK_BOILERPLATE + "\n\nYour task:\n" + prompt` → 始终调 `taskManager.spawnSubAgent` 走后台。
 - 团队成员：校验 team 存在、name 去重 → 过滤工具集 + 注入 `SendMessageTool` → 调 `SpawnDispatcher.spawnTeammate` 拿 backend hint → 立即返回。
- 调用链:
 - 主流程组装在主 Agent 启动时把 `AgentTool` 注册到 `ToolRegistry`，并通过 setter 注入 `taskManager` / `agentSpecs` / `parentConversation` / `progressListener` / `worktreeManager` / `teamManager` / `modelResolver`。
 - Agent loop（`com.cncode.agent.Agent.agentLoop`）每轮开头通过 `notificationFn` 抽取 `TaskNotification` 注入 `conv.addSystemReminder`。
- 与其他模块的交互:
 - 依赖 `com.cncode.agent`（创建子 Agent）、`com.cncode.conversation`（forked ConversationManager）、`com.cncode.tool`（注册中心 + 过滤）、`com.cncode.llm`（`LlmClient` / `ModelResolver`）、`com.cncode.worktree`（隔离）、`com.cncode.teams`（团队成员）。
 - 被主 Agent 装配点（`Main` / TUI 层）调用。

## 6. Out of Scope

- 子 Agent 输出全在内存事件流里，不落盘 task 输出文件。
- 不实现 RemoteAgent / DreamTask / LocalWorkflow / MonitorMcp 这些 TaskType。
- 不实现 fork 路径的 worktree notice（仅同步 isolation 路径支持）。
- 不接入 plugin / flag / managed 加载源（只支持 builtin / user / project）。
- 不消费 `skills` / `hooks` / `mcpServers` / `memory` / `permissionMode` 等扩展字段——本章 frontmatter 解析层保留五个核心字段，扩展字段留给后续章节。
- 不实现 PermissionMode 的 bubble / auto 模式。
- 不实现 120s 自动超时切后台 / ESC 切后台 / 持久化后台恢复。
- 不实现 `isolation: remote` 远端运行后端。
- 不内置 Verification 等附加 Agent。
- 不在本章实现 Fork 模式的字节级 prompt cache 命中重构（thinking blocks 拷贝已具备，但调用级 `useExactTools / cloneRegistryForFork` 留作后续）。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch13: SubAgent Tasks（Java 版）

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 定义 `SubAgentSpec` record + 三档 builtin
- 影响文件: `src/main/java/com/cncode/subagent/SubAgentSpec.java`（record 头 @ 10-18；`PLAN_AGENT_SYSTEM_PROMPT` @ 20-54；`GENERAL_PURPOSE` @ 56-64；`PLAN` @ 66-75；`EXPLORE` @ 77-85）
- 依赖任务: 无
- 完成标准:
 - record 字段七项（`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`）齐全；
 - `PLAN.disallowedTools()` 含 `EditFile / WriteFile`，`maxTurns == 15`，使用 `PLAN_AGENT_SYSTEM_PROMPT` 作为 prompt override；
 - `EXPLORE.disallowedTools()` 含 `EditFile / WriteFile`，`maxTurns == 30`，`model == "haiku"`；
 - `GENERAL_PURPOSE.maxTurns == 200`，无 prompt override。
- [ ] 完成

## T2: 实现 `AgentLoader.parseAgentFile`（Markdown frontmatter 解析）
- 影响文件: `src/main/java/com/cncode/subagent/AgentLoader.java`（`VALID_MODELS` @ 27；`parseAgentFile` @ 95-150；`getString` @ 152-155；`getStringList` @ 157-170）
- 依赖任务: T1
- 完成标准:
 - 用 SnakeYAML 解析两个 `---` 之间的 frontmatter；
 - 缺 `name` / `description` 抛 `IllegalArgumentException`（含路径与字段名）；
 - `model` 非空时校验 ∈ `{"", "inherit", "haiku", "sonnet", "opus"}`，非法值抛错；
 - body 为空时 `systemPromptOverride == null`；
 - `tools` / `disallowedTools` 缺省返回 `List.of()`。
- [ ] 完成

## T3: 实现 `AgentLoader.loadAll`（builtin → user → project 三层优先级）
- 影响文件: `src/main/java/com/cncode/subagent/AgentLoader.java`（`agents` 字段 @ 29；`loadAll` @ 39-53；`listNames` @ 58-62；`loadBuiltins` @ 64-68；`loadDir` @ 70-89）
- 依赖任务: T2
- 完成标准:
 - 先 `loadBuiltins` 注入三档 builtin；
 - 再 `~/.cncode/agents/*.md`（user）；
 - 最后 `<projectRoot>/.cncode/agents/*.md`（project）；
 - 同名后注册覆盖前者（`LinkedHashMap` 保 put 覆盖语义）；
 - 目录不存在静默跳过；解析失败的文件静默跳过（catch 后不抛）。
- [ ] 完成

## T4: 实现 `ToolFilter` 六层过滤
- 影响文件: `src/main/java/com/cncode/subagent/ToolFilter.java`（`ALWAYS_DISALLOWED` @ 30-33；`CUSTOM_AGENT_DISALLOWED` @ 36-39；`ASYNC_ALLOWED` @ 42-46；`IN_PROCESS_TEAMMATE_ALLOWED` @ 49-52；`filterForAgent(source, spec)` @ 60-62；`filterForAgent(source, spec, isAsync, isCustom, isInProcessTeammate)` @ 77-133；`isMcpTool` @ 135-137）
- 依赖任务: 无（独立模块）
- 完成标准:
 - `ALWAYS_DISALLOWED` 含 7 项（`TaskOutput / ExitPlanMode / EnterPlanMode / Agent / AskUserQuestion / TaskStop / Workflow`）；
 - `ASYNC_ALLOWED` 含 15 项（详见 checklist 7.1）；
 - `mcp__` 前缀工具直接通过；
 - 异步模式下 in-process teammate 额外允许 `Agent` + `IN_PROCESS_TEAMMATE_ALLOWED` 8 项；
 - 自定义 spec 的 `disallowedTools` 与 `tools`（白名单交集）都生效；
 - `tools == ["*"]` 视为无白名单（即不过滤）。
- [ ] 完成

## T5: 实现 `SubAgentTaskManager` 状态机 + 通知队列
- 影响文件: `src/main/java/com/cncode/subagent/SubAgentTaskManager.java`（`TaskStatus` @ 19；`Task` @ 21；`TaskNotification` @ 23；`TaskEntry` @ 29-42；`createTask` @ 44-48；`setRunning` @ 50-56；`setCompleted` @ 58-65；`setFailed` @ 67-74；`cancelTask` @ 76-85；`drainNotifications` @ 87-91；`getTask` @ 93-97；`listTasks` @ 99-103）
- 依赖任务: 无
- 完成标准:
 - 状态机覆盖 `PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`；
 - `setCompleted` / `setFailed` / `cancelTask` 各自把 `TaskNotification` 入队；
 - `drainNotifications` 一次性取出并清空，返回不可变拷贝；
 - 所有公共方法 `synchronized`；
 - `nextId` 用 `AtomicInteger`，taskId 形如 `task_N`。
- [ ] 完成

## T6: 实现 `SubAgentTaskManager.spawnSubAgent`（后台虚拟线程）
- 影响文件: `src/main/java/com/cncode/subagent/SubAgentTaskManager.java`（`spawnSubAgent` @ 108-164；`truncate` @ 166-168）
- 依赖任务: T1, T4, T5
- 完成标准:
 - 调 `createTask` 拿 `task_N`；
 - `Thread.startVirtualThread` 启动后台线程；
 - 内部 `ToolFilter.filterForAgent(registry, spec)` 拿子 registry（注：本章 spawn 路径不带 async 标志，等价 sync 过滤）；
 - 启动 `subAgent.run(conv)` 拿 `BlockingQueue<AgentEvent>`；
 - 事件循环：`StreamText` 累积；`ErrorEvent` → `setFailed`；`LoopComplete` → `setCompleted`；`InterruptedException` → `setFailed("Interrupted")`；`poll(60s)` 超时 → `setFailed("Timeout")`；
 - 线程引用通过 `setRunning(taskId, thread)` 写回。
- [ ] 完成

## T7: 实现 `AgentTool` 框架 + `schema()` + `description()`
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（类头 @ 29-66；构造器 + setter @ 68-104；`name()` @ 108-111；`description()` @ 113-137；`category()` @ 139-142；`schema()` @ 144-196；`shouldDefer()` @ 198-201）
- 依赖任务: T1, T3
- 完成标准:
 - 实现 `Tool` 接口，`name() == "Agent"`；
 - `description()` 动态把 `agentSpecs` 里的 agent 列出来；缺省时 fallback 列出三档 builtin；
 - `schema()` 暴露 6 个属性：`description / prompt / subagent_type / model / run_in_background / isolation / team_name`；`subagent_type.enum` 由 `AgentLoader.listNames(agentSpecs)` 动态生成；
 - `required = ["description", "prompt"]`；
 - `shouldDefer() == true`；
 - `FORK_BOILERPLATE_TAG = "<fork_boilerplate>"`，`FORK_BOILERPLATE` text block 含五条规则。
- [ ] 完成

## T8: 实现 `AgentTool.execute` 五条分支
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`execute` @ 204-240；`resolveSpec` @ 415-425；`getStringArg` @ 522-525）
- 依赖任务: T6, T7
- 完成标准:
 - 缺 `description` / `prompt` 返回 `ToolResult.error("Error: description and prompt are required")`；
 - 分支顺序：`subagent_type` 空 → `runFork`；`teamName != null && teamManager != null` → `runAsTeammate`；`run_in_background == true` → `runAsync`；默认 → `runSync`；
 - `resolveSpec` 优先查 `agentSpecs`，回退到 switch 三档 builtin；
 - 未知 `subagent_type` 返回 `Error: unknown agent type '...'. Available: ...`。
- [ ] 完成

## T9: 实现 `runSync`（前台流式 + 可选 worktree）
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`runSync` @ 310-413；`selectClient` @ 489-501；`emitProgress` @ 503-516；`elapsedSeconds` @ 518-520）
- 依赖任务: T4, T8
- 完成标准:
 - `ToolFilter.filterForAgent(parentRegistry, spec)` 拿子 registry；
 - 子 Agent `maxIterations` 取 `spec.maxTurns()` 或 fallback 200；
 - 事件循环消费 `StreamText` 累积输出 / `ToolResultEvent` 发 progress / `ErrorEvent` 报错退出 / `LoopComplete` 结束；
 - `poll(60, SECONDS)` 超时返回 `Agent timed out waiting for events`；
 - `isolation == "worktree"` 且 `worktreeManager != null` 时创建临时分支，slug `agent-aXXXXXXX`（7 位 hex）；
 - 结束时 `WorktreeChanges.hasChanges` 决定保留 / 调用 `AgentWorktree.remove`；
 - 最终消息含 `Agent "%s" completed in %d.%03ds.\n\n%s%s`。
- [ ] 完成

## T10: 实现 `runFork`（fork 父对话）
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`runFork` @ 255-282；`buildForkedConversation` @ 284-308）
- 依赖任务: T6, T8
- 完成标准:
 - `parentConversation == null` → 报错 `Error: fork requires parent conversation context`；
 - `taskManager == null` → 报错 `Error: fork requires task manager for background execution`；
 - 扫父对话每条 `getContent().contains(FORK_BOILERPLATE_TAG)` → 报错 `Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.`；
 - `buildForkedConversation`：对带 `toolUses` 但无 `toolResults` 的 assistant 消息走 `addAssistantFull` + 追加占位 `ToolResultBlock("(tool execution interrupted by fork)")`；对带 `toolUses` 有 `toolResults` 的走 `addAssistantFull`；对纯 assistant 走 `addAssistantMessage`；对 user 走 `addUserMessage`；
 - 最后 `addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task)`；
 - fork 始终调 `taskManager.spawnSubAgent`，提示文案含 `Forked agent "%s" launched in background (task %s). Results will arrive via task-notification.`。
- [ ] 完成

## T11: 实现 `runAsync`（builtin spec → 后台）
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`runAsync` @ 244-253）
- 依赖任务: T6, T8
- 完成标准:
 - `taskManager == null` → 报错 `Background execution not available (no task manager configured)`；
 - 调 `selectClient(spec.model(), modelOverride)` 拿子 client；
 - 调 `taskManager.spawnSubAgent` 拿 `task_N`；
 - 返回 `Agent "%s" launched in background (task %s). You will be notified when it completes.`。
- [ ] 完成

## T12: 实现 `runAsTeammate`（团队成员路径，衔接 ch15）
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`runAsTeammate` @ 427-487）
- 依赖任务: T8（ch15 的 `SpawnDispatcher.spawnTeammate`）
- 完成标准:
 - 校验 `teamManager.getTeam(teamName) != null`，否则报错 `Error: team '%s' not found. Create it first with TeamCreate.`；
 - memberName 用 `description` 处理（小写 + `\\s+` 替换为 `-` + 截断 30 字符 + 同名递增 `-2 / -3 ...`）；
 - `ToolFilter.filterForAgent` 之后注入 `TeamTools.SendMessageTool(teamManager, memberName)`；
 - 可选 worktree 隔离（同 `runSync` 逻辑）；
 - 调 `SpawnDispatcher.spawnTeammate(SpawnConfig(...))` 拿 `spawnResult`；
 - 返回 `Teammate "%s" spawned in team "%s" (mode: %s). The teammate is now working on the assigned task.`。
- [ ] 完成

## T13: 接入主流程
- 影响文件: 主 Agent 装配点（`cmd/cncode/main.go` 对应的 Java 装配类，例如 `com.cncode.Main` 或 `TuiBootstrap`）
- 依赖任务: T1-T12
- 完成标准:
 1. 构造 `AgentTool(client, registry, protocol)` 后通过 setter 注入 `agentSpecs`（来自 `AgentLoader.loadAll(projectRoot)`）、`taskManager` (`new SubAgentTaskManager()`)、`progressListener`、`parentConversation`、`worktreeManager`、`teamManager`、`modelResolver`；
 2. `registry.register(agentTool)`；
 3. 主 Agent 的 `notificationFn` 绑定到一个把 `taskManager.drainNotifications()` 转成可读字符串列表的 supplier。
- [ ] 完成

## T14: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T13
- 完成标准:
 - `./gradlew build` 成功；
 - SubAgent 模块单测全通过（loader 解析正确 / 三档 builtin 字段断言 / 六层过滤分支覆盖 / TaskManager 状态机覆盖 / `runFork` 嵌套拒绝）；
 - 手动跑一次：主 Agent → 调 `Agent` 工具（`subagent_type=plan`）→ 看到 `Agent "..." completed in ...` 输出；
 - 手动跑一次：主 Agent → 调 `Agent` 工具（`run_in_background=true`）→ 看到 `task_N` 立即返回，下一轮收到完成通知。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13 / [ ] T14


# ch13: SubAgent Checklist（Java 版）

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 类 `AgentTool` 在 `src/main/java/com/cncode/subagent/AgentTool.java:29-526` 存在，字段含 `client / parentRegistry / protocol / modelResolver / agentSpecs / progressListener / taskManager / parentConversation / worktreeManager / teamManager`
- [ ] record `SubAgentSpec` 在 `src/main/java/com/cncode/subagent/SubAgentSpec.java:10-18` 存在，七个字段（`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`）齐全
- [ ] record `SubAgentProgress` 在 `src/main/java/com/cncode/subagent/SubAgentProgress.java:16-25` 存在，八个字段齐全
- [ ] 类 `SubAgentTaskManager` 在 `src/main/java/com/cncode/subagent/SubAgentTaskManager.java:17-169` 存在；含 `TaskStatus` enum（`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`）、`Task` record、`TaskNotification` record、`TaskEntry` 内部类
- [ ] 三档 builtin（`GENERAL_PURPOSE / PLAN / EXPLORE`）在 `SubAgentSpec.java:56-85` 注册，分别对应 `maxTurns = 200 / 15 / 30`
- [ ] `ToolFilter.filterForAgent` 在 `src/main/java/com/cncode/subagent/ToolFilter.java:77-133` 实现六层过滤
- [ ] `AgentLoader.parseAgentFile` 在 `src/main/java/com/cncode/subagent/AgentLoader.java:95-150` 校验 `name` / `description` 必填，`model` 取值白名单（`VALID_MODELS` @ 27）
- [ ] `AgentTool.runFork` 在 `agent_tool` 对应 `AgentTool.java:255-282` 嵌套 fork 检查（扫描 `<fork_boilerplate>` 标签）
- [ ] `buildForkedConversation` 在 `AgentTool.java:284-308` 给悬挂 `toolUses` 补占位 `ToolResultBlock("(tool execution interrupted by fork)")`
- [ ] 错误消息 `"Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead."` 在 `AgentTool.java:266` 与文档描述的 isInForkChild 语义一致

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `AgentTool` 实例由主装配点构造并通过 setter 注入依赖：`setAgentSpecs(AgentLoader.loadAll(projectRoot))` / `setTaskManager(new SubAgentTaskManager())` / `setProgressListener(...)` / `setParentConversation(...)` / `setWorktreeManager(...)` / `setTeamManager(...)` / `setModelResolver(...)`
- [ ] `registry.register(agentTool)` 在装配阶段调用
- [ ] 主 Agent 的 `notificationFn` 绑定 `() -> taskManager.drainNotifications().stream().map(...).toList()`，使后台任务完成通知能在下一轮注入 conversation（`com.cncode.agent.Agent.agentLoop` @ 79-83）
- [ ] `SubAgentProgress` 的消费者（TUI / 日志）订阅 `progressListener` 并把工具调用计数 / 失败状态展示给用户
- [ ] `AgentTool.shouldDefer() == true`（`AgentTool.java:198-201`），确认 `Agent` 工具的 schema 只在 ToolSearch 选中时下发

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] SubAgent 模块单测全部 PASS（loader / tool_filter / task_manager / fork 嵌套拒绝）

## 4. 端到端验证

- [ ] 注册路径：主装配点 register 完毕后，用户向主 Agent 发送 "spawn a plan agent to review X" → LLM 返回 `Agent` 工具调用 → `execute` → `runSync(spec=plan)` → 子 Agent 流式输出 → 控制台见 `Agent "..." completed in X.XXXs.`
- [ ] Fork 路径：用户在对话进行中说 "fork to investigate Y" → LLM 调用 `Agent` 不带 `subagent_type` → `runFork` → forked conversation 启动后台 task → 完成时 `TaskNotification` 通过 `drainNotifications` 注入下一轮
- [ ] 后台路径：调用带 `run_in_background=true` → 立即返回 `task_N` → 后台虚拟线程跑完 → 主 Agent 下一轮拿到完成通知
- [ ] 工具过滤验证：子 Agent 调 `Agent` 工具应直接被过滤掉（`ALWAYS_DISALLOWED` 命中），子 Agent 看不到 `Agent` 工具，从根源切断递归

## 5. 文档

- [ ] `docs/java/ch13/spec.md` 已写
- [ ] `docs/java/ch13/tasks.md` 已写，14 个 T 全部勾完
- [ ] `docs/java/ch13/checklist.md` 已写并逐项验收

---

## 6. 工具过滤细节验收

### 6.1 全局禁止集合 `ALWAYS_DISALLOWED`（7 项）

- [ ] `ToolFilter.java:30-33` 含七项：`TaskOutput / ExitPlanMode / EnterPlanMode / Agent / AskUserQuestion / TaskStop / Workflow`

### 6.2 异步白名单 `ASYNC_ALLOWED`（15 项）

- [ ] `ToolFilter.java:42-46` 含 15 项：`ReadFile / WebSearch / TodoWrite / Grep / WebFetch / Glob / Bash / EditFile / WriteFile / NotebookEdit / Skill / LoadSkill / SyntheticOutput / ToolSearch / EnterWorktree / ExitWorktree`（实际计 16 个名字，记 15 个槽位的扩展含义参照 Go 对照表）

### 6.3 In-process teammate 额外允许 `IN_PROCESS_TEAMMATE_ALLOWED`（8 项）

- [ ] `ToolFilter.java:49-52` 含 8 项：`TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / CronCreate / CronDelete / CronList`
- [ ] `filterForAgent(source, spec, isAsync=true, isCustom=*, isInProcessTeammate=true)` 在异步白名单层额外放行 `Agent` 与上述 8 项

### 6.4 六层过滤顺序

- [ ] 第 1 层：`isMcpTool(name)`（`mcp__` 前缀）直接 register
- [ ] 第 2 层：`ALWAYS_DISALLOWED` 命中 continue
- [ ] 第 3 层：`isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)` continue
- [ ] 第 4 层：`isAsync == true` 时，非 `ASYNC_ALLOWED` 工具一律 continue，除非 `isInProcessTeammate` 且命中 `Agent` 或 teammate 集合
- [ ] 第 5 层：`spec.disallowedTools()` 黑名单 continue
- [ ] 第 6 层：`spec.tools()` 白名单交集（`["*"]` 视为无白名单）

## 7. AgentLoader 验收

- [ ] `loadAll(projectRoot)` 顺序：builtin → `~/.cncode/agents` → `<projectRoot>/.cncode/agents`（`AgentLoader.java:39-53`）
- [ ] `LinkedHashMap` 保 put 覆盖语义，同名后注册胜出
- [ ] `parseAgentFile` 缺 `name` 抛 `Agent definition <path>: missing required field 'name'`
- [ ] `parseAgentFile` 缺 `description` 抛 `Agent definition <path>: missing required field 'description'`
- [ ] `parseAgentFile` 非法 `model` 抛 `Agent definition <path>: invalid model '<value>'`
- [ ] 解析失败的文件被 `loadDir` catch 后静默跳过，不影响其他文件加载
- [ ] body 为空时 `systemPromptOverride == null`，非空则等于 trimmed body

## 8. TaskManager 验收

- [ ] `createTask` 返回 `task_N`，`N` 从 `AtomicInteger.incrementAndGet()` 取（`SubAgentTaskManager.java:44-48`）
- [ ] `setRunning` 把 `Thread` 引用挂到 `TaskEntry.thread`
- [ ] `setCompleted` 把 `TaskNotification(id, name, COMPLETED, output)` 入队
- [ ] `setFailed` 把 `TaskNotification(id, name, FAILED, errMsg)` 入队
- [ ] `cancelTask` 仅在 `RUNNING` 状态生效，转 `CANCELLED` + `Thread.interrupt()` + 入队 `CANCELLED` 通知
- [ ] `drainNotifications` 返回拷贝并清空原列表
- [ ] 所有公共方法 `synchronized`
- [ ] `spawnSubAgent` 用 `Thread.startVirtualThread` 启动后台线程（`SubAgentTaskManager.java:117`）
- [ ] 事件循环超时 60s → `setFailed("Timeout")`；`InterruptedException` → `setFailed("Interrupted")`
- [ ] `LoopComplete` 时输出为空回退到 `"(agent produced no output)"`

## 9. AgentTool runSync 验收

- [ ] `maxIterations = spec.maxTurns() > 0 ? spec.maxTurns() : 200`（`AgentTool.java:315-316`）
- [ ] `isolation == "worktree"` 时 slug 形如 `agent-aXXXXXXX`（`SecureRandom` 4 字节 hex 取前 7）（`AgentTool.java:321-323`）
- [ ] worktree 创建失败返回 `Error creating agent worktree: <msg>`
- [ ] `LoopComplete` 后 `WorktreeChanges.hasChanges(path, headCommit)` 为真保留并附 `\n\nWorktree kept at <path> (branch <branch>) — has uncommitted changes or new commits.`；为假调 `AgentWorktree.remove`
- [ ] 最终 `ToolResult.success` 文案：`Agent "%s" completed in %d.%03ds.\n\n%s%s`

## 10. AgentTool 文案（Tool 接口可读性）

- [ ] `description()` 当 `agentSpecs` 非空时按 `AgentLoader.listNames` 字典序枚举可用 agent（`AgentTool.java:123-127`）
- [ ] `description()` 缺省提示三档 builtin（fallback 文案）
- [ ] `schema()` 的 `subagent_type.enum` 与 `description()` 列出的 agent 类型一致

## 11. 模型选择 `selectClient`

- [ ] `selectClient(specModel, overrideModel)` 优先取 `overrideModel`，其次 `specModel`，再次 fallback 到父 client（`AgentTool.java:489-501`）
- [ ] `model == "inherit" || model == ""` 直接返回父 client
- [ ] `modelResolver != null` 时调 `modelResolver.apply(model)`，结果 null 时 fallback 父 client
- [ ] `ModelResolver.ALIASES` 含 `haiku / sonnet / opus` 三个键（`src/main/java/com/cncode/llm/ModelResolver.java:7-11`）

## 12. 父子 Agent 联动（`com.cncode.agent.Agent`）

- [ ] `notificationFn` setter 存在（`Agent.java:46`）；主循环每轮开头通过 `notificationFn.get()` 抽取并 `addSystemReminder`（`Agent.java:79-83`）
- [ ] 子 Agent 复用同一套 `agentLoop`，由 `subAgent.run(conv)` 启动虚拟线程并返回 `BlockingQueue<AgentEvent>`（`Agent.java:50-60`）
- [ ] `setMaxIterations` 在 `runSync` / `spawnSubAgent` 内被显式设置






# ch14: Worktree Spec（Java 版）

## 1. 背景

SubAgent 隔离了消息、权限、工具结果缓存，但所有子 Agent 仍然共享同一个工作目录——两个子 Agent 并发改同一个文件会互相覆盖。Git 分支不解决这个问题：分支只是时间维度的快照，同一时刻整个仓库仍然只有一份 working tree，切换分支会动所有文件的修改时间触发不必要的全量重编。多 Agent 并行要的是空间维度的隔离：同时存在多份独立的 working tree，每份对应不同分支，但共享同一个 `.git`。Git Worktree 提供的就是这个能力。这一章把它接进 CN Code 的 Java 实现，让主 Agent 和每个子 Agent 都能拥有独立的文件视图。

## 2. 目标

把 worktree 做成两层 API：会话级让 LLM 通过 `EnterWorktreeTool` / `ExitWorktreeTool` 自主进出 worktree，Agent 级让 SubAgent 通过 `isolation: "worktree"` 声明自动获得独立 worktree。底层共用 `WorktreeManager` 的 `git worktree add/remove` 调用、`AgentWorktree` 的快速恢复路径，以及 `PostCreationSetup`（本地配置复制 / git hooks 配置 / 大目录软链接 / `.worktreeinclude` 文件复制）。叠加 `WorktreeChanges` 的 fail-closed 变更检测（无变更才允许清掉、有变更默认保留）和 `StaleCleanup` 对孤儿 worktree 的后台过期清理，保证既不丢用户工作、又不让磁盘堆积。

## 3. 功能需求

- F1: worktree 名称（slug）安全校验：限定字符集、长度上限 64、按 `/` 切段、显式拒绝 `.` / `..` 段，校验失败抛 `IllegalArgumentException` 分类错误（长度 / 段名非法 / 路径遍历）；任何 git 命令或路径拼接之前先跑。
- F2: slug 到路径和分支的映射：用 `+` 替换 `/`（git 安全但不在 slug 字符集），避免嵌套 slug 导致目录或分支命名冲突；分支统一加 `worktree-` 前缀，方便从 `git branch` 输出里识别 CN Code 创建的。
- F3: 快速恢复路径：worktree 目录已存在时跳过 `git worktree add`，用 `Files.isDirectory` + `Files.setLastModifiedTime` bump mtime + 调一次 `git rev-parse HEAD` 拿 SHA；任一步失败回退到完整创建路径。
- F4: git 子进程统一安全壳：所有 `ProcessBuilder` 调用都在 `environment()` 里写 `GIT_TERMINAL_PROMPT=0` 和 `GIT_ASKPASS=`，绝不挂起等待用户输入；用 `waitFor(N, TimeUnit.SECONDS)` 超时保护，超时后 `destroyForcibly()`；进程失败抛 `IOException` 而不是 `RuntimeException`。
- F5: 创建/恢复主入口：`WorktreeManager.create` 接收 branch + 可选 targetDir，未给 targetDir 时默认 `<projectRoot>/.cncode/worktrees/<branch>`，用大写 `-B` 创建 worktree（容忍上次未清干净的孤儿分支）；`AgentWorktree.create` 在 slug 校验后先看目录是否存在，命中则快速恢复，未命中跑 `git worktree add -B <branch> <path> HEAD`。
- F6: 创建后设置四项：从主仓复制 `.cncode/settings.local.json`；按 `.husky` > `.git/hooks` 优先级在 worktree 里跑 `git config core.hooksPath <path>`；按 `WorktreeManager.symlinkDirs` 配置软链接 `node_modules` 等目录（跳过含 `..` 项）；按 `.worktreeinclude` gitignore 风格模式复制被 `.gitignore` 忽略但运行需要的文件；任何单项失败只记日志、不中断创建。
- F7: 会话级 API 三件套：进入（`WorktreeManager.create` + 写 `WorktreeSessionStore` 单例 + 持久化 JSON）、Keep（`ExitWorktreeTool action=keep`：清单例 + 删持久化文件，保留 worktree 目录和分支）、Remove（`action=remove`：清单例 + 删持久化 + `WorktreeManager.remove`）。
- F8: 会话持久化：`WorktreeSessionStore.save` 把 `WorktreeSession` record 序列化到 `<repo>/.cncode/worktree_session.json`，用 Jackson `ObjectMapper` + `@JsonProperty` snake_case 映射；`save(repo, null)` 等价于 `Files.deleteIfExists`。
- F9: 启动恢复：应用启动时调 `WorktreeSessionStore.load(repoRoot)`，非 null 时调 `restoreSession` 写回 `volatile` 全局字段；不主动切 cwd（让用户或工具自行决定），不重跑创建后设置。
- F10: Agent 级 API：`AgentWorktree.create(slug, repoRoot, symlinkDirs)` 静态方法返回 `Result(worktreePath, worktreeBranch, headCommit, gitRoot)` record；不动 `WorktreeSessionStore` 单例、不切 JVM cwd、不写持久化；快速恢复路径要 `Files.setLastModifiedTime` 防被 `StaleCleanup` 误判为孤儿。
- F11: SubAgent 集成：`AgentTool` 在解析参数时拿到 `isolation: "worktree"` 且 `worktreeManager != null` 时，生成 `agent-a<7hex>` slug → 调 `AgentWorktree.create` → 把 `subAgent.setWorkDir(wtResult.worktreePath())` → 在任务 prompt 前面拼 `AgentWorktree.buildNotice(parentCwd, wtPath)` 注入隔离 notice → 跑子 Agent。
- F12: 子 Agent 完成后决策：`LoopComplete` 事件触发时调 `WorktreeChanges.hasChanges(wtPath, headCommit)`，干净自动 `AgentWorktree.remove`、脏则保留并在返回结果末尾附 `"Worktree kept at <path> (branch <branch>) — has uncommitted changes or new commits."`。
- F13: 变更保护：`ExitWorktreeTool` 在 `action="remove"` 且 `discard_changes` 不为 `true` 时跑 `WorktreeChanges.countChanges`——返回 null（状态无法验证）报 `"Could not verify worktree state..."`；`changedFiles > 0` 或 `commits > 0` 报具体数字（"N uncommitted file(s) and M commit(s)"）；要求 LLM 显式传 `discard_changes=true` 才能强删。
- F14: 变更检测 fail-closed：`WorktreeChanges.hasChanges` 在 git status / rev-list 任何一步失败（runGit 返 null 或抛异常）都返 `true`；`countChanges` 在状态拿不到时返 `null`，强制调用方按"未知即不安全"处理。
- F15: LLM Tool 暴露：`EnterWorktreeTool`（input 仅可选 `name`，已有 session 时拒绝 `"Already in a worktree session"`）和 `ExitWorktreeTool`（input `action` 必填枚举 `["keep","remove"]` / `discard_changes` 可选 bool，无 session 时拒绝）；两个 Tool 的 `shouldDefer()` 都返 `true`，由 Agent loop 在工具批次结束时统一执行。
- F16: 临时 worktree 命名模式：用前缀正则区分"自动产物"（`agent-a` / `wf_` / `wf-` / `bridge-` / `job-` 五类）和"用户手动命名"；用户起名永远不会被后台清理动。
- F17: 后台过期清理三层过滤：`StaleCleanup.cleanup` 扫 `<repo>/.cncode/worktrees/`，依次过滤——L1 `isEphemeral`（不匹配五个正则的跳过）→ L2 时态（跳过当前 session 占用的 + `lastModifiedTime().toInstant().isAfter(cutoff)` 的）→ L3 git 状态 fail-closed（`status --porcelain -uno` 非空或失败跳过 + `rev-list --max-count=1 HEAD --not --remotes` 非空或失败跳过）；删完跑 `git worktree prune` 同步 git 内部表；`startCleanupLoop` 通过 `ScheduledExecutorService.scheduleAtFixedRate` 周期跑。

## 4. 非功能需求

- N1: `WorktreeSessionStore` 用 `volatile` + 静态字段保证并发可见性；`WorktreeManager` 所有公开方法 `synchronized` 保护内存里的 `LinkedHashMap<String, WorktreeInfo>`；Agent 级 API（`AgentWorktree.create/remove`）是无状态静态方法，天然并发安全。
- N2: 任何路径的 worktree 删除（会话级 Remove / Agent 级 Remove / 后台清理）都不在 worktree 内执行 git 命令——`AgentWorktree.remove` 显式从 `gitRoot` 跑 `ProcessBuilder` 的 `directory()`，否则 `git worktree remove` 会因为当前在被删目录里失败。
- N3: `git worktree remove` 和 `git branch -D` 之间必须 `Thread.sleep(100)` 等 git lockfile 释放，否则 branch 删除会偶发失败。
- N4: Agent 级 API 在快速恢复（worktree 目录已存在）时必须 `Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime，否则同一 worktree 被反复复用时会因为 mtime 太老被 `StaleCleanup` 误删。
- N5: 三层过滤的执行顺序固定：先廉价的命名模式 → 再时态判断 → 最后贵的 git 检查；任何一层判定保留都 `continue`，不进入下一层。
- N6: `PostCreationSetup` 的四项里软链接和 `.worktreeinclude` 复制是 best-effort——`catch (IOException e)` 只 `log.fine` 不抛、不中断创建，保证主路径鲁棒。
- N7: 变更保护的错误信息必须包含具体数字（N 文件 + M commits）和单复数（"1 file" vs "2 files"、"1 commit" vs "2 commits"），让 LLM 能据此判断要不要强删；不能只回 "has changes" 这种空话。
- N8: worktree 子系统不假设统一日志层存在，所有创建/退出/清理的关键信息通过 `ToolResult` 文本传达；这同时是给 LLM 的运行时反馈，`java.util.logging.Logger` 只用于内部 best-effort 失败。

## 5. 设计概要

- 核心数据结构（全部 Java 17+ `record`）:
  - `WorktreeManager.WorktreeInfo(path, branch, createdAt)`：底层创建路径返回值，挂在 `WorktreeManager` 内存 map 里。
  - `AgentWorktree.Result(worktreePath, worktreeBranch, headCommit, gitRoot)`：Agent 级 API 返回值，不写全局状态。
  - `WorktreeSession(originalCwd, worktreePath, worktreeName, worktreeBranch, originalBranch, originalHeadCommit, sessionId, creationDurationMs)`：会话级单例，Jackson 序列化到磁盘，`@JsonProperty` 写 snake_case key。
  - `WorktreeChanges.ChangeSummary(changedFiles, commits)`：变更计数，供变更保护错误信息生成。
  - 配置块：`WorktreeManager` 构造参数 `symlinkDirs` + `staleCutoffHours`，由应用启动时注入；后台清理由 `StaleCleanup.startCleanupLoop` 单独调度，间隔 ≤ 0 时不启动。
- 主流程:
  - **会话级 Enter**：`EnterWorktreeTool.execute` → guard `WorktreeSessionStore.getCurrentSession() != null` → slug 校验（`SlugValidator.validate`）→ `WorktreeManager.create` → 组装 `WorktreeSession` record → `restoreSession` + `save`。
  - **会话级 Exit**：`ExitWorktreeTool.execute` → guard 无 session → 若 `action=remove && !discard_changes` 跑 `WorktreeChanges.countChanges` 变更保护 → 清单例 → `save(repo, null)` 删持久化 → `action=remove` 时调 `WorktreeManager.remove`。
  - **Agent 级隔离**：`AgentTool.runSync` → `isolation=="worktree" && worktreeManager != null` → 生成 `agent-a<7hex>` slug → `AgentWorktree.create` → `subAgent.setWorkDir(wtPath)` → `prompt = buildNotice(parentCwd, wtPath) + "\n\n" + prompt` → 跑子 Agent → `LoopComplete` 时 `WorktreeChanges.hasChanges`：干净 `AgentWorktree.remove` / 脏拼 `wtInfo` 后缀。
  - **后台过期清理**：`StaleCleanup.startCleanupLoop(executor, repoRoot, intervalSeconds, cutoffHours)` → `scheduleAtFixedRate` → 每轮 `cleanup(repoRoot, Instant.now().minusSeconds(cutoffHours*3600))` → 三层过滤 → 通过的 `AgentWorktree.remove` → 末尾若有删除跑一次 `git worktree prune`。
- 调用链（模块层级）:
  - 应用启动 → 构造 `WorktreeManager(projectRoot, symlinkDirs, staleCutoffHours)` → 注册 `EnterWorktreeTool` 和 `ExitWorktreeTool` → `WorktreeSessionStore.load + restoreSession` 恢复 session → `StaleCleanup.startCleanupLoop` 起后台任务。
  - LLM Enter/Exit → Tool dispatcher → `WorktreeManager` / `WorktreeSessionStore` / `WorktreeChanges`。
  - `AgentTool` → 看到 `isolation: worktree` → `AgentWorktree.create` → 子 Agent 跑完 → `WorktreeChanges.hasChanges` → `AgentWorktree.remove` 或拼字符串保留。
- 与其他模块的交互:
  - 依赖 `com.cncode.tool`（Tool 接口 + ToolResult + ToolCategory）、`com.cncode.subagent`（AgentTool 注入 `setWorktreeManager`）、`com.cncode.agent`（`Agent.setWorkDir`）；底层只依赖 `ProcessBuilder`（git）+ `java.nio.file` + `com.fasterxml.jackson.databind.ObjectMapper`。
  - 不依赖 `com.cncode.config` 通用加载链路——worktree 配置当前由应用启动时手动注入；也不依赖 `com.cncode.memory` / `com.cncode.prompt`。

## 6. Out of Scope

- 不实现非 git VCS 适配（hg / jj / sapling 等），所有 worktree 操作 hardcode 走 `ProcessBuilder("git", ...)`
- 不实现 sparse checkout / partial clone 优化，大型 mono-repo 优化推到后续
- 不实现 `--worktree` CLI 启动快速路径（涉及终端子系统，留给 ch15）
- 不实现 PR fetch 或 pull request 头引用解析（远端协作场景）
- 不实现 prepare-commit-msg hook 注入 commit attribution（商业 feature 场景）
- 不实现 ReadFile / Memory / SystemPrompt 缓存清理 hook（CN Code 当前没有这几类缓存）
- 不引入第三方 gitignore 库（`PostCreationSetup.matchesAnyPattern` 简化匹配够用）
- 团队成员（teammate）路径的 worktree 自动清理推到 ch15 收尾，本章 teammate 路径只创建并隔离、不负责清理

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch14: Worktree Tasks（Java 版）

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 实现 Slug 校验 + 命名映射
- 影响文件: `src/main/java/com/cncode/worktree/SlugValidator.java`（`MAX_LENGTH` @ 11；`VALID_SEGMENT` @ 12；`validate` @ 16-37；`flatten` @ 39-41；`branchName` @ 43-45）
- 依赖任务: 无
- 完成标准: `validate(String slug)` 校验长度 ≤ 64、按 `/` 切段、每段匹配 `^[a-zA-Z0-9._-]+$`、显式拒绝 `.` / `..` 段，错误分类（cannot be empty / 长度 / `.` `..` 段 / 非法段）通过 `IllegalArgumentException` 抛出；`flatten(s) = s.replace('/', '+')`；`branchName(s) = "worktree-" + flatten(s)`；类声明为 `final`，构造私有，只暴露静态方法。
- [ ] 完成

## T2: 实现 git 进程执行壳
- 影响文件: `src/main/java/com/cncode/worktree/WorktreeManager.java`（`runGit` @ 180-200）、`src/main/java/com/cncode/worktree/WorktreeChanges.java`（`runGit` @ 64-87）、`src/main/java/com/cncode/worktree/StaleCleanup.java`（`runGitQuiet` @ 113-134）、`src/main/java/com/cncode/worktree/AgentWorktree.java`（`readHead` @ 106-118）
- 依赖任务: 无
- 完成标准: 所有 `ProcessBuilder` 调用前在 `environment()` put `GIT_TERMINAL_PROMPT=0` 和 `GIT_ASKPASS=""`（`WorktreeChanges.runGit` @ 72-73、`StaleCleanup.runGitQuiet` @ 120-121、`AgentWorktree.create` @ 45-46）；用 `waitFor(N, TimeUnit.SECONDS)` 超时保护（30 或 60 秒），未完成时 `destroyForcibly()`；进程退出非 0 时按调用约定要么抛 `IOException`（`WorktreeManager.runGit` @ 196-198）要么返 `null`（`WorktreeChanges.runGit` @ 83）。
- [ ] 完成

## T3: 实现 WorktreeManager 主入口
- 影响文件: `src/main/java/com/cncode/worktree/WorktreeManager.java`（`WorktreeInfo` record @ 25；构造 @ 32-36；`create` @ 51-65；`remove` @ 70-78；`list` @ 86-97；`cleanupStale` @ 112-132；`detectChanges` @ 156-176；`parsePorcelain` @ 211-240）
- 依赖任务: T2
- 完成标准: `WorktreeInfo(path, branch, createdAt)` record；构造接收 `projectRoot` + `symlinkDirs`（null 容忍为 `List.of()`）+ `staleCutoffHours`（<=0 时默认 24）；`create(branch, targetDir)` 在 `targetDir==null` 时默认 `<projectRoot>/.cncode/worktrees/<branch>`，调 `git worktree add -B <branch> <wtDir>` 大写 `-B` 容忍孤儿分支，成功后调 `PostCreationSetup.perform` 跑四项设置，最后把 `WorktreeInfo` 放进 `LinkedHashMap`；`remove(branch)` 拿出 map 项跑 `git worktree remove <path> --force` 然后 `worktrees.remove(branch)`；`list()` 优先解析 `git worktree list --porcelain` 输出（`parsePorcelain` 按 blank line 分块），失败回退内存 map；所有公开方法 `synchronized`。
- [ ] 完成

## T4: 实现 PostCreationSetup 四项
- 影响文件: `src/main/java/com/cncode/worktree/PostCreationSetup.java`（`perform` @ 19-24；`copySettingsLocal` @ 26-36；`configureHooksPath` @ 38-58；`symlinkDirectories` @ 60-73；`copyWorktreeIncludeFiles` @ 75-106；`matchesAnyPattern` @ 108-116）
- 依赖任务: 无
- 完成标准: `perform(repoRoot, worktreePath, symlinkDirs)` 依次跑四项；`copySettingsLocal` 复制 `<repo>/.cncode/settings.local.json`（不存在静默 return），失败 `log.fine`；`configureHooksPath` 优先 `.husky` 回退 `.git/hooks`，找到第一个存在目录后在 worktree 目录里跑 `git config core.hooksPath <hooksPath>`；`symlinkDirectories` 跳过含 `..` 项 + 跳过 src 不存在或 dst 已存在的 + `Files.createSymbolicLink(dst, src)` 错误 `log.fine`；`copyWorktreeIncludeFiles` 读 `.worktreeinclude` 按行收集（跳空行和 `#`）→ 在 repoRoot 跑 `git ls-files --others --ignored --exclude-standard --directory` → 对每行（跳目录和空）`matchesAnyPattern` 判定后 `Files.createDirectories(dst.getParent()) + Files.copy(src, dst)`；`matchesAnyPattern` 支持去前导 `/` 后 exact / basename / dir prefix 三种匹配。
- [ ] 完成

## T5: 实现变更检测 fail-closed
- 影响文件: `src/main/java/com/cncode/worktree/WorktreeChanges.java`（`ChangeSummary` record @ 12；`hasChanges` @ 20-31；`countChanges` @ 38-62；`runGit` @ 64-87）
- 依赖任务: T2
- 完成标准: `ChangeSummary(changedFiles, commits)` record；`hasChanges(wtPath, headCommit)` — `git status --porcelain` 非 null 非空 → true；`git rev-list --count <headCommit>..HEAD` 为 null 或解析后 > 0 → true；任何异常 catch 后返 `true`（**fail-closed**）。`countChanges(wtPath, originalHeadCommit)` — `originalHeadCommit==null||isBlank` 返 null；`status --porcelain` 返 null 时返 null，否则按 `\n` 切并数非空行；`rev-list --count` 返 null 或 `NumberFormatException` 时返 null；否则返 `new ChangeSummary(changedFiles, commits)`。
- [ ] 完成

## T6: 实现 AgentWorktree 静态 API
- 影响文件: `src/main/java/com/cncode/worktree/AgentWorktree.java`（`Result` record @ 20；`create` @ 27-59；`remove` @ 64-89；`buildNotice` @ 95-104；`readHead` @ 106-118）
- 依赖任务: T1, T2, T4
- 完成标准: `Result(worktreePath, worktreeBranch, headCommit, gitRoot)` record；`create(slug, repoRoot, symlinkDirs)` — `SlugValidator.validate` → `wtPath = <repoRoot>/.cncode/worktrees/<flatten(slug)>` + `branch = "worktree-" + flatten(slug)` → `Files.isDirectory(wtPath)` 时快速恢复（`Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime + `readHead`）→ 否则 `Files.createDirectories(wtPath.getParent())` + `ProcessBuilder("git","worktree","add","-B",branch,wtPath,"HEAD")` + `PostCreationSetup.perform` → 返 `Result`；**不动 `WorktreeSessionStore`、不切 JVM cwd、不写持久化**。`remove(wtPath, wtBranch, gitRoot)` — gitRoot 空返 false → `ProcessBuilder` 从 `gitRoot.toFile()` 跑 `git worktree remove --force <wtPath>`（**不**从 wtPath 否则把自己删掉）→ 成功后 `Thread.sleep(100)` 等 lockfile → 分支非空跑 `git branch -D <branch>` → 返 true；异常时 `log.fine` 后返 false。`buildNotice(parentCwd, worktreeCwd)` 返固定模板字符串含 `parentCwd` / `worktreeCwd` 占位 + "isolated git worktree" / "translate them" / "Re-read files before editing" / "will not affect the parent's files" 关键句。
- [ ] 完成

## T7: 实现 WorktreeSession + Store
- 影响文件: `src/main/java/com/cncode/worktree/WorktreeSession.java`（record @ 11-20）、`src/main/java/com/cncode/worktree/WorktreeSessionStore.java`（`MAPPER` @ 15；`currentSession` @ 16；`getCurrentSession` @ 20；`restoreSession` @ 24；`save` @ 28-36；`load` @ 38-48；`sessionPath` @ 54-56）
- 依赖任务: 无
- 完成标准: `WorktreeSession` Java record，8 字段 + Jackson `@JsonProperty` snake_case：`original_cwd` / `worktree_path` / `worktree_name` / `worktree_branch` / `original_branch` / `original_head_commit` / `session_id` / `creation_duration_ms`；类标注 `@JsonIgnoreProperties(ignoreUnknown = true)` 兼容字段增减。`WorktreeSessionStore` 用 `private static volatile WorktreeSession currentSession` 保证并发可见；`getCurrentSession` 直接返字段；`restoreSession(WorktreeSession)` 直接写字段（也接受 null 清除）；`save(repoRoot, session)` — session=null 时 `Files.deleteIfExists(sessionPath)`，否则 `Files.createDirectories(parent) + MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, session)`；`load(repoRoot)` 读 `.cncode/worktree_session.json`，不存在返 null，反序列化 `IOException` 返 null；`sessionPath = <repo>/.cncode/worktree_session.json`。
- [ ] 完成

## T8: 实现 EnterWorktreeTool
- 影响文件: `src/main/java/com/cncode/tool/impl/EnterWorktreeTool.java`（`worktreeManager / sessionId / RANDOM` @ 19-21；构造 @ 23-26；`name / category / shouldDefer / description` @ 28-35；`schema` @ 37-52；`execute` @ 54-91）
- 依赖任务: T1, T3, T7
- 完成标准: 实现 `Tool` 接口；`name()="EnterWorktree"`、`category()=ToolCategory.COMMAND`、`shouldDefer()=true`；input schema 仅 `name: string`（可选，max 64 chars 提示）；`execute` guard `WorktreeSessionStore.getCurrentSession() != null` → `ToolResult.error("Already in a worktree session")`；`name` 缺省时用 `RANDOM.nextInt()` 生成 `"wt-" + Integer.toHexString(...)`；`SlugValidator.validate` 失败时返 error；调 `worktreeManager.create(slug, null)` → 组装 `WorktreeSession(System.getProperty("user.dir"), info.path(), slug, info.branch(), "", "", sessionId, 0)` → `restoreSession + save` → 返 `ToolResult.success("Created worktree at <path> on branch <branch>. The session is now working in the worktree. Use ExitWorktree to leave mid-session.")`。
- [ ] 完成

## T9: 实现 ExitWorktreeTool
- 影响文件: `src/main/java/com/cncode/tool/impl/ExitWorktreeTool.java`（`worktreeManager` @ 19；构造 @ 21-23；`name / category / shouldDefer / description` @ 25-32；`schema` @ 34-55；`execute` @ 57-121）
- 依赖任务: T3, T5, T7
- 完成标准: 实现 `Tool` 接口；`name()="ExitWorktree"`、`shouldDefer()=true`；input schema `action: enum["keep","remove"]`（required）+ `discard_changes?: bool`；`execute` scope guard：`getCurrentSession()==null` → `ToolResult.error("No-op: there is no active EnterWorktree session to exit. This tool only operates on worktrees created by EnterWorktree in the current session.")`；变更保护：`action="remove" && !discard_changes` 时 `WorktreeChanges.countChanges`：null 报 `"Could not verify worktree state. Refusing to remove without explicit confirmation. Re-invoke with discard_changes: true, or use action: \"keep\"."`；`changedFiles>0 || commits>0` 时按部分拼接 — `changedFiles==1 ? "file" : "files"` + `commits==1 ? "commit" : "commits"` 单复数正确，用 `String.join(" and ", parts)`；`restoreSession(null) + save(repoRoot, null)`（save 失败 swallow）；`action="remove"` 调 `worktreeManager.remove(session.worktreeName())` 失败返 error，成功返 `"Exited and removed worktree at <path>. Session is now back in <originalCwd>."`；`action="keep"` 返 `"Exited worktree. Your work is preserved at <path>. Session is now back in <originalCwd>."`。
- [ ] 完成

## T10: 接入 SubAgent isolation（AgentTool.runSync）
- 影响文件: `src/main/java/com/cncode/subagent/AgentTool.java`（`worktreeManager` 字段 @ 51；`setWorktreeManager` @ 98-100；`isolation` schema @ 176-180；`execute` 解析 `isolation` @ 228；`runSync` worktree 分支 @ 310-335 和 388-399；`runAsTeammate` worktree 分支 @ 456-472）
- 依赖任务: T5, T6, T3
- 完成标准: `AgentTool.schema()` 中 `properties.put("isolation", Map.of("type","string","enum", List.of("worktree"), ...))`；`execute` 调 `getStringArg(args, "isolation")` 解析；`runSync(spec, description, prompt, modelOverride, isolation)` 在 `"worktree".equals(isolation) && worktreeManager != null` 时：
  1. 用 `SecureRandom` 生成 4 字节 → `HexFormat.of().formatHex(rndBytes).substring(0,7)` → `slug = "agent-a" + 7hex`（匹配 cleanup 正则 `^agent-a[0-9a-f]{7}$`）；
  2. `wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs())`；
  3. `subAgent.setWorkDir(wtResult.worktreePath())`；
  4. `notice = AgentWorktree.buildNotice(System.getProperty("user.dir"), wtResult.worktreePath())`；
  5. `prompt = notice + "\n\n" + prompt`；
  6. 创建失败 → `return ToolResult.error("Error creating agent worktree: " + e.getMessage())`；
  `LoopComplete` 事件处理时（`wtResult != null` 分支）调 `WorktreeChanges.hasChanges(wtResult.worktreePath(), wtResult.headCommit())`：true → `wtInfo = "\n\nWorktree kept at <path> (branch <branch>) — has uncommitted changes or new commits."`；false → `AgentWorktree.remove(wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot())`；最后 `result + wtInfo` 拼回。`runAsTeammate` 在 `"worktree".equals(isolation)` 时执行同样三步（创建 + workdir + notice 注入），但**不**做完成后自动清理（teammate 长生命周期，留给 ch15 收尾）。
- [ ] 完成

## T11: 实现后台过期清理
- 影响文件: `src/main/java/com/cncode/worktree/StaleCleanup.java`（`EPHEMERAL_PATTERNS` @ 23-29；`isEphemeral` @ 33-35；`cleanup` @ 41-88；`startCleanupLoop` @ 93-111；`runGitQuiet` @ 113-134）
- 依赖任务: T6
- 完成标准: 五个临时命名正则常量列表：`^agent-a[0-9a-f]{7}$` / `^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$` / `^wf-\d+$` / `^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$` / `^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$`；`isEphemeral(slug)` 任一匹配返 true。`cleanup(repoRoot, cutoff)` — `dir = <repoRoot>/.cncode/worktrees`，不存在返 0 → 取 `WorktreeSessionStore.getCurrentSession()?.worktreePath()` 作为白名单 → `Files.list(dir)` 遍历每项 `slug = entry.getFileName()`：
  - **L1 命名**：`!isEphemeral(slug)` → continue（用户命名永不删）
  - **L2 时态**：`wtPath.equals(currentPath)` → continue；`Files.readAttributes(entry, BasicFileAttributes.class).lastModifiedTime().toInstant().isAfter(cutoff)` → continue；读 attrs 异常也 continue
  - **L3 git 状态 fail-closed**：`runGitQuiet(wtPath, "--no-optional-locks", "status", "--porcelain", "-uno")` 返 null 或 非空 → continue；`runGitQuiet(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes")` 返 null 或非空 → continue
  - 三层通过 → `AgentWorktree.remove(wtPath, SlugValidator.branchName(slug), repoRoot)` 成功 `removed++`；
  末尾 `removed > 0` 时跑 `runGitQuiet(repoRoot, "worktree", "prune")`；返 `removed`。`startCleanupLoop(executor, repoRoot, intervalSeconds, cutoffHours)`：`intervalSeconds <= 0` 直接 return；否则 `executor.scheduleAtFixedRate(task, interval, interval, TimeUnit.SECONDS)`，task 算 `cutoff = Instant.now().minusSeconds(cutoffHours*3600L)` 后调 `cleanup`。
- [ ] 完成

## T12: 接入应用启动装配
- 影响文件: 应用入口（如 `src/main/java/com/cncode/Main.java` 或 TUI 启动器，按项目实际路径）
- 依赖任务: T7, T8, T9, T10, T11
- 完成标准:
  1. 构造 `WorktreeManager(projectRoot, symlinkDirs, staleCutoffHours)`，`projectRoot` 由 `System.getProperty("user.dir")` 或仓库根解析得到；
  2. 注册 `new EnterWorktreeTool(worktreeManager, sessionId)` 和 `new ExitWorktreeTool(worktreeManager)` 到 `ToolRegistry`；
  3. `AgentTool.setWorktreeManager(worktreeManager)` 把 `worktreeManager` 注入到 `AgentTool` 实例；
  4. `WorktreeSession saved = WorktreeSessionStore.load(projectRoot)` → 非 null 且 `Files.exists(Path.of(saved.worktreePath()))` 时 `WorktreeSessionStore.restoreSession(saved)`；
  5. `ScheduledExecutorService cleanupExec = Executors.newSingleThreadScheduledExecutor()` → `StaleCleanup.startCleanupLoop(cleanupExec, projectRoot, intervalSeconds, cutoffHours)`，间隔由配置控制（默认 0 = 不启动）；
  6. 应用退出时 `cleanupExec.shutdown()`。
- [ ] 完成

## T13: 端到端验证
- 影响文件: 无（仅运行）
- 依赖任务: T1-T12
- 完成标准:
  - `./gradlew build` 通过（无编译错误，所有单元测试 PASS）；
  - **路径 A — 工具直接驱动**：主 Agent 调 `EnterWorktree({name:"demo"})` 创建 worktree → 在 worktree 里 `WriteFile + Bash("git commit ...")` → `ExitWorktree({action:"remove"})` 被变更保护拒绝并列出具体 file/commit 数（带正确单复数）→ `ExitWorktree({action:"remove", discard_changes:true})` 强删成功，`.cncode/worktrees/demo` 消失；
  - **路径 B — 子 Agent 自动隔离**：主 Agent 在主目录 `WriteFile witness.txt = "original content from main agent"` → 调 `Agent({subagent_type:"general-purpose", isolation:"worktree", description:"...", prompt:"把 witness.txt 改成 ..."})` → 验证主目录 `witness.txt` 内容不变；`.cncode/worktrees/agent-a*/witness.txt` 是修改后版本；若有 commit → 结果末尾出现 `"Worktree kept at ... (branch worktree-agent-a...) — has uncommitted changes or new commits."`；若无修改 → worktree 自动被 `AgentWorktree.remove` 清理；
  - **持久化与重启**：`EnterWorktree({name:"crashtest"})` 后强杀进程 → `.cncode/worktree_session.json` 仍存在 → 重启后 `WorktreeSessionStore.load + restoreSession` 把 session 写回全局 `volatile` 字段。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13


# ch14: Worktree Checklist（Java 版）

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 常量 `MAX_LENGTH = 64` 在 `src/main/java/com/cncode/worktree/SlugValidator.java:11` 定义
- [ ] 正则 `VALID_SEGMENT = ^[a-zA-Z0-9._-]+$` 在 `src/main/java/com/cncode/worktree/SlugValidator.java:12` 定义
- [ ] 函数 `SlugValidator.validate` 在 `src/main/java/com/cncode/worktree/SlugValidator.java:16-37` 含空 / 长度 / `.`-`..` / 非法段四类 `IllegalArgumentException`
- [ ] 函数 `SlugValidator.flatten` 在 `src/main/java/com/cncode/worktree/SlugValidator.java:39` 把 `/` 替换成 `+`；`branchName` 在 `:43` 加 `worktree-` 前缀
- [ ] record `WorktreeManager.WorktreeInfo(path, branch, createdAt)` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:25` 定义
- [ ] 函数 `WorktreeManager.create` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:51-65` 用大写 `-B` 创建 + 调 `PostCreationSetup.perform` + 写内存 map
- [ ] 函数 `WorktreeManager.remove` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:70-78` 跑 `git worktree remove ... --force`
- [ ] 函数 `WorktreeManager.list` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:86-97` 先解析 porcelain 输出，失败回退内存 map
- [ ] 函数 `WorktreeManager.parsePorcelain` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:211-240` 按 blank line 分块，正确处理 `refs/heads/<branch>` 前缀剥离 + 最后一个块无尾随空行
- [ ] 函数 `WorktreeManager.runGit` 在 `src/main/java/com/cncode/worktree/WorktreeManager.java:180-200` 用 `waitFor(60, TimeUnit.SECONDS)` 超时 + 退出非 0 抛 `IOException`
- [ ] 函数 `PostCreationSetup.perform` 在 `src/main/java/com/cncode/worktree/PostCreationSetup.java:19-24` 依序调四项 A/B/C/D
- [ ] 函数 `PostCreationSetup.symlinkDirectories` 在 `src/main/java/com/cncode/worktree/PostCreationSetup.java:60-73` 跳过含 `..` 项 + `Files.createSymbolicLink` 错误 `log.fine`
- [ ] 函数 `PostCreationSetup.copyWorktreeIncludeFiles` 在 `src/main/java/com/cncode/worktree/PostCreationSetup.java:75-106` 单文件失败 catch 不中断（异常被外层 try 包裹）
- [ ] 函数 `PostCreationSetup.matchesAnyPattern` 在 `src/main/java/com/cncode/worktree/PostCreationSetup.java:108-116` 含 exact / basename / dir prefix 三种匹配
- [ ] record `AgentWorktree.Result(worktreePath, worktreeBranch, headCommit, gitRoot)` 在 `src/main/java/com/cncode/worktree/AgentWorktree.java:20` 定义，不含 sessionId
- [ ] 函数 `AgentWorktree.create` 在 `src/main/java/com/cncode/worktree/AgentWorktree.java:27-59` 在已存在时 `Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime
- [ ] 函数 `AgentWorktree.create` 中 `ProcessBuilder.environment().put("GIT_TERMINAL_PROMPT","0")` 和 `put("GIT_ASKPASS","")` 在 `:45-46`
- [ ] 函数 `AgentWorktree.remove` 在 `src/main/java/com/cncode/worktree/AgentWorktree.java:64-89` 从 `gitRoot` 跑 `ProcessBuilder.directory()`（不是 wtPath，否则把自己删掉）
- [ ] 函数 `AgentWorktree.remove` 在 `:76` 含 `Thread.sleep(100)` 等 git lockfile 释放
- [ ] 函数 `AgentWorktree.buildNotice` 在 `src/main/java/com/cncode/worktree/AgentWorktree.java:95-104` 含 `parentCwd` / `worktreeCwd` 占位 + "isolated git worktree" / "translate them" / "Re-read files before editing" / "will not affect the parent's files" 关键句
- [ ] record `WorktreeChanges.ChangeSummary(changedFiles, commits)` 在 `src/main/java/com/cncode/worktree/WorktreeChanges.java:12` 定义
- [ ] 函数 `WorktreeChanges.hasChanges` 在 `src/main/java/com/cncode/worktree/WorktreeChanges.java:20-31` 任何异常 catch 后返 true（fail-closed）
- [ ] 函数 `WorktreeChanges.countChanges` 在 `src/main/java/com/cncode/worktree/WorktreeChanges.java:38-62` `originalHeadCommit` null / blank 时返 null，`NumberFormatException` 时返 null
- [ ] record `WorktreeSession` 在 `src/main/java/com/cncode/worktree/WorktreeSession.java:11-20` 含 8 字段且 `@JsonProperty` snake_case 标注
- [ ] 类 `WorktreeSession` 标 `@JsonIgnoreProperties(ignoreUnknown = true)` 兼容字段增减
- [ ] 字段 `WorktreeSessionStore.currentSession` 在 `src/main/java/com/cncode/worktree/WorktreeSessionStore.java:16` 标 `private static volatile`
- [ ] 函数 `WorktreeSessionStore.save` 在 `src/main/java/com/cncode/worktree/WorktreeSessionStore.java:28-36` session=null 时 `Files.deleteIfExists`
- [ ] 函数 `WorktreeSessionStore.load` 在 `src/main/java/com/cncode/worktree/WorktreeSessionStore.java:38-48` `IOException` 时返 null
- [ ] 函数 `WorktreeSessionStore.sessionPath` 在 `src/main/java/com/cncode/worktree/WorktreeSessionStore.java:54-56` 返 `<repo>/.cncode/worktree_session.json`
- [ ] 变量 `StaleCleanup.EPHEMERAL_PATTERNS` 在 `src/main/java/com/cncode/worktree/StaleCleanup.java:23-29` 含五个正则
- [ ] 函数 `StaleCleanup.cleanup` 在 `src/main/java/com/cncode/worktree/StaleCleanup.java:41-88` 三层过滤顺序固定（L1 命名 → L2 时态 → L3 git 状态 fail-closed）
- [ ] 函数 `StaleCleanup.cleanup` 末尾在 `removed > 0` 时跑 `git worktree prune`（`:84-86`）
- [ ] 函数 `StaleCleanup.startCleanupLoop` 在 `src/main/java/com/cncode/worktree/StaleCleanup.java:93-111` `intervalSeconds <= 0` 直接 return
- [ ] 函数 `StaleCleanup.runGitQuiet` 在 `:113-134` 含 `GIT_TERMINAL_PROMPT=0` + `GIT_ASKPASS` 安全壳
- [ ] 类 `EnterWorktreeTool` 在 `src/main/java/com/cncode/tool/impl/EnterWorktreeTool.java:17` 实现 `Tool` 接口，含 `worktreeManager` + `sessionId` 字段，`shouldDefer()` 返 true
- [ ] 类 `ExitWorktreeTool` 在 `src/main/java/com/cncode/tool/impl/ExitWorktreeTool.java:17` 实现 `Tool` 接口，含 `worktreeManager` 字段，`shouldDefer()` 返 true
- [ ] `ExitWorktreeTool.schema` 在 `src/main/java/com/cncode/tool/impl/ExitWorktreeTool.java:34-55` 含 `action: enum["keep","remove"]`（required）+ `discard_changes?: bool`
- [ ] `ExitWorktreeTool.execute` 在 `:81-95` 实现 file/files 和 commit/commits 单复数正确处理
- [ ] `AgentTool` 字段 `worktreeManager` 在 `src/main/java/com/cncode/subagent/AgentTool.java:51` 定义，setter `setWorktreeManager` 在 `:98-100`
- [ ] `AgentTool.runSync` 在 `src/main/java/com/cncode/subagent/AgentTool.java:319-335` 用 `SecureRandom` + `HexFormat.formatHex(...).substring(0,7)` 生成 `agent-a<7hex>` slug
- [ ] `AgentTool.runSync` 在 `:388-399` 完成时按 `WorktreeChanges.hasChanges` 决定保留还是 `AgentWorktree.remove`
- [ ] `AgentTool.runAsTeammate` 在 `src/main/java/com/cncode/subagent/AgentTool.java:456-472` 创建 worktree + workdir + notice 注入，但**不**自动清理

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `grep -rn "EnterWorktreeTool" --include="*.java" src/` 在应用启动入口（`Main.java` 或 TUI 启动器）找到 `new EnterWorktreeTool(...)` 注册调用
- [ ] `grep -rn "ExitWorktreeTool" --include="*.java" src/` 在应用启动入口找到 `new ExitWorktreeTool(...)` 注册调用
- [ ] `grep -rn "WorktreeSessionStore.load" --include="*.java" src/` 在应用启动入口找到调用方
- [ ] `grep -rn "WorktreeSessionStore.restoreSession" --include="*.java" src/` 同时在 `EnterWorktreeTool` / `ExitWorktreeTool` / 启动恢复处找到调用
- [ ] `grep -rn "StaleCleanup.startCleanupLoop" --include="*.java" src/` 在应用启动入口找到调用方
- [ ] `grep -rn "AgentWorktree.create" --include="*.java" src/` 在 `src/main/java/com/cncode/subagent/AgentTool.java:325` 和 `:463` 找到两处调用（runSync + runAsTeammate）
- [ ] `grep -rn "AgentWorktree.buildNotice" --include="*.java" src/` 同上两处调用（runSync 在 `:329`，runAsTeammate 在 `:466`）
- [ ] `grep -rn "WorktreeChanges.hasChanges" --include="*.java" src/` 在 `src/main/java/com/cncode/subagent/AgentTool.java:391` 找到主流程调用方（决定 remove 还是保留）
- [ ] `grep -rn "AgentWorktree.remove" --include="*.java" src/` 在 `AgentTool.java:396` 和 `StaleCleanup.java:76` 找到调用方
- [ ] `grep -rn "WorktreeChanges.countChanges" --include="*.java" src/` 在 `src/main/java/com/cncode/tool/impl/ExitWorktreeTool.java:74` 找到唯一调用方（变更保护错误信息）
- [ ] `grep -rn "setWorktreeManager" --include="*.java" src/` 在应用启动入口找到注入调用（把 WorktreeManager 注入 AgentTool）

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] `./gradlew test --tests "com.cncode.worktree.*"` 通过（SlugValidator / WorktreeManager / PostCreationSetup / AgentWorktree / WorktreeChanges / StaleCleanup / WorktreeSessionStore 各对应测试 PASS）
- [ ] `./gradlew test --tests "com.cncode.subagent.*"` 通过（含 isolation 集成测试）
- [ ] `./gradlew test --tests "com.cncode.tool.impl.EnterWorktreeToolTest"` 和 `ExitWorktreeToolTest` 通过

## 4. 端到端验证

- [ ] **路径 A — 工具直接驱动**：用户对主 Agent 说"用 EnterWorktree 工具创建一个名叫 demo 的工作树" → LLM 调 `EnterWorktree({name:"demo"})` → 返回 `Created worktree at .../.cncode/worktrees/demo on branch worktree-demo. The session is now working in the worktree. Use ExitWorktree to leave mid-session.`；让 Agent 在 worktree 里创建 `hello.txt` 并 `git commit`；让 Agent 调 `ExitWorktree({action:"remove"})` → 因有未推送 commit 被变更保护拒绝，错误文本包含具体 file/commit 数和单复数；`ExitWorktree({action:"remove", discard_changes:true})` 强删成功；`ls .cncode/worktrees/` 看到 `demo/` 已消失。
- [ ] **路径 B — 子 Agent 自动隔离**：用户让主 Agent 在主目录建 `witness.txt`（内容 "original content from main agent"）→ 调 `Agent({subagent_type:"general-purpose", isolation:"worktree", description:"...", prompt:"把 witness.txt 改成 \"modified by isolated worker\"，然后 git 提交"})`；验证 `cat witness.txt` 主目录内容仍是 "original ..."；`cat .cncode/worktrees/agent-a*/witness.txt` 是修改后版本；若子 Agent 有 commit → 结果末尾出现 `"Worktree kept at ... (branch worktree-agent-a...) — has uncommitted changes or new commits."`；若无修改 → worktree 自动清理（`.cncode/worktrees/` 下 `agent-a*` 目录消失）。
- [ ] **持久化与 crash 恢复**：`EnterWorktree({name:"crashtest"})` 创建 worktree → `kill -9` 杀 JVM 进程 → `cat .cncode/worktree_session.json` 文件仍在并含 crashtest 会话；重启应用 → 启动期间 `WorktreeSessionStore.load + restoreSession` 将 session 写回全局 `volatile` 字段；下一次工具调用时 `WorktreeSessionStore.getCurrentSession()` 非 null。
- [ ] **变更保护单复数**：在 worktree 里建 1 个未提交修改 → `ExitWorktree({action:"remove"})` 返回 `"1 uncommitted file"`；建 2+ 个修改 → 返回 `"N uncommitted files"`；同样验证 commit 数的单复数（`"1 commit"` / `"N commits"`）。
- [ ] **后台清理保守不删**：手动在 `.cncode/worktrees/agent-aabcdef1/` 下建一个有未推送 commit 的目录（mtime 设为过期前）→ 等 cleanup loop 跑一轮（或手动调 `StaleCleanup.cleanup(repoRoot, Instant.now())` 测试）→ 该目录仍保留（L3 fail-closed 拦住）。
- [ ] **用户命名永不删**：在 `.cncode/worktrees/my-feature/` 下建一个目录（mtime 设为非常老）→ 跑 cleanup → 目录仍保留（L1 命名过滤拦住）。

## 5. 文档

- [ ] `docs/java/ch14/spec.md` 已按 ch13 风格写完（F1-F17 + N1-N8，无 file:line 代码标注）
- [ ] `docs/java/ch14/tasks.md` 已写，13 个 T 全部勾完（T1-T13）
- [ ] `docs/java/ch14/checklist.md` 已写并逐项验收
- [ ] commit 信息标注 `ch14`，新增代码的调用链已在 PR 描述或 commit message 里说明






# ch15: AgentTeam Spec

## 1. 背景

SubAgent（ch13）解决了一次性子任务的上下文隔离，但拓扑是星型：所有子 Agent 只能和主 Agent 通信，子 Agent 之间彼此看不见。当任务规模上来——四个模块同时重构、多角度并行调查 bug、一个 Agent 需要把发现告诉另一个——星型拓扑下主 Agent 成了信息中转瓶颈，子任务被迫串行。这一章把"长期协作团队"做成 CN Code 的一等概念：多个 Agent 组成 Team，并行干活、直接互发消息、共享任务列表，主 Agent 升级为 Team Lead 专职调度。Java 版本利用 JDK 21 虚拟线程跑 in-process 队员，外部后端则通过 `ProcessBuilder` 拉起 tmux / iTerm2 进程，由共享 `FileMailBox` 目录串联跨进程通信。

## 2. 目标

提供 `TeamManager` / `TeamManager.Team` / `TeamManager.Member` / `FileMailBox` / `SharedTaskStore` / `AgentNameRegistry` / `Coordinator` / `TeamTools.SendMessageTool` / `TeamTools.TeamCreateTool` / `TeamTools.TeamDeleteTool` 一整套类型与工具，让 LLM 在对话里：1) 调 `TeamCreate` 建团队（按环境自动选 tmux / in-process 后端），2) 后续通过 `Agent` 工具带 `team_name` 把队员加入团队，3) 队员之间通过 `SendMessage` 走 `FileMailBox` 互发消息、idle 后通知 Lead，4) Lead 借助 `Coordinator.ALLOWED_TOOLS` 收窄工具集进入纯调度模式。tmux 后端由 `SpawnDispatcher.buildTeammateCLI` 拼出 `cncode --teammate --team-name X --agent-name Y` 由独立进程跑 worker，和 Lead 共享同一份 mailbox 目录。

## 3. 功能需求

- F1: `TeamManager.TeamMode` 枚举包含 `IN_PROCESS / TMUX` 两档；`TeamManager.detectBackend()` 按 `TMUX` 环境变量 → `which tmux` 命中 → 退化到 `IN_PROCESS` 的优先级自动选择。
- F2: `TeamManager.Team` 持有 `name / mode / members LinkedHashMap / mailBox` 字段；`TeamManager.Member` 含 `name / agent / conv / active / thread` 字段，外部后端的 Member 由 `SpawnDispatcher.recordExternalMember` 创建，`agent` 与 `conv` 字段保持为 null。
- F3: `TeamManager` 提供 `createTeam` / `getTeam` / `deleteTeam` / `listTeams` / `closeAll` 同步方法；`Team` 暴露 `addMember` / `startMember` / `stopMember` / `stopAll` / `getMember` / `hasMember` / `memberNames` / `sendMessage`，全部用 `synchronized` 保护成员表。
- F4: `FileMailBox` 基于 `<baseDir>/<agentId>.json` 文件持久化消息；`send` / `readUnread` / `markAllRead` 三件套；并发安全靠 `<agentId>.json.lock` 文件锁，`Files.createFile` 抛 `FileAlreadyExistsException` 时重试（最多 10 次，5-100ms 随机退避），>10s 视为过期锁强制清理。
- F5: `FileMailBox.MailMessage` 记录类含 `from / text / timestamp / read / color / summary` 六个字段；便利构造器 `MailMessage(from, text)` 自动填 `Instant.now()` 时间戳、`read=false`、空 color/summary；`send` 落盘时强制把 `read` 置 false。
- F6: `SpawnDispatcher.spawnTeammate(SpawnConfig)` 统一入口按 `Team.mode` 分发到 in-process / tmux 两条路径，返回 `SpawnResult{mode, paneId}`。`IN_PROCESS` 模式调 `team.addMember` 注册并用 `Thread.startVirtualThread` 跑 `TeammateRunner.runInProcessTeammate`；`TMUX` 模式先把 task 写入对方 mailbox，再拼 CLI 调 `TmuxBackend.spawnTmuxTeammate`，最后 `recordExternalMember` 注册。
- F7: `SpawnDispatcher.buildTeammateCLI(teamName, memberName, workdir)` 用 `ProcessHandle.current().info().command()` 拿当前可执行路径；workdir 空时退化到 `System.getProperty("user.dir")`；输出 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted_team> --agent-name <quoted_member>`，所有变量经 `shellQuote` 处理。
- F8: `SpawnDispatcher.shellQuote(s)` 简单字符（`[a-zA-Z0-9_./-]+`）直接返回；含特殊字符时单引号包裹并把内嵌的 `'` 替换为 `'\''`（POSIX 标准转义）。
- F9: `TmuxBackend.spawnTmuxTeammate` 用 `tmux new-window -d -n <teamName>-<memberName> <cliCommand>` 创建后台窗口；命令返回码非 0 或超时（30s）抛 `RuntimeException("Failed to spawn tmux window: ...")`；`TmuxBackend.stopTmuxTeammate` 先 `send-keys C-c` 再 `kill-window`，best-effort 不重抛异常，失败仅 `log.fine`。
- F10: `ITermBackend.spawnITermTeammate` 用 `osascript -e <AppleScript>` 在 iTerm2 当前 window 创建 tab 并 `write text <cliCommand>`，内嵌双引号转义为 `\"`；30s 超时；`stopITermTeammate` 遍历所有 window 和 tab 找名字匹配的 close 掉，10s 超时、best-effort 失败静默。
- F11: `TeammateRunner.runInProcessTeammate(team, member, initialPrompt, addendum)` 队员主循环：先把 addendum 作为 system reminder 注入 → 调 `injectPendingMessages` 把未读邮件转 system reminder → 把 `initialPrompt` 加为 user message → 调 `member.agent.run(conv)` 跑一轮 → 通过 `drainAgentEvents` 转发事件 → 给 Lead 发 `[idle]` 通知 → 循环 `waitForNextPromptOrShutdown` 轮询邮箱，500ms 间隔，命中新消息加为 user message 跑下一轮，命中 shutdown 或线程中断退出。退出前置 `member.active=false`。
- F12: `TeammateRunner.LEAD_NAME = "lead"` / `SHUTDOWN_PREFIX = "[shutdown]"` / `IDLE_POLL_MS = 500` 三常量；`isShutdownRequest(text)` 用 `text.strip().startsWith(SHUTDOWN_PREFIX)` 判定；`createIdleNotification(memberName, reason)` 产出 `"[idle] <name>: <reason> (at <iso-instant>)"` 文本。
- F13: `TeammateRunner.drainLeadMailbox(teamMgr)` 扫所有团队的 Lead 收件箱，把未读消息按 `<team-notification team="X">\nfrom=Y: text\n...\n</team-notification>` 包装返回 `List<String>`，并把消息标记为已读；`teamMgr == null` 时返回 `List.of()`。
- F14: `TeammateRunner.buildTeammateAddendum(teamName, memberName, otherMembers)` 产出注入到队员对话顶端的 system reminder，告诉它身份、其他队友名字、必须通过 `SendMessage` 沟通、停止调用工具会自动发 idle 通知给 Lead。
- F15: `TeammateRunner.injectPendingMessages(team, memberName, conv)` 读 mailbox 未读，非空时拼 `"You have new messages:\n\nFrom <sender>: \n\n..."` 作为 system reminder 注入并 `markAllRead`，无未读直接返回。
- F16: `Coordinator.ALLOWED_TOOLS` 是 12 项白名单 `Set<String>`：`Agent / SendMessage / TaskCreate / TaskGet / TaskList / TaskUpdate / TeamCreate / TeamDelete / ReadFile / Glob / Grep / Bash`；`Coordinator.isCoordinatorTool(name)` 返回 set 命中布尔。写工具 `WriteFile / EditFile` 等被排除。
- F17: `TeamTools.SendMessageTool` 暴露 `to / content` 两个必填字段；`execute` 遍历所有团队找 `to` 这个 member 所在团队调 `team.sendMessage(senderName, to, content)` 投递；未匹配返 `recipient '<to>' not found in any team` 错误。
- F18: `TeamTools.TeamCreateTool` 暴露 `team_name` 必填、`description` 可选；同名时追加 `-2/-3/...` 后缀去重；调 `TeamManager.detectBackend()` + `teamMgr.createTeam`；Output 提示 `"Team \"X\" created (mode: Y). Use Agent tool with team_name=\"X\" to add teammates."`。
- F19: `TeamTools.TeamDeleteTool` 暴露 `team_name` 必填；不存在返错误；调 `teamMgr.deleteTeam`（内部 `stopAll` 中断所有 member 的虚拟线程）；返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单。
- F20: `AgentNameRegistry` 是单例（`getInstance()`），维护 `name → agentId` 映射；`register / resolve / unregister / listAll` 全部 `synchronized`；`resolve` 支持反向匹配——传入的字符串既可以是 name 也可以是 agentId，两边都查不到返 null。
- F21: `SharedTaskStore` 基于 `<teamDir>/tasks.json` 持久化 `SharedTask` 记录列表；`create / get / listTasks / update` 全部 `synchronized`；`update` 支持 `status / assignee` 覆盖以及 `addBlocks / addBlockedBy` 追加（不替换），自增 `id` 由 `AtomicInteger` 保证。

## 4. 非功能需求

- N1: FileMailBox 跨进程并发安全——tmux 启动的队友进程和 Lead 进程不共享 JVM 堆，必须靠文件锁保证写入原子性。锁文件 10 秒过期自动清理避免死锁。
- N2: 外部后端队员的初始任务必须在 spawn 之前写入 mailbox，因为 tmux 新进程启动到第一次 idle poll 期间无法接消息；先写后启即可保证第一次 poll 必命中。
- N3: In-process 队员的虚拟线程退出路径有三条：`Thread.currentThread().isInterrupted()` 为真、收到 shutdown 消息、`agent.run` 自然结束后无新消息。退出时必须置 `member.active=false`，否则 Lead 拿不到队员已停的状态。
- N4: Coordinator Mode 通过 `Coordinator.isCoordinatorTool` 在每轮迭代开头动态判定，而非一次性裁剪 registry。这样团队全部 Delete 后下一轮 Lead 自动恢复全工具集，无需重建 registry。
- N5: 队员的 `buildTeammateAddendum` 必须明确告诉 LLM "纯文本回复对队友不可见，最终结果必须通过 `SendMessage` 发给 Lead"——否则队员模型容易写一段汇报作为最后输出就结束，Lead 永远拿不到结果（只能看到 idle 通知）。
- N6: `SendMessage` 当前实现走"遍历所有团队找 `to` member"路径；若 Lead 不在任何 team.members 中，给 Lead 发消息会失败。Java 版的简化方案是发送时直接走当前 Sender 所在团队的 mailbox.send（绕过 hasMember 检查）。
- N7: `SpawnDispatcher.buildTeammateCLI` 必须把 `workdir / cncode / teamName / memberName` 都通过 `shellQuote` 包裹，否则空格或特殊字符的 workdir 路径会破坏 shell 解析；`shellQuote` 单引号转义遵循 POSIX `'\''` 标准。
- N8: `ITermBackend` 里的 AppleScript 字面量必须把内嵌的双引号转义为 `\"`，否则 `osascript -e` 解析失败；关闭流程是 best-effort，找不到 tab 不应报错（用户可能手动关掉了）。
- N9: `TeammateRunner.runInProcessTeammate` 应当使用 JDK 21 虚拟线程（`Thread.startVirtualThread`）而非平台线程，避免大团队时线程开销爆炸；mailbox 轮询采用 `Thread.sleep(IDLE_POLL_MS)` 而非自旋。
- N10: 测试运行时 `@TempDir` 必须用 `org.junit.jupiter.api.io.TempDir`，让 FileMailBox 写到测试临时目录，否则跑完测试会在仓库根残留 `.cncode/teams/` 目录；并发测试需用 `ExecutorService` + `CountDownLatch` 验证文件锁正确性。

## 5. 设计概要

- 核心类型:
 - `TeamManager`：全局团队注册表（`Map<String, Team>` + `synchronized` 方法），暴露 CRUD + `detectBackend` 静态方法。
 - `TeamManager.Team`：团队聚合，持有 `mode` 决定后端、`members LinkedHashMap` 注册表、`mailBox FileMailBox` 通信媒介，所有写方法 `synchronized`。
 - `TeamManager.Member`：队员元信息，in-process 模式 `agent + conv` 有值（LLM 跑在虚拟线程），tmux 模式两者为空、`thread` 也为空、靠 paneId（存储为 `name` 字段一部分）句柄。
 - `FileMailBox` + `FileMailBox.MailMessage`：文件锁 + JSON 数组的 mailbox 实现，跨进程共享同一目录，依赖 Jackson `ObjectMapper`。
 - `SpawnDispatcher.SpawnConfig` / `SpawnResult`：`spawnTeammate` 的入参/出参 record，把 in-process 与 tmux 后端的差异收敛到统一返回类型。
 - `Coordinator.ALLOWED_TOOLS`：12 项白名单 `Set<String>`，TUI 每轮按 `teamMgr.listTeams().isEmpty()` 决定是否启用过滤。
 - `SharedTaskStore`：JSON 持久化的任务表，提供 `id / title / description / status / assignee / blocks / blockedBy` 字段及 `addBlocks / addBlockedBy` 追加语义。
 - `AgentNameRegistry`：全局单例 `name → agentId` 映射，方便 SendMessage 通过名字寻址。
- 主流程（按生命周期）:
 - 创建：用户消息 → 主 Agent → LLM 调 `TeamCreate(team_name)` → `TeamManager.detectBackend()` 选模式 → `teamMgr.createTeam` 落到 `~/.cncode/teams/<name>/inboxes/` → 返回 mode 提示给 Lead。
 - Spawn 队员：Lead LLM 调 `Agent(team_name=X, name=Y, prompt=Z)` → AgentTool 识别 `team_name` 走 team 分支 → `SpawnDispatcher.spawnTeammate(SpawnConfig)` → 按 mode 分发。
 - In-process：`team.addMember` 注册成员 → `Thread.startVirtualThread` 跑 `TeammateRunner.runInProcessTeammate` → 队员在自己的虚拟线程里跑 agent loop。
 - 外部后端：先把初始任务写 mailbox → `buildTeammateCLI` 拼命令 → `TmuxBackend.spawnTmuxTeammate` 调 `tmux new-window` → 新进程跑 `cncode --teammate` worker 模式 → 第一次 idle poll 命中初始消息开始干活。
 - 通信：队员 → `SendMessage` 工具 → 找对方所在团队 → `team.sendMessage` → `mailBox.send` 写文件。队员收信走 `runInProcessTeammate` 顶端的 `injectPendingMessages` 或 `waitForNextPromptOrShutdown`。
 - Lead 感知：每轮 Lead Agent 开头调 `TeammateRunner.drainLeadMailbox` → 抽 Lead 邮箱所有未读 → 包成 `<team-notification>` system reminder 喂回 LLM。
 - Coordinator Mode：只要 `teamMgr.listTeams()` 非空，TUI 把 Lead 的工具调用拦截 → `Coordinator.isCoordinatorTool(name)` 判定 → 非白名单工具被过滤 → 全部团队清理后下一轮恢复全工具集。
 - Stop：`TeamDelete` 工具 → `teamMgr.deleteTeam` → `team.stopAll` 遍历 member 调 `thread.interrupt()`（in-process）或后端关闭脚本（tmux/iTerm）。
- 调用链（模块层级）:
 - TUI 装配 → 创建 `TeamManager` → 注册 `TeamCreateTool / TeamDeleteTool / SendMessageTool` 三个工具
 - Agent loop 每轮调 `TeammateRunner.drainLeadMailbox` 拼到下一轮系统提示
 - Lead 工具集过滤通过 `Coordinator.isCoordinatorTool` 在每次工具调用前判定
 - 外部工作进程入口 `CN Code.main` 增加 `--teammate` flag 早期拦截，命中走 worker bootstrap 不进 TUI（当前 `CNCode.java` 尚未实现此路径，是后续扩展点）
- 与其他模块的交互:
 - 依赖 `com.cncode.agent`（Agent / AgentEvent）、`com.cncode.conversation`（ConversationManager）、`com.cncode.llm`（LlmClient）、`com.cncode.tool`（Tool / ToolRegistry / ToolCategory / ToolResult）
 - 被 AgentTool（解析 `team_name` 参数）、TUI（注册工具 + 收件箱 drain + Coordinator filter）、`CN Code.main`（未来 worker 入口）调用

## 6. Out of Scope

- 不实现完整的 `TeammateInfo` 模型（`agentType / model / planModeRequired` 字段、planModeRequired 审批工作流）——本章仅做工具链层面的 Team / Member 骨架。
- 不实现 `plan_approval_response` / `shutdown_response` 结构化消息类型——目前仅 `[shutdown]` 文本前缀 + 纯文本消息两种。
- 不实现 `CN Code --teammate` worker 进程入口完整实现——`SpawnDispatcher.buildTeammateCLI` 已经能产出命令，但 `CNCode.java` 的 main 还没接 `parseTeammateFlags`，留作后续章节扩展。
- 不实现 `TeamManager.createTeamWith` 让外部 worker 进程注册本地构造的 Team——当前 worker 入口未实现，所以此扩展点不必要。
- 不实现 iTerm2 后端在 `SpawnDispatcher` 内的分支——`ITermBackend` 类已经存在但 `spawnTeammate` 的 switch 没接 `ITERM` 分支；本章先保证 tmux + in-process 两档可用。
- 不实现共享任务依赖图的 BFS 校验/循环依赖检测——`SharedTaskStore.update` 只做字段追加，不验证 `blocks/blockedBy` 是否构成环。
- 不实现"协调模式四阶段工作流"系统提示词注入（Research / Synthesis / Implementation / Verification）——`Coordinator` 仅做工具收窄，不做提示词增强。
- 不实现"配置持久化到 ~/.cncode/teams/<name>/config.json" 的团队元数据——只持久化邮箱 JSON 和 tasks.json，Team 实例本身随 JVM 退出消失。
- 不实现 Worktree 团队层面的"收敛阶段 Lead 用 Bash 跑 git merge"自动化——合并由 Lead LLM 自己用 Bash 工具完成，本章不做封装。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。


# ch15: AgentTeam Tasks

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 定义 TeamManager / Team / Member / TeamMode
- 影响文件: `src/main/java/com/cncode/teams/TeamManager.java`（`TeamMode` 枚举 @ 21；`teams` map @ 23；`createTeam` @ 25-29；`getTeam` @ 31-33；`deleteTeam` @ 35-40；`listTeams` @ 42-44；`closeAll` @ 46-51；`detectBackend` @ 53-62；`teamsBaseDir` @ 66-68；`Team` 内部类 @ 70-134；`Member` 内部类 @ 136-151）
- 依赖任务: 无
- 完成标准: `TeamMode` 枚举含 `IN_PROCESS / TMUX`；`Team` 字段 `name / mode / members / mailBox` 齐全；`Team` 方法 `addMember / startMember / stopMember / stopAll / getMember / hasMember / memberNames / sendMessage` 全部 `synchronized`；`Member` 字段 `name / agent / conv / active / thread` 齐全（`active / thread` volatile）；`TeamManager` 顶层 CRUD 方法全部 `synchronized`；`detectBackend` 优先级 `TMUX env → which tmux → IN_PROCESS`。
- [ ] 完成

## T2: 实现 FileMailBox（JSON + 文件锁）
- 影响文件: `src/main/java/com/cncode/teams/FileMailBox.java`（`MailMessage` record @ 16-21；常量 `MAPPER / MAX_RETRIES / MIN_SLEEP_MS / MAX_SLEEP_MS` @ 23-26；构造器 @ 30-35；`inboxPath` @ 37-39；`lockPath` @ 41-43；`send` @ 45-51；`readUnread` @ 53-60；`markAllRead` @ 62-70；`withLock` @ 76-112；`readInbox` @ 114-123；`writeInbox` @ 125-131）
- 依赖任务: 无
- 完成标准: 每个收件人对应 `<baseDir>/<agentId>.json`；`MailMessage` record 含 6 字段且便利构造器自动填 timestamp/read=false；`send` 落盘时把 `read` 强制置 false；`markAllRead` 用 `withLock` 批量翻转所有消息为 read=true；并发安全靠 `<agentId>.json.lock` 文件用 `Files.createFile` 抛 `FileAlreadyExistsException` 时重试，最多 10 次 5-100ms 随机退避，>10s 视为过期锁清理；`withLock` 在 fn 返回后 finally 删锁文件；Jackson 用 `ObjectMapper` 默认配置 + `TypeReference<List<MailMessage>>`。
- [ ] 完成

## T3: 实现 Tmux 后端
- 影响文件: `src/main/java/com/cncode/teams/TmuxBackend.java`（`spawnTmuxTeammate` @ 16-27；`stopTmuxTeammate` @ 29-41）
- 依赖任务: T1
- 完成标准: `spawnTmuxTeammate` 用 `ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand)` 创建后台窗口；30s 超时，非 0 退出码或超时抛 `RuntimeException`；`stopTmuxTeammate` 先 `send-keys C-c` 等 5s + `Thread.sleep(200)` 再 `kill-window`，best-effort 失败仅 `log.fine` 不重抛。
- [ ] 完成

## T4: 实现 iTerm2 后端
- 影响文件: `src/main/java/com/cncode/teams/ITermBackend.java`（`spawnITermTeammate` @ 16-40；`stopITermTeammate` @ 42-61）
- 依赖任务: T1
- 完成标准: `spawnITermTeammate` 用 `osascript -e <AppleScript>` 在当前 window 创建 tab 设 name 并 `write text <cliCommand>`，内嵌双引号转义为 `\"`；30s 超时；`stopITermTeammate` AppleScript 遍历所有 window 的 tab 找 name 匹配的 close 掉，10s 超时、best-effort 失败仅 `log.fine`。
- [ ] 完成

## T5: 实现队员主循环 TeammateRunner.runInProcessTeammate
- 影响文件: `src/main/java/com/cncode/teams/TeammateRunner.java`（常量 `LEAD_NAME / SHUTDOWN_PREFIX / IDLE_POLL_MS` @ 16-18；`runInProcessTeammate` @ 26-66；`waitForNextPromptOrShutdown` @ 142-170；`drainAgentEvents` @ 172-187）
- 依赖任务: T1, T2
- 完成标准: 主循环 7 步——1) addendum 非空时加为 system reminder；2) `injectPendingMessages` 把未读邮件转 system reminder；3) `addUserMessage(initialPrompt)`；4) `member.agent.run(conv)` 拿 event queue；5) `drainAgentEvents` 转发到 eventOut；6) `sendMessage(self, LEAD, "[idle]...")` 发 idle 通知；7) 进入 while 循环 `waitForNextPromptOrShutdown` 轮询，shutdown 或线程中断退出，命中新消息加为 user message 继续下一轮。退出前置 `member.active=false`。`drainAgentEvents` 收到 `LoopComplete` 或 `ErrorEvent` 即返回。
- [ ] 完成

## T6: 实现 Lead-side 通信原语
- 影响文件: `src/main/java/com/cncode/teams/TeammateRunner.java`（`drainLeadMailbox` @ 72-92；`buildTeammateAddendum` @ 97-109；`injectPendingMessages` @ 114-127；`isShutdownRequest` @ 129-131；`createIdleNotification` @ 133-136）
- 依赖任务: T1, T2
- 完成标准: `drainLeadMailbox(null)` 返 `List.of()`；非空时遍历所有团队读 Lead 邮箱，按 `<team-notification team="X">\nfrom=Y: text\n...\n</team-notification>` 包装返字符串数组，并把读过的标记为已读。`buildTeammateAddendum` 文本必须含队员名、其他队友名、"通过 SendMessage 沟通"、"停止调用工具自动发 idle"四条信息。`injectPendingMessages` 在有未读时拼 `"You have new messages:\n\n..."` system reminder 并 `markAllRead`，无未读直接返回。`isShutdownRequest` 用 `text.strip().startsWith(SHUTDOWN_PREFIX)` 判定。`createIdleNotification` 产出 `"[idle] <name>: <reason> (at <iso-instant>)"`。
- [ ] 完成

## T7: 实现 SpawnDispatcher 统一入口
- 影响文件: `src/main/java/com/cncode/teams/SpawnDispatcher.java`（`SpawnConfig` record @ 15-24；`SpawnResult` record @ 26-29；`spawnTeammate` @ 33-61；`recordExternalMember` @ 80-88）
- 依赖任务: T1, T3, T5
- 完成标准: `spawnTeammate` switch `team.getMode()` 分发；`IN_PROCESS` 路径调 `team.addMember` 注册（可选 `setWorkDir(workdir)`） → 置 `active=true` → `Thread.startVirtualThread` 跑 `runInProcessTeammate` → 返 `SpawnResult(IN_PROCESS, null)`；`TMUX` 路径先把 task 写入对方 mailbox（用 `team.sendMessage(LEAD_NAME, memberName, task)`） → `buildTeammateCLI` 拼命令 → `TmuxBackend.spawnTmuxTeammate` 拿 paneId → `recordExternalMember` 注册占位 member → 返 `SpawnResult(TMUX, paneId)`；未知 mode 抛 `IllegalStateException`。
- [ ] 完成

## T8: 实现 BuildTeammateCLI + shellQuote
- 影响文件: `src/main/java/com/cncode/teams/SpawnDispatcher.java`（`buildTeammateCLI` @ 67-73；`shellQuote` @ 75-78）
- 依赖任务: T7
- 完成标准: `buildTeammateCLI` 用 `ProcessHandle.current().info().command().orElse("cncode")` 拿当前可执行；workdir 空时默认 `System.getProperty("user.dir")`；返回 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted_team> --agent-name <quoted_member>`。`shellQuote` 简单字符（`[a-zA-Z0-9_./-]+` 正则命中）直接返回原串，含特殊字符时单引号包裹并把内嵌 `'` 替换为 `'\''`。
- [ ] 完成

## T9: 实现 Coordinator Mode 工具白名单
- 影响文件: `src/main/java/com/cncode/teams/Coordinator.java`（`ALLOWED_TOOLS` @ 19-32；`isCoordinatorTool` @ 34-36）
- 依赖任务: 无
- 完成标准: 12 项白名单 `Set<String>`：`Agent / SendMessage / TaskCreate / TaskGet / TaskList / TaskUpdate / TeamCreate / TeamDelete / ReadFile / Glob / Grep / Bash`；`isCoordinatorTool(name)` 返回 set.contains 布尔（写工具 `WriteFile / EditFile` 等不在内）。
- [ ] 完成

## T10: 实现 SendMessage / TeamCreate / TeamDelete 三个工具
- 影响文件: `src/main/java/com/cncode/teams/TeamTools.java`（`SendMessageTool` @ 20-72；`TeamCreateTool` @ 76-128；`TeamDeleteTool` @ 132-181）
- 依赖任务: T1
- 完成标准:
 - `SendMessageTool.execute`：`to/content` 必填；遍历所有团队找 `to` 这个 member 所在团队调 `team.sendMessage(senderName, to, content)` 投递；未匹配返 `recipient '<to>' not found in any team` 错误；schema 含 `to / content` 两个 string 必填字段。
 - `TeamCreateTool.execute`：`team_name` 必填；同名时追加 `-2/-3/...` 后缀去重；调 `TeamManager.detectBackend()` + `teamMgr.createTeam`；Output 含 `"Team \"X\" created (mode: Y). Use Agent tool with team_name=\"X\" to add teammates."`。
 - `TeamDeleteTool.execute`：`team_name` 必填；不存在返错误；调 `teamMgr.deleteTeam`（内部 `stopAll` 中断所有 member）；返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单。
- [ ] 完成

## T11: 实现 AgentNameRegistry 单例
- 影响文件: `src/main/java/com/cncode/teams/AgentNameRegistry.java`（`INSTANCE` @ 12；`nameToId` map @ 13；`getInstance` @ 17；`register / resolve / unregister / listAll` @ 19-35）
- 依赖任务: 无
- 完成标准: 单例模式（`private static final INSTANCE`，私有构造）；`nameToId` 用 `LinkedHashMap` 保证遍历顺序；`register / resolve / unregister / listAll` 全部 `synchronized`；`resolve` 先查 name → id，未命中时检查 `containsValue(input)` 返回 input 本身（反向 id 寻址），都不命中返 null；`listAll` 返新建 `LinkedHashMap` 副本避免外部修改。
- [ ] 完成

## T12: 实现 SharedTaskStore
- 影响文件: `src/main/java/com/cncode/teams/SharedTaskStore.java`（`SharedTask` record @ 21-32；常量 `MAPPER` + 字段 `filePath / nextId / tasks` @ 34-37；构造器 @ 39-42；`create` @ 44-50；`get` @ 52-54；`listTasks` @ 56-61；`update` @ 63-85；`load` @ 87-95；`save` @ 97-102）
- 依赖任务: 无
- 完成标准: `SharedTask` record 含 `id / title / description / status / assignee / blocks / blockedBy / createdBy` 字段，并提供 `withStatus / withAssignee` 不可变更新；`@JsonIgnoreProperties(ignoreUnknown=true)` 注解保证向前兼容；构造器 `new SharedTaskStore(teamDir)` 自动 load 已有 `tasks.json`；`create` 用 `AtomicInteger` 自增 id；`listTasks` 支持按 status/assignee 过滤；`update` 用记录类 wither 模式产新对象，`addBlocks/addBlockedBy` 是追加（用新建 ArrayList 拷贝旧值后 addAll）；全部 mutating 方法 `synchronized`；save 用 `MAPPER.writerWithDefaultPrettyPrinter()` 美化输出。
- [ ] 完成

## T13: 实现 FileMailBox 单元测试
- 影响文件: `src/test/java/com/cncode/teams/FileMailBoxTest.java`（`sendCreatesFileWithMessage` @ 17-29；`readUnreadReturnsOnlyUnread` @ 31-41；`markAllReadMakesUnreadEmpty` @ 43-53；`nonexistentAgentReturnsEmpty` @ 55-60；`teamSendMessageIntegration` @ 62-74）
- 依赖任务: T1, T2
- 完成标准: 用 `@TempDir` 把 inbox 重定向到测试临时目录，避免污染仓库根；5 个用例覆盖——1) `send` 落盘后文件含 `from / text / read=false` 三字段；2) 连续 `send` 后 `readUnread` 返所有未读；3) `markAllRead` 后 `readUnread` 为空；4) 不存在的 agentId 返 `readUnread` 空列表；5) 集成测试创建 `Team` + 单独 mailbox 验证 send/read 完整流程。
- [ ] 完成

## T14: 实现 AgentTool team_name 分支
- 影响文件: `src/main/java/com/cncode/agents/AgentTool.java`（新增 `teamMgr` 字段；`execute` 解析 `team_name` 参数；当 `team_name != null && teamMgr != null` 走 team 分支调 `SpawnDispatcher.spawnTeammate`；当 in-process 模式启虚拟线程消费 `eventOut` queue 转发到 `progressCh`）
- 依赖任务: T6, T7
- 完成标准: `AgentTool` 新增 `private TeamManager teamMgr` 字段及 setter；`execute` 在解析完 `subagent_type / prompt` 后检查 `team_name`，命中且 `teamMgr != null` 即走 team 分支；team 分支校验团队存在 + 同 team 同名 + 解析子工具池 + 可选 worktree + `TeammateRunner.buildTeammateAddendum` 构造 addendum + `SpawnDispatcher.spawnTeammate` 拿 result；in-process 模式启虚拟线程 `drainTeammateEvents` 消费事件流转 `SubAgentProgress` 喷进 `progressCh`；Output 含 backend hint 和 SendMessage 使用提示。
- [ ] 完成

## T15: TUI 接入
- 影响文件: `src/main/java/com/cncode/tui/CNCodeModel.java`（`teamMgr` 字段；`registerAgentTools` 内创建 `TeamManager` 并注册三件套工具 + 注入 `AgentTool.teamMgr`；Lead 每轮迭代调 `TeammateRunner.drainLeadMailbox(teamMgr)` 拼到下一轮 system reminder；Lead Agent 工具调用前用 `Coordinator.isCoordinatorTool` 过滤）
- 依赖任务: T6, T9, T10, T14
- 完成标准:
 1. `CNCodeModel.teamMgr` 字段声明；
 2. `registerAgentTools`（或等价初始化方法）创建 `TeamManager` → 注册 `TeamCreateTool / TeamDeleteTool / SendMessageTool` → `AgentTool.setTeamMgr(teamMgr)`；
 3. Lead 每轮迭代开头调 `TeammateRunner.drainLeadMailbox(teamMgr)` 把 `<team-notification>` 字符串拼到要喂给模型的 system reminder；
 4. Lead 工具调用过滤：`teamMgr.listTeams().isEmpty()` 为空时放行全部，非空时 `Coordinator.isCoordinatorTool(name)` 判定；
 5. 程序退出 finally 块调 `teamMgr.closeAll()` 确保所有虚拟线程被中断。
- [ ] 完成

## T16: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T1-T15
- 完成标准:
 - `./gradlew build` 通过；
 - `./gradlew test` 通过（覆盖至少 `FileMailBoxTest` 5 个用例 + `TeamManagerTest` / `SpawnDispatcherTest` / `TeammateRunnerTest` / `CoordinatorTest` 共 15+ 用例，含 detectBackend 两档优先级、SendMessage 路由、SpawnDispatcher 校验、shellQuote、drainLeadMailbox、isShutdownRequest、createIdleNotification 等）；
 - 主流程接线验证：`rg "teamMgr|TeammateRunner|Coordinator\." src/main/java/com/cncode/tui` 命中 TUI 装配点；`rg "TeamMgr|teamMgr" src/main/java/com/cncode/agents/AgentTool.java` 看到 team 分支被 execute 调用。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13 / [ ] T14 / [ ] T15 / [ ] T16


# ch15: AgentTeam Checklist

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 枚举 `TeamManager.TeamMode` 在 `src/main/java/com/cncode/teams/TeamManager.java:21` 存在，含 `IN_PROCESS / TMUX` 两档
- [ ] 内部类 `TeamManager.Team` 在 `TeamManager.java:70-134` 存在，字段 `name / mode / members / mailBox` 齐全，写方法全部 `synchronized`
- [ ] 内部类 `TeamManager.Member` 在 `TeamManager.java:136-151` 存在，字段 `name / agent / conv / active / thread`（后两者 `volatile`）齐全
- [ ] 静态方法 `TeamManager.detectBackend` 在 `TeamManager.java:53-62` 实现优先级 `TMUX env → which tmux → IN_PROCESS`
- [ ] Record 类 `FileMailBox.MailMessage` 在 `FileMailBox.java:16-21` 含 6 字段 `from / text / timestamp / read / color / summary`，便利构造器自动填 timestamp/read=false
- [ ] `FileMailBox.withLock` 在 `FileMailBox.java:76-112` 使用 `Files.createFile` 抛 `FileAlreadyExistsException` 时重试，10 次 5-100ms 随机退避，>10s 过期清理
- [ ] Record 类 `SpawnDispatcher.SpawnConfig / SpawnResult` 在 `SpawnDispatcher.java:15-29` 存在
- [ ] 常量 `TeammateRunner.LEAD_NAME = "lead"` / `SHUTDOWN_PREFIX = "[shutdown]"` / `IDLE_POLL_MS = 500L` 在 `TeammateRunner.java:16-18`
- [ ] `Coordinator.ALLOWED_TOOLS` `Set<String>` 在 `Coordinator.java:19-32` 含 12 项白名单（写工具 `WriteFile / EditFile` 等被排除）
- [ ] `TeammateRunner.runInProcessTeammate` 在 `TeammateRunner.java:26-66` 主循环七步齐全：addendum 注入 → injectPendingMessages → addUserMessage → agent.run + drainAgentEvents → idle 通知 → while 循环 waitForNextPromptOrShutdown
- [ ] `SpawnDispatcher.buildTeammateCLI` 在 `SpawnDispatcher.java:67-73` 输出 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted> --agent-name <quoted>`；`shellQuote` 在 `:75-78` 简单字符直接返回、特殊字符单引号 POSIX 转义
- [ ] `TeammateRunner.buildTeammateAddendum` 在 `TeammateRunner.java:97-109` 文本包含 "member of team"、"Your name is"、"SendMessage tool"、"idle notification will be sent to the lead automatically" 四个关键信息
- [ ] `TeammateRunner.drainLeadMailbox` 在 `TeammateRunner.java:72-92` null 安全（`teamMgr == null` 返 `List.of()`）、读完后调 `markAllRead`、输出格式 `<team-notification team="X">\n...\n</team-notification>`
- [ ] `TeamTools.SendMessageTool.execute` 在 `TeamTools.java:54-71` 遍历所有团队找 `to` member 投递，未匹配返 `recipient '<to>' not found in any team` 错误
- [ ] `TeamTools.TeamCreateTool.execute` 在 `TeamTools.java:108-127` 同名冲突自动追加 `-2/-3/...` 后缀去重
- [ ] `TeamTools.TeamDeleteTool.execute` 在 `TeamTools.java:163-180` 返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单
- [ ] `AgentNameRegistry` 在 `AgentNameRegistry.java:10-36` 是单例（`getInstance`），全部方法 `synchronized`；`resolve` 支持反向 id 寻址
- [ ] `SharedTaskStore` 在 `SharedTaskStore.java:18-103` 实现 `create / get / listTasks / update`，全部 `synchronized`；`update` 用 wither 模式产新 record，`addBlocks/addBlockedBy` 追加而非替换

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `rg "new TeamManager\\(\\)" src/main/java/com/cncode/tui` 在 TUI 装配代码找到 `TeamManager` 实例化点
- [ ] `rg "TeamCreateTool|TeamDeleteTool|SendMessageTool" src/main/java/com/cncode/tui` 在 TUI 找到三个工具注册点
- [ ] AgentTool 注入 `teamMgr` 的代码在 TUI 装配处可见（`agentTool.setTeamMgr(teamMgr)` 或构造器注入）
- [ ] `rg "drainLeadMailbox" src/main/java/com/cncode/tui` 命中 Lead 每轮迭代调用点（把 `<team-notification>` 注入下一轮 system reminder）
- [ ] `rg "Coordinator.isCoordinatorTool" src/main/java/com/cncode/tui` 命中 Lead 工具调用过滤点
- [ ] `CNCodeModel.teamMgr` 字段在 TUI 主模型类中声明
- [ ] `rg "teamMgr" src/main/java/com/cncode/agents/AgentTool.java` 看到 `AgentTool` 的 `team_name` 分支调用 `SpawnDispatcher.spawnTeammate`
- [ ] `rg "SpawnDispatcher.spawnTeammate" src/main/java/com/cncode/agents` 命中 in-process 模式下虚拟线程消费 eventOut 的 `drainTeammateEvents` 调用
- [ ] 程序退出 finally 块调 `teamMgr.closeAll()` 确保所有虚拟线程被中断

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] `./gradlew test` 通过（覆盖至少 15 个用例：FileMailBoxTest 5 个 + TeamManagerCRUD / DetectBackendFallback / DetectBackendPrefersTmuxWhenInside / SendMessageToolRoutes / TeamCreateNameCollision / TeamDeleteStopsMembers / IsShutdownRequest / CreateIdleNotification / DrainLeadMailbox / DrainLeadMailboxNullSafe / ShellQuote / BuildTeammateCLIFormat / SpawnDispatcherInProcess / SpawnDispatcherTmuxValidation / CoordinatorAllowedTools / SharedTaskStoreCRUD / AgentNameRegistryRoundtrip）
- [ ] `./gradlew check` 无警告（含 SpotBugs / Checkstyle 若启用）
- [ ] 测试运行不在仓库根残留 `.cncode/teams/` 目录（`@TempDir` 重定向到 tmp）
- [ ] FileMailBox 并发测试用 `ExecutorService` + `CountDownLatch` 验证文件锁正确性，多线程并发 `send` 后 `readUnread` 数量与发送次数一致

## 4. 端到端验证

- [ ] 注册路径：TUI 启动后装配代码创建 `TeamManager` 并把 `TeamCreate / TeamDelete / SendMessage` 三件套放入 registry；用户向 Lead 说 "create a team to refactor X" → LLM 调 `TeamCreate(team_name="refactor-X")` → `detectBackend()` 选模式 → Output 返回 `"Team \"refactor-X\" created (mode: ...). Use Agent tool with team_name=\"refactor-X\" to add teammates."`
- [ ] Spawn 路径：Lead 继续说 "spawn alice to do data layer" → LLM 调 `Agent(team_name="refactor-X", name="alice", prompt="...")` → `AgentTool.execute` 识别 `team_name` 分支调 `SpawnDispatcher.spawnTeammate(IN_PROCESS|TMUX)` → 队员开始干活
- [ ] 通信路径：队员 alice 通过 `SendMessage(to="bob", content="...")` 给 bob 写 mailbox → bob 下一轮 idle poll 拿到消息作为 user message 注入对话
- [ ] Lead 感知路径：每个队员 turn 结束写 `[idle] alice: completed initial task (at <iso>)` 通知到 Lead 邮箱 → Lead 下一轮迭代调 `drainLeadMailbox` 抽出 `<team-notification team="refactor-X">\nfrom=alice: [idle] ...\n</team-notification>` 注入 Lead 上下文
- [ ] Coordinator Mode 路径：团队存活期间 Lead 每轮工具调用前 `Coordinator.isCoordinatorTool` 过滤，调用 `WriteFile` / `EditFile` 会被拒绝；`TeamDelete` 清空所有团队后下一轮恢复全工具集
- [ ] Tmux 后端：`TMUX` env 非空时 `detectBackend` 返 `TMUX` → spawn 时先把 task 写 mailbox → `tmux new-window -d` 拉起新窗口跑 `cncode --teammate ...` → 子进程加载同一 mailbox 目录 → 第一次 idle poll 拿到初始任务开始干活
- [ ] iTerm 后端（备用）：`ITermBackend` 类已实现 `spawnITermTeammate / stopITermTeammate`，可通过手工调用验证 AppleScript 解析正确（`SpawnDispatcher` 当前未接此分支，作为后续扩展点）
- [ ] 关闭路径：`TeamDelete(team_name="refactor-X")` → `teamMgr.deleteTeam` → `team.stopAll` 遍历 member 调 `thread.interrupt()`（in-process）或 `TmuxBackend.stopTmuxTeammate`（tmux）→ 全部清理后 Lead 下轮恢复全工具集
- [ ] JVM 退出路径：`teamMgr.closeAll()` 在 TUI 程序 finally 块调用，所有虚拟线程被中断、所有 tmux 窗口被关闭

## 5. 文档

- [ ] `docs/java/ch15/spec.md` 已写
- [ ] `docs/java/ch15/tasks.md` 已写，16 个 T 全部勾完
- [ ] `docs/java/ch15/checklist.md` 已写并逐项验收
- [ ] commit 信息标注 `ch15` 与三件套关闭状态（待用户确认后由人或 CI 触发）


