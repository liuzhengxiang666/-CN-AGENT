# CN Code 单工具 Agent 能力技术设计

## 1. 架构概览

新增工具系统和单工具 Agent 编排层，在现有聊天链路上增加：

1. 模型收到用户请求。
2. Provider 以支持 tool call 的方式调用模型。
3. 如果模型直接返回文本，照常展示。
4. 如果模型请求一个工具，Agent 执行该工具。
5. Agent 将工具结果加入对话历史。
6. Agent 再次调用模型，但禁止继续使用工具。
7. 模型生成最终回复。

本阶段优先支持 OpenAI-compatible Provider，对 DeepSeek 生效。Anthropic Provider 保留扩展点。

设计原则：采用 Java ch03 工具系统的核心骨架，但保持当前阶段轻量。工具系统只负责“工具是什么、如何注册、如何执行、如何返回结构化结果”；单工具 Agent 只负责一次用户请求最多执行一个工具。多轮 ReAct Agent Loop、ToolSearch、AskUser、Deferred 工具披露、权限审批和 Hook 都不进入本阶段。

## 2. 模块划分

### tool

新增工具核心模块：

- `Tool`
- `ToolCategory`
- `ToolMetadata`
- `ToolParameter`
- `ToolResult`
- `ToolCall`
- `ToolRegistry`
- `ToolExecutionContext`
- `ToolExecutor`

职责：

- 定义统一工具接口。
- 标记工具分类：`READ` / `WRITE` / `COMMAND`。
- 管理工具元信息和参数 Schema。
- 执行工具并返回结构化结果。
- 提供 LLM Provider 可用的工具定义列表。
- 稳定输出 OpenAI-compatible tools/function schema。

### tool.builtin

新增六个内置工具：

- `ReadFileTool`
- `WriteFileTool`
- `ReplaceFileTool`
- `RunCommandTool`
- `FindFilesTool`
- `SearchCodeTool`

职责：

- 实现项目工作目录内的文件、命令和搜索能力。
- 保证路径安全。
- 处理超时、截断和错误结果。

### agent

新增单工具 Agent 编排模块：

- `SingleToolAgent`
- `AgentEvent`
- `AgentEventHandler`

职责：

- 负责一次用户请求的完整执行流程。
- 接收模型文本 delta、工具调用、工具结果、最终回复。
- 限制每次用户请求最多执行一个工具。
- 向 Web UI/REPL 输出用户可见事件。

### provider

扩展现有 Provider 抽象：

新增或调整：

- `ChatRequest` 增加工具定义和是否允许工具调用。
- `StreamHandler` 增加工具调用相关回调，或新增 `ProviderStreamEvent`。
- `OpenAiProvider` 支持 OpenAI-compatible tools 请求格式。
- `OpenAiProvider` 解析流式 tool call 的 name 和 arguments 片段。
- `AnthropicProvider` 暂不完整实现 tool use，返回不支持或忽略工具定义。

## 3. 工具接口设计

### Tool

```text
interface Tool {
    ToolMetadata metadata();
    ToolCategory category();
    ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}
```

### ToolCategory

```text
enum ToolCategory {
    READ,
    WRITE,
    COMMAND
}
```

本阶段 `ToolCategory` 不驱动并发调度，只作为后续权限、审批和并发策略的基础元信息。

### ToolMetadata

包含：

- `name`
- `description`
- `parametersJsonSchema`

`description` 需要写清该工具的使用场景。例如读文件工具应说明：当用户要求查看、总结、引用本地文件内容时必须使用该工具，不应凭空猜测文件内容。

### ToolResult

包含：

- `success`
- `toolName`
- `summary`
- `output`
- `error`

并提供转换为模型可读 JSON 文本的方法。

### ToolExecutionContext

包含：

- `workspaceRoot`
- `timeout`
- `maxOutputChars`

## 4. 六个工具参数设计

### read_file

参数：

- `path`

行为：

- 读取工作目录内文本文件。
- 输出文件内容，超过限制则截断。
- 分类为 `READ`。
- 描述强调：查看、读取、总结、引用本地文件内容时使用。

### write_file

参数：

- `path`
- `content`

行为：

- 在工作目录内写入完整内容。
- 必要时创建父目录。
- 路径越界则拒绝。
- 分类为 `WRITE`。

### replace_file

参数：

- `path`
- `old_text`
- `new_text`

行为：

- 读取文件。
- 统计 `old_text` 出现次数。
- 0 次或多次返回错误。
- 1 次时替换并写回。
- 分类为 `WRITE`。

### run_command

参数：

- `command`
- `timeout_seconds`

行为：

- 在工作目录内执行命令。
- 返回 stdout、stderr、exit_code。
- 超时返回错误。
- 分类为 `COMMAND`。

### find_files

参数：

- `pattern`

行为：

- 在工作目录内按 glob 模式查找文件。
- 返回相对路径列表。
- 结果过长截断。
- 分类为 `READ`。

### search_code

参数：

- `query`
- `regex`

行为：

- 在工作目录内搜索文本文件。
- 返回匹配文件、行号、片段。
- `regex` 为 true 时按正则搜索，否则按普通文本搜索。
- 结果过长截断。
- 分类为 `READ`。

## 5. 路径安全设计

所有文件工具通过统一 Path 工具方法解析路径：

1. `workspaceRoot.resolve(userPath).normalize()`
2. 检查结果是否 `startsWith(workspaceRoot)`
3. 不满足则拒绝

默认 `workspaceRoot` 为当前进程工作目录。

## 6. OpenAI-compatible tool call 设计

请求模型时，`OpenAiProvider` 在请求体中加入：

- `tools`
- `tool_choice: auto`

注册中心负责把内部工具元信息转换为 OpenAI-compatible 格式：

```text
{
  "type": "function",
  "function": {
    "name": "...",
    "description": "...",
    "parameters": { ... }
  }
}
```

如果后续 Provider 需要不同 schema 形状，应在注册中心或 Provider 边界转换，不让每个 Tool 写多份 schema。

流式解析：

- 解析 delta 中的 `tool_calls`
- 拼接 tool call id、name、arguments`
- 当 finish_reason 为 `tool_calls` 时，通知 Agent 工具调用已完成

第二次模型调用：

- 将 assistant tool_call 消息和 tool result 消息加入历史
- 禁止 tools 或设置不允许工具调用
- 要求模型生成最终文本

## 7. Web UI 事件设计

`SingleToolAgent` 向 Web UI 产生事件：

- `delta`：模型文本片段
- `tool_start`：工具开始，包含工具名
- `tool_result`：工具结束，包含 success 和 summary
- `error`：错误
- `done`：完成

Web 前端显示工具过程为系统消息或工具状态块。

## 8. 错误处理

- 工具不存在：结构化错误结果。
- 参数缺失：结构化错误结果。
- 路径越界：结构化错误结果。
- 命令超时：结构化错误结果。
- Provider 解析失败：显示错误并结束本轮。
- 第二次模型调用继续请求工具：拒绝并提示本阶段只支持单工具。

## 9. 风险与缓解

- OpenAI-compatible 不同服务 tool call 流式格式略有差异：先按 OpenAI Chat Completions tool_calls 格式实现，并用 DeepSeek 验证。
- JSON 参数碎片可能不完整：只有 finish_reason 为 tool_calls 后再解析完整 JSON。
- 手写 JSON 解析能力有限：本阶段可以先实现针对 tool 参数的简单对象解析，后续再换 Jackson。
- 命令执行风险较高：限制工作目录、超时、输出截断，并把错误结构化返回。
- 工具描述过弱会导致模型不调用工具：为六个核心工具补充强行为描述，并在 Agent 系统提示中强调需要本地信息时必须调用工具。
- 高级模板能力一次性引入会拖慢进度：ToolSearch、AskUser、Deferred、并发批次、权限和 Hook 明确放到后续阶段。
