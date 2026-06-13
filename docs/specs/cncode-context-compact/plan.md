# CN Code 上下文压缩技术方案

## 1. 架构概览

上下文压缩分两层：

```text
ConversationManager
  -> ToolResultBudget.apply(...)       // Layer 1：工具结果轻量预防
  -> ContextCompactor.manage(...)      // Layer 2：整体历史兜底压缩
  -> Provider.stream(...)
```

Layer 1 主要处理工具结果体积，尽量不调用 LLM。  
Layer 2 处理整体对话长度，需要调用 LLM 生成结构化摘要。

## 2. 模块划分

### 2.1 `cncode.toolresult`

负责工具结果内容替换和落盘。

建议类：

- `ContentReplacementState`
  - 记录已见工具调用 ID。
  - 记录工具调用 ID 到替换文本的映射。
  - 支持 `copy()`，给未来 fork / 子 Agent 预留。

- `ContentReplacementRecord`
  - JSONL 持久化记录。
  - 字段包含 `kind`、`toolUseId`、`replacement`。

- `ApplyResult`
  - 返回轻量压缩后的 API 对话视图。
  - 返回本轮新增 replacement records。

- `ReplacementRecordsIO`
  - 负责 JSONL append / load。

- `ToolResultBudget`
  - 定义工具结果阈值。
  - 执行单条超限落盘。
  - 执行单消息聚合超限落盘。
  - 生成稳定预览文本。

### 2.2 `cncode.compact`

负责整体上下文压缩。

建议类：

- `ContextCompactor`
  - `estimateTokens(...)`
  - `manage(...)`
  - `forceCompact(...)`
  - `requestSummary(...)`
  - `formatCompactSummary(...)`
  - `buildBoundaryMessage(...)`

- `CompactTrackingState`
  - 记录连续自动压缩失败次数。
  - 达到阈值后熔断自动压缩。

- `CompactResult`
  - 表达是否发生压缩、压缩前估算 token、压缩后估算 token、提示文本。

## 3. 核心接口

### 3.1 工具结果预算

```java
public final class ToolResultBudget {
    public static ApplyResult apply(
            ConversationManager conversation,
            Path sessionDir,
            ContentReplacementState state
    );
}
```

`apply` 不应修改传入的主 conversation，而是返回一份用于 API 请求的视图。

### 3.2 上下文压缩

```java
public final class ContextCompactor {
    public static CompactResult manage(
            ConversationManager conversation,
            LlmProvider provider,
            long contextWindow,
            CompactTrackingState tracking
    );

    public static CompactResult forceCompact(
            ConversationManager conversation,
            LlmProvider provider,
            long contextWindow
    );
}
```

`manage` 用于自动压缩，受阈值和熔断影响。  
`forceCompact` 用于 `/compact` 手动命令，跳过阈值判断。

### 3.3 摘要 Prompt

摘要 Prompt 固定包含：

- 首部禁止工具声明。
- 摘要任务说明。
- 固定摘要结构。
- 分析草稿和正式摘要输出要求。
- 尾部再次禁止工具声明。

系统只保留正式摘要，不把分析草稿写回主 conversation。

## 4. 数据流

### 4.1 每次 Provider 请求前

```text
AgentLoop
  -> ToolResultBudget.apply(conversation, sessionDir, replacementState)
  -> 保存新增 replacement records
  -> ContextCompactor.manage(conversation, provider, contextWindow, tracking)
  -> 如果整体压缩成功，发送 CompactEvent
  -> Provider.stream(apiConversation)
```

注意：Layer 1 处理后得到的是 API 视图；Layer 2 成功时会替换旧 conversation 历史。

### 4.2 手动 `/compact`

```text
Web/CLI command
  -> ContextCompactor.forceCompact(...)
  -> 成功：显示压缩前后 token 估算
  -> 失败：显示错误，不破坏原 conversation
```

### 4.3 整体压缩后的历史形态

压缩成功后，conversation 至少包含：

1. 一条摘要消息：`[Compacted conversation summary]`
2. 一条边界提醒：告诉模型旧历史已摘要化，需要细节时重新读文件
3. 最近若干条原始消息，尤其保留最新用户请求

## 5. 技术决策

### 5.1 优先压缩工具结果

工具结果最容易爆上下文，也最容易通过文件路径恢复，因此 Layer 1 先处理工具结果。

### 5.2 用户消息优先原文保留

用户消息承载真实意图，不能轻易被摘要改写。整体压缩时也必须在“用户原话”部分保留关键表达。

### 5.3 摘要调用禁用工具

摘要是内部维护动作，不应触发工具调用。Provider 调用时工具列表传空，Prompt 首尾都强调禁止工具。

### 5.4 自动压缩失败熔断

压缩失败通常由 Provider 异常、上下文过大或摘要响应异常引起。连续失败后停止自动触发，避免每轮消耗时间和 token。

### 5.5 会话目录落盘

完整工具结果写入固定会话目录，例如：

```text
.cncode/session/tool-results/<tool-call-id>.txt
```

具体目录名称可在实现时根据现有项目约定调整，但必须固定且在项目工作目录内。

## 6. 风险与缓解

- 风险：摘要丢失关键代码细节。  
  缓解：压缩边界消息要求模型需要细节时重新读文件。

- 风险：摘要调用再次触发工具。  
  缓解：工具列表传空，并在 prompt 首尾禁止工具。

- 风险：落盘路径泄漏到不该写的位置。  
  缓解：路径由系统生成，固定在会话目录，禁止模型提供。

- 风险：估算 token 不准。  
  缓解：阈值保守设置，估算逻辑集中封装。

- 风险：压缩失败打断对话。  
  缓解：自动压缩失败保留原历史，连续失败熔断。
