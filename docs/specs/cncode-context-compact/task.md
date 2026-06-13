# CN Code 上下文压缩任务拆解

## T1. 建立工具结果替换状态

影响文件：

- `src/main/java/cncode/toolresult/ContentReplacementState.java`
- `src/test/java/cncode/toolresult/ContentReplacementStateTest.java`

依赖任务：无

步骤：

1. 新增状态类，保存 `seenIds` 和 `replacements`。
2. 提供只读或受控访问方法。
3. 提供 `copy()`，复制内部集合。

验证方式：

- 新状态为空。
- `copy()` 后修改副本不影响原对象。

## T2. 建立替换记录 JSONL

影响文件：

- `src/main/java/cncode/toolresult/ContentReplacementRecord.java`
- `src/main/java/cncode/toolresult/ReplacementRecordsIO.java`
- `src/test/java/cncode/toolresult/ReplacementRecordsIOTest.java`

依赖任务：T1

步骤：

1. 定义 replacement record。
2. 实现 append records 到 JSONL。
3. 实现从 JSONL 读取 records。
4. 缺失文件时返回空列表。

验证方式：

- append 后 load 能得到相同记录。
- 缺失文件不报错。

## T3. 实现工具结果预算常量

影响文件：

- `src/main/java/cncode/toolresult/ToolResultBudget.java`

依赖任务：T1

步骤：

1. 定义单个工具结果阈值。
2. 定义单条消息聚合阈值。
3. 定义预览长度。
4. 定义落盘子目录名称。

验证方式：

- 常量可被测试读取。
- 阈值集中在一个类中。

## T4. 实现工具结果落盘与预览

影响文件：

- `src/main/java/cncode/toolresult/ToolResultBudget.java`
- `src/test/java/cncode/toolresult/ToolResultBudgetTest.java`

依赖任务：T3

步骤：

1. 根据工具调用 ID 生成稳定文件路径。
2. 超限内容写入会话目录。
3. 生成包含长度、预览和路径的替换文本。
4. 同一工具结果重复处理时复用已有 replacement。

验证方式：

- 超长结果生成落盘文件。
- replacement 文本包含文件路径。
- 重复调用不会生成不同 replacement。

## T5. 实现单条消息聚合预算

影响文件：

- `src/main/java/cncode/toolresult/ToolResultBudget.java`
- `src/test/java/cncode/toolresult/ToolResultBudgetTest.java`

依赖任务：T4

步骤：

1. 统计同一条工具结果消息内所有结果的总长度。
2. 超过聚合阈值时，按结果长度从大到小依次落盘。
3. 保持工具结果顺序不变。

验证方式：

- 多个工具结果合计超限时，只替换必要的大结果。
- 回灌给模型的工具结果顺序不变。

## T6. 实现 API 对话视图构建

影响文件：

- `src/main/java/cncode/toolresult/ApplyResult.java`
- `src/main/java/cncode/toolresult/ToolResultBudget.java`
- `src/test/java/cncode/toolresult/ToolResultBudgetTest.java`

依赖任务：T5

步骤：

1. `ToolResultBudget.apply` 返回新的 API conversation。
2. 不修改原始 ConversationManager。
3. 返回本轮新增 replacement records。

验证方式：

- 原 conversation 内容不变。
- API conversation 中超限工具结果被替换。
- 新增 records 数量正确。

## T7. 实现 token 估算

影响文件：

- `src/main/java/cncode/compact/ContextCompactor.java`
- `src/test/java/cncode/compact/ContextCompactorTest.java`

依赖任务：无

步骤：

1. 遍历用户消息、助手消息、工具调用参数、工具结果和 thinking。
2. 使用字符数近似估算 token。
3. 为空内容提供安全默认值。

验证方式：

- 空对话估算为 0 或接近 0。
- 含工具结果的大对话估算值明显更高。

## T8. 编写摘要 Prompt 与正式摘要提取

影响文件：

- `src/main/java/cncode/compact/ContextCompactor.java`
- `src/test/java/cncode/compact/ContextCompactorTest.java`

依赖任务：T7

步骤：

1. 编写摘要 system prompt。
2. 首尾都声明禁止工具。
3. 要求输出分析草稿和正式摘要。
4. 只提取正式摘要。

验证方式：

- Prompt 中至少两次出现禁止工具语义。
- 能从带草稿的响应中提取正式摘要。

## T9. 实现自动整体压缩

影响文件：

- `src/main/java/cncode/compact/ContextCompactor.java`
- `src/main/java/cncode/compact/CompactTrackingState.java`
- `src/main/java/cncode/compact/CompactResult.java`
- `src/test/java/cncode/compact/ContextCompactorTest.java`

依赖任务：T8

步骤：

1. 根据估算 token 与上下文窗口计算占比。
2. 未达到阈值时跳过。
3. 达到阈值时调用 LLM 摘要。
4. 摘要成功后替换旧历史，并追加边界消息。

验证方式：

- 低于阈值不触发压缩。
- 高于阈值触发摘要。
- 压缩后 conversation 包含摘要和边界消息。

## T10. 实现自动压缩熔断

影响文件：

- `src/main/java/cncode/compact/CompactTrackingState.java`
- `src/main/java/cncode/compact/ContextCompactor.java`
- `src/test/java/cncode/compact/ContextCompactorTest.java`

依赖任务：T9

步骤：

1. 自动压缩失败时计数加一。
2. 成功时清零。
3. 达到连续失败上限后跳过自动压缩。

验证方式：

- 连续失败达到上限后不再自动调用摘要。
- 手动压缩仍然可以调用。

## T11. 实现手动压缩入口

影响文件：

- `src/main/java/cncode/compact/ContextCompactor.java`
- Web 或 CLI 命令入口相关文件

依赖任务：T9

步骤：

1. 新增 `forceCompact`。
2. 跳过阈值判断。
3. 成功后返回压缩前后估算 token。
4. 失败时返回清楚错误。

验证方式：

- 输入 `/compact` 后触发压缩。
- 未达到自动阈值时也能手动压缩。

## T12. 接入 AgentLoop 请求前流程

影响文件：

- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/agent/AgentLoopConfig.java`
- `src/main/java/cncode/agent/AgentEvent.java`

依赖任务：T6, T9

步骤：

1. AgentLoop 持有 replacement state 和 compact tracking state。
2. Provider 请求前先执行 `ToolResultBudget.apply`。
3. 保存新增 replacement records。
4. 再执行 `ContextCompactor.manage`。
5. 压缩成功时发送 compact 事件。
6. Provider 使用压缩后的 API conversation。

验证方式：

- 每轮请求前 Layer 1 先于 Layer 2 执行。
- 压缩事件能被上层收到。

## T13. 接入主流程

影响文件：

- `src/main/java/cncode/Main.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebAssets.java`

依赖任务：T12

步骤：

1. 为 Web 会话初始化压缩状态。
2. SSE 支持 compact 事件。
3. 前端展示压缩提示。
4. 支持 `/compact` 命令或按钮触发。

验证方式：

- Web UI 能看到压缩完成提示。
- `/compact` 不作为普通用户消息发给模型。

## T14. 端到端验证

影响文件：无

依赖任务：T13

步骤：

1. 构造会产生大工具结果的请求。
2. 验证工具结果被写入会话目录。
3. 验证对话中只保留预览和路径。
4. 构造长历史触发整体压缩。
5. 验证压缩后仍能继续对话。

验证方式：

- `.\gradlew.bat check --console=plain` 通过。
- Web UI 手动 `/compact` 可见压缩提示。
- 模型需要完整结果时能通过读文件工具重新读取落盘文件。
