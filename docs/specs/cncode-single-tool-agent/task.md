# CN Code 单工具 Agent 能力任务拆解

## 1. 文件清单

预计创建或修改：

- `src/main/java/cncode/tool/Tool.java`
- `src/main/java/cncode/tool/ToolCategory.java`
- `src/main/java/cncode/tool/ToolMetadata.java`
- `src/main/java/cncode/tool/ToolResult.java`
- `src/main/java/cncode/tool/ToolCall.java`
- `src/main/java/cncode/tool/ToolRegistry.java`
- `src/main/java/cncode/tool/ToolExecutionContext.java`
- `src/main/java/cncode/tool/ToolExecutor.java`
- `src/main/java/cncode/tool/ToolJson.java`
- `src/main/java/cncode/tool/builtin/ReadFileTool.java`
- `src/main/java/cncode/tool/builtin/WriteFileTool.java`
- `src/main/java/cncode/tool/builtin/ReplaceFileTool.java`
- `src/main/java/cncode/tool/builtin/RunCommandTool.java`
- `src/main/java/cncode/tool/builtin/FindFilesTool.java`
- `src/main/java/cncode/tool/builtin/SearchCodeTool.java`
- `src/main/java/cncode/agent/SingleToolAgent.java`
- `src/main/java/cncode/agent/AgentEvent.java`
- `src/main/java/cncode/agent/AgentEventHandler.java`
- `src/main/java/cncode/provider/ChatRequest.java`
- `src/main/java/cncode/provider/StreamHandler.java`
- `src/main/java/cncode/provider/openai/OpenAiProvider.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebAssets.java`
- `src/test/java/cncode/tool/...`
- `src/test/java/cncode/agent/...`
- `docs/specs/cncode-single-tool-agent/checklist.md`

## 2. 有序任务

### T1. 写入文档

步骤：

1. 写入 `spec.md`。
2. 写入 `plan.md`。
3. 写入 `task.md`。
4. 写入 `checklist.md`。

验证：

- 四份文档存在。
- 文档范围与单工具 Agent 一致。

### T2. 实现工具核心模型

步骤：

1. 创建 `Tool`。
2. 创建 `ToolCategory`，包含 `READ` / `WRITE` / `COMMAND`。
3. 创建 `ToolMetadata`。
4. 创建 `ToolResult`。
5. 创建 `ToolCall`。
6. 创建 `ToolExecutionContext`。
7. 创建 `ToolJson` 辅助 JSON 转义和简单参数读取。
8. 创建 `ToolRegistry`。
9. 创建 `ToolExecutor`。

验证：

- 工具能注册和按名称查找。
- 每个工具都有分类。
- 工具结果能序列化为模型可读 JSON。
- 未知工具返回结构化错误。

### T3. 实现路径安全和文本限制

步骤：

1. 在 `ToolExecutionContext` 或工具辅助类中实现路径解析。
2. 所有文件路径必须限制在工作目录内。
3. 实现输出长度限制和截断提示。
4. 实现默认超时配置。

验证：

- `../` 越界路径被拒绝。
- 过长输出被截断并说明。
- 工作目录内路径可以正常访问。

### T4. 实现六个内置工具

步骤：

1. 实现 `ReadFileTool`。
2. 实现 `WriteFileTool`。
3. 实现 `ReplaceFileTool`。
4. 实现 `RunCommandTool`。
5. 实现 `FindFilesTool`。
6. 实现 `SearchCodeTool`。
7. 在默认注册中心登记六个工具。
8. 为每个工具补充强描述，明确“什么时候必须调用这个工具”。
9. 按稳定顺序注册六个工具：`read_file`、`write_file`、`replace_file`、`run_command`、`find_files`、`search_code`。

验证：

- 每个工具有名称、描述和参数 Schema。
- 每个工具描述包含具体使用场景，而不是泛泛说明功能。
- `read_file`、`find_files`、`search_code` 分类为 `READ`。
- `write_file`、`replace_file` 分类为 `WRITE`。
- `run_command` 分类为 `COMMAND`。
- 每个工具成功和失败场景都有测试。
- `replace_file` 的 0 次、多次、1 次匹配行为正确。
- `run_command` 返回 stdout、stderr、exit_code，并支持超时。

### T5. 扩展 Provider 流式事件

步骤：

1. 扩展 `ChatRequest`，支持工具定义和是否允许工具。
2. 扩展 `StreamHandler` 或新增工具调用回调。
3. 定义内部 `ToolCall` 流式完成事件。
4. 保持无工具请求的兼容性。

验证：

- 现有纯聊天测试仍通过。
- 无工具时 Provider 行为不变。

### T6. OpenAI Provider 支持工具调用

步骤：

1. 请求体支持 `tools` 和 `tool_choice`。
2. 流式解析 `delta.tool_calls`。
3. 拼接工具名和 arguments JSON 片段。
4. 识别 `finish_reason: tool_calls`。
5. 触发工具调用完成回调。
6. 第二次禁止工具时不发送 tools。

验证：

- 可用样例 SSE 片段测试工具名和参数拼接。
- 普通文本 delta 仍正常输出。
- DeepSeek OpenAI-compatible 配置下请求格式符合预期。
- OpenAI-compatible tools 输出为 API 可识别的 function/tool schema。

### T7. 实现 SingleToolAgent

步骤：

1. 创建 `AgentEvent`。
2. 创建 `AgentEventHandler`。
3. 创建 `SingleToolAgent`。
4. 第一次调用模型允许工具。
5. 如果收到工具调用，执行工具。
6. 将工具结果写入对话历史。
7. 第二次调用模型禁止工具。
8. 如果第二次仍请求工具，返回单工具限制错误。
9. 如果第一次无工具，直接输出最终回复。

验证：

- fake Provider 直接文本回复时不执行工具。
- fake Provider 请求工具时执行一次工具并回灌结果。
- 工具失败时模型能收到结构化失败结果。
- 第二次请求工具会被拒绝。

### T8. Web UI 接入 Agent 事件

步骤：

1. `WebChatServer` 从直接调用 Provider 改为调用 `SingleToolAgent`。
2. `/api/chat` SSE 增加 `tool_start` 和 `tool_result` 事件。
3. 前端 JS 显示工具过程。
4. 侧边栏或系统消息显示工具摘要。
5. 保持普通聊天流式显示。
6. 状态接口显示工具已启用，或至少显示已注册工具数量。

验证：

- Web UI 能显示工具开始和结果。
- 普通聊天不显示工具过程。
- 工具失败显示错误摘要。
- `/api/status` 不再显示工具未启用。

### T9. REPL/TUI 兼容

步骤：

1. REPL 可先保持 Provider 直连，或以文本方式接入 Agent。
2. TUI 可先保持 Provider 直连，或以系统消息方式接入 Agent。
3. 至少确保新增工具系统不破坏 REPL/TUI 启动。

验证：

- `--repl` 仍可启动。
- `--tui` 仍可启动或 fallback。
- 不带参数行为不崩溃。

### T10. 构建和端到端验收

步骤：

1. 运行 `.\gradlew.bat check`。
2. 运行 `.\gradlew.bat installDist`。
3. 启动 `cncode --web`。
4. 用 DeepSeek 请求读取 `AGENTS.md`。
5. 验证 Web UI 工具过程和最终回复。
6. 验证路径越界被拒绝。
7. 验证纯聊天仍可用。

验证：

- 对照 `checklist.md` 完成验收。
