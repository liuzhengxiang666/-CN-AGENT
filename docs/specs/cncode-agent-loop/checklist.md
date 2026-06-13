# CN Code Agent Loop 验收清单

## 1. 文档

- [ ] `docs/specs/cncode-agent-loop/spec.md` 存在。
- [ ] `docs/specs/cncode-agent-loop/plan.md` 存在。
- [ ] `docs/specs/cncode-agent-loop/task.md` 存在。
- [ ] `docs/specs/cncode-agent-loop/checklist.md` 存在。
- [ ] 文档明确不实现复杂 system prompt、完整权限策略、Hook、SubAgent、Team、Worktree。

## 2. 状态机

- [ ] `AgentRunState` 包含 `IDLE / RUNNING_MODEL / EXECUTING_TOOLS / COMPLETED / CANCELLED / FAILED`。
- [ ] Agent Loop 每轮开始检查取消。
- [ ] 无工具调用时进入 `COMPLETED`。
- [ ] 有工具调用时进入 `EXECUTING_TOOLS`。
- [ ] 达到最大轮数时进入 `FAILED` 并输出错误。
- [ ] 用户取消时进入 `CANCELLED` 并输出事件。

## 3. 事件流

- [ ] `AgentEvent` 包含 `USER_MESSAGE`。
- [ ] `AgentEvent` 包含 `THINKING_DELTA`。
- [ ] `AgentEvent` 包含 `DELTA`。
- [ ] `AgentEvent` 包含 `TOOL_START`。
- [ ] `AgentEvent` 包含 `TOOL_RESULT`。
- [ ] `AgentEvent` 包含 `TURN_COMPLETE`。
- [ ] `AgentEvent` 包含 `LOOP_COMPLETE`。
- [ ] `AgentEvent` 包含 `CANCELLED`。
- [ ] `AgentEvent` 包含 `TIMEOUT`。
- [ ] `AgentEvent` 包含 `ERROR`。
- [ ] `AgentEvent` 包含 `DONE`。

## 4. 工具分批

- [ ] `ToolBatch` 或等价结构存在。
- [ ] 连续 `READ` 工具会被放入同一 read batch。
- [ ] read batch 可并发执行。
- [ ] `WRITE` 工具串行执行。
- [ ] `COMMAND` 工具串行执行。
- [ ] 工具结果按模型调用顺序回填。
- [ ] 未知工具返回失败 `ToolResult`，不会崩溃。

## 5. Plan-only

- [ ] `AgentLoopConfig` 包含 `planOnly`。
- [ ] 默认 `planOnly=false`。
- [ ] plan-only 下 `READ` 工具可执行。
- [ ] plan-only 下 `WRITE` 工具被拦截。
- [ ] plan-only 下 `COMMAND` 工具被拦截。
- [ ] 拦截结果包含 `plan-only` 或等价说明。
- [ ] 被拦截结果会回填给模型。

## 6. 取消与超时

- [ ] `AgentCancellationToken` 存在。
- [ ] token `cancel()` 后 `isCancelled()` 为 true。
- [ ] 取消后不再发起下一轮 Provider 调用。
- [ ] 取消后不再启动新的工具执行。
- [ ] 取消会产生 `CANCELLED` 和 `DONE`。
- [ ] Provider 超时或异常会产生 `TIMEOUT` 或 `ERROR`。

## 7. 会话回填

- [ ] 用户消息只在一次用户请求开始时写入一次。
- [ ] assistant 文本会写入 `ChatSession`。
- [ ] assistant 工具调用信息会写入 `ChatSession`。
- [ ] tool result 信息会写入 `ChatSession`。
- [ ] 工具失败结果也会回填。
- [ ] 下一轮模型能看到上一轮工具结果。

## 8. Web UI

- [ ] `/api/chat` 使用 Agent Loop。
- [ ] 输入 `/plan` 不调用模型，并进入 plan-only。
- [ ] 输入 `/do` 不调用模型，并退出 plan-only。
- [ ] plan-only 状态会影响后续普通请求的 AgentLoopConfig。
- [ ] SSE 能输出 `delta`。
- [ ] SSE 能输出 `tool_start`。
- [ ] SSE 能输出 `tool_result`。
- [ ] SSE 能输出 `turn_complete` 或安全忽略。
- [ ] SSE 能输出 `loop_complete` 或安全忽略。
- [ ] SSE 能输出 `cancelled` 或安全忽略。
- [ ] SSE 能输出 `timeout` 或安全忽略。
- [ ] 一次请求完成时只发送一次 `done`。

## 9. StreamCollector 与 Stop Reason

- [ ] `StreamCollector` 类存在。
- [ ] `CollectedStream` record 存在。
- [ ] `AgentLoop` 不再包含 `callModel()`。
- [ ] `StreamCollector` 能收集文本 delta。
- [ ] `StreamCollector` 能收集 thinking delta。
- [ ] `StreamCollector` 能收集多个 tool call。
- [ ] `StreamCollector` 能收集 stop reason。
- [ ] `StreamHandler` 支持带 stop reason 的 complete。
- [ ] OpenAI Provider 解析 `finish_reason` 并传递给上层。
- [ ] `stop` / `end_turn` 会正常结束。
- [ ] `length` 会产生错误事件。

## 10. 自动测试

- [ ] fake Provider 直接文本回复：Agent Loop 只跑一轮并完成。
- [ ] fake Provider 连续工具再文本：Agent Loop 多轮完成。
- [ ] fake Provider 一轮返回多个 `READ` 工具：并发执行且顺序回填。
- [ ] fake Provider 包含 `WRITE` / `COMMAND`：串行执行。
- [ ] plan-only 下写工具被拦截。
- [ ] `/plan` 后写工具被拦截。
- [ ] `/do` 后写工具恢复执行。
- [ ] 取消后不会进入下一轮。
- [ ] stop reason 为 `length` 时输出错误。
- [ ] 超过最大轮数输出错误。
- [ ] `.\gradlew.bat check` 通过。
- [ ] `.\gradlew.bat installDist` 通过。

## 11. 端到端验收

- [ ] 启动 `cncode --web` 后普通聊天仍可流式回复。
- [ ] 输入“找到 AGENTS.md 并读取总结”，能看到查找和读取工具链路。
- [ ] 输入“创建 tmp/agent-loop-test.txt，然后读取确认”，正常模式下能执行写入和读取。
- [ ] plan-only 开启时，同样写入请求会被拦截并输出计划说明。
- [ ] 用户取消或断开连接后，服务端不继续启动新工具或新一轮模型调用。
