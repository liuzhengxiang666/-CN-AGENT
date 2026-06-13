# CN Code Agent Loop 任务拆解

## 1. 文件清单

预计创建或修改：

- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/agent/AgentLoopConfig.java`
- `src/main/java/cncode/agent/AgentCancellationToken.java`
- `src/main/java/cncode/agent/AgentRunState.java`
- `src/main/java/cncode/agent/AgentEvent.java`
- `src/main/java/cncode/agent/StreamCollector.java`
- `src/main/java/cncode/agent/CollectedStream.java`
- `src/main/java/cncode/agent/StreamingToolExecutor.java`
- `src/main/java/cncode/agent/ToolBatch.java`
- `src/main/java/cncode/provider/StreamHandler.java`
- `src/main/java/cncode/provider/openai/OpenAiProvider.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebAssets.java`
- `src/test/java/cncode/agent/AgentLoopTest.java`
- `src/test/java/cncode/agent/StreamCollectorTest.java`
- `src/test/java/cncode/agent/StreamingToolExecutorTest.java`

## 2. 有序任务

### T1. 更新文档

步骤：

1. 更新 `spec.md`，加入 ReAct、状态机、事件流、分批、plan-only、取消/超时。
2. 更新 `plan.md`，加入状态、接口、批次、拦截位。
3. 更新 `task.md`。
4. 更新 `checklist.md`。

验证：

- 四份文档存在。
- 文档明确不做完整权限、Hook、SubAgent、复杂 system prompt。

### T2. 扩展 AgentEvent

步骤：

1. 增加 `USER_MESSAGE`。
2. 增加 `THINKING_DELTA`。
3. 增加 `TURN_COMPLETE`。
4. 增加 `LOOP_COMPLETE`。
5. 增加 `CANCELLED`。
6. 增加 `TIMEOUT`。
7. 保持 `DELTA / TOOL_START / TOOL_RESULT / ERROR / DONE` 兼容。

验证：

- Web SSE switch 覆盖或安全忽略新增事件。
- 旧测试仍通过。

### T3. 定义配置、状态和取消令牌

步骤：

1. 扩展 `AgentLoopConfig`：`maxIterations`、`modelIdleTimeout`、`toolTimeout`、`maxOutputChars`、`planOnly`。
2. 新增 `AgentRunState` 枚举：`IDLE / RUNNING_MODEL / EXECUTING_TOOLS / COMPLETED / CANCELLED / FAILED`。
3. 新增 `AgentCancellationToken`。
4. `AgentLoop.run` 返回 token 或接受外部 token。

验证：

- 默认最大轮数为 10。
- 默认 plan-only 为 false。
- token 调用 `cancel()` 后 `isCancelled()` 为 true。

### T4. 重构 AgentLoop 状态机

步骤：

1. 每轮开始检查取消。
2. 设置状态为 `RUNNING_MODEL`。
3. 调 Provider 并收集文本/thinking/tool calls。
4. 无工具调用则转 `COMPLETED`。
5. 有工具调用则转 `EXECUTING_TOOLS`。
6. 工具执行后写回结果并进入下一轮。
7. 最大轮数转 `FAILED`。
8. 取消转 `CANCELLED`。

验证：

- fake Provider 普通文本：`RUNNING_MODEL -> COMPLETED`。
- fake Provider 工具后文本：`RUNNING_MODEL -> EXECUTING_TOOLS -> RUNNING_MODEL -> COMPLETED`。
- 超最大轮数产生 `ERROR`。
- 取消产生 `CANCELLED`。

### T5. 实现工具分批

步骤：

1. 新增 `ToolBatch`。
2. 按工具调用顺序查找工具类别。
3. 连续 `READ` 合并成 read batch。
4. `WRITE` / `COMMAND` 形成串行 batch。
5. 未知工具形成串行失败项。
6. read batch 并发执行。
7. 所有结果按原始 index 排序回填。

验证：

- `READ, READ` 并发执行且结果顺序稳定。
- `WRITE, WRITE` 串行执行。
- `READ, WRITE, READ` 拆成三个批次。
- 未知工具返回失败结果。

### T6. 实现 plan-only 拦截

步骤：

1. 在工具执行前检查 `config.planOnly()`。
2. `READ` 放行。
3. `WRITE` 和 `COMMAND` 返回失败 `ToolResult`。
4. 失败结果回填给模型。
5. 发送 `TOOL_RESULT` 事件。

验证：

- plan-only 下 `read_file` 可执行。
- plan-only 下 `write_file` 被拦截。
- plan-only 下 `run_command` 被拦截。
- 拦截结果包含 `plan-only` 文案。

### T7. 实现取消与超时事件

步骤：

1. Agent Loop 在每轮和每个工具前检查 token。
2. 取消后发送 `CANCELLED` 和 `DONE`。
3. Provider 异常时发送 `ERROR`。
4. 模型空闲超时可先通过 Provider timeout 或异常转换为 `TIMEOUT` / `ERROR`。
5. 工具超时继续使用 `ToolExecutionContext.timeout`。

验证：

- 取消后不再发起下一轮 Provider 调用。
- 取消后不再启动新的工具。
- 超时错误能被 UI 看到。

### T8. 强化 Provider 多工具收集

步骤：

1. 保持单工具解析稳定。
2. `StreamHandler.onToolCall` 支持多次调用。
3. OpenAI Provider 遇到多个 tool call 时不覆盖已完成调用。
4. 无法完整解析多 tool call 时不影响 Agent Loop fake Provider 测试。

验证：

- fake Provider 一轮发两个 tool call，Agent Loop 能执行两个。
- OpenAI 单工具调用测试仍通过。

### T9. 抽离 StreamCollector

步骤：

1. 新增 `CollectedStream`。
2. 新增 `StreamCollector`。
3. 把 `AgentLoop.callModel()` 中的 Provider 回调迁移到 `StreamCollector.collect()`。
4. `AgentLoop` 只消费 `CollectedStream`。
5. 测试 delta、thinking、tool call、stop reason、error 收集。

验证：

- `AgentLoop` 中不再存在 `callModel()`。
- `StreamCollectorTest` 覆盖文本、工具调用和 stop reason。

### T10. 暴露 stop_reason / end_turn

步骤：

1. 调整 `StreamHandler`，支持 `onComplete(String stopReason)`。
2. 保留无参 `onComplete()` 默认兼容。
3. `OpenAiProvider` 解析 `finish_reason` 并传给 handler。
4. `AgentLoop` 根据 `CollectedStream.stopReason()` 处理 `stop`、`end_turn`、`tool_calls`、`length`。

验证：

- fake Provider 返回 `stop` 时正常完成。
- fake Provider 返回 `end_turn` 时正常完成。
- fake Provider 返回 `length` 时输出错误。

### T11. Web UI 接入新增事件与命令

步骤：

1. `/api/chat` 继续使用 Agent Loop。
2. SSE 增加 `thinking`、`turn_complete`、`loop_complete`、`cancelled`、`timeout`。
3. 前端安全处理新增事件。
4. WebChatServer 保存当前会话 `planOnly` 状态。
5. 用户输入 `/plan` 时切换 plan-only，不调用模型。
6. 用户输入 `/do` 时退出 plan-only，不调用模型。
7. 用户断开连接时尽量触发 cancel token。

验证：

- Web UI 普通聊天可用。
- 多工具过程可见。
- 新增事件不会导致 JS 报错。
- `/plan` 返回已进入 plan-only 的 system 消息。
- `/do` 返回已恢复正常模式的 system 消息。

### T12. 构建和验收

步骤：

1. 运行 `.\gradlew.bat check`。
2. 运行 `.\gradlew.bat installDist`。
3. 启动 `cncode --web`。
4. 测试多轮读取。
5. 测试 plan-only。
6. 测试取消或断连。

验证：

- 对照 `checklist.md` 完成验收。

## 3. 进度

- [ ] T1 更新文档
- [ ] T2 扩展 AgentEvent
- [ ] T3 定义配置、状态和取消令牌
- [ ] T4 重构 AgentLoop 状态机
- [ ] T5 实现工具分批
- [ ] T6 实现 plan-only 拦截
- [ ] T7 实现取消与超时事件
- [ ] T8 强化 Provider 多工具收集
- [ ] T9 抽离 StreamCollector
- [ ] T10 暴露 stop_reason / end_turn
- [ ] T11 Web UI 接入新增事件与命令
- [ ] T12 构建和验收
