# CN Code System Prompt 技术设计

## 1. 架构概览

新增 `cncode.prompt` 包，负责构造稳定 system prompt 和动态 reminder。

```text
AgentLoop
  -> PromptAssembler.buildStablePrompt(...)
  -> PromptAssembler.buildEnvironmentReminder(...)
  -> PromptAssembler.buildPlanReminder(...)
  -> ChatRequest(messages with system/reminder/history)
  -> Provider
```

核心原则：

- 稳定 prompt 只描述长期不变规则。
- 环境、模式、临时补充通过 `<system-reminder>` 进入对话消息。
- section 使用 priority 排序，避免未来章节改动时破坏前缀稳定性。

## 2. 模块划分

### prompt

新增：

- `PromptSection`
- `PromptAssembler`
- `PromptEnvironment`
- `PromptOptions`
- `SystemReminder`
- `PlanModeReminder`
- `PromptCacheStats`

职责：

- 管理 section 排序和拼接。
- 构造稳定 system prompt。
- 探测运行环境。
- 构造 `<system-reminder>`。
- 构造 Plan 模式动态提醒。
- 承接 Provider 缓存字段统计。

### agent

调整：

- `AgentLoop`
- `AgentLoopConfig`

职责：

- 持有或调用 prompt 组件。
- 每轮构造消息时注入环境 reminder。
- Plan 模式下按轮次注入 Plan reminder。
- 不再直接维护大段固定 system prompt。

### provider

调整：

- `StreamHandler`
- `OpenAiProvider`
- `AnthropicProvider`

职责：

- 如响应中存在缓存字段，解析成 `PromptCacheStats` 或等价统计。
- 没有缓存字段时保持空统计，不影响现有流式行为。

### tool

调整：

- 六个内置工具的 metadata description。

职责：

- 在工具描述中强化关键规则。
- 与全局工具 section 形成双重约束。

## 3. 核心数据结构

### PromptSection

```text
record PromptSection(String name, int priority, String content)
```

规则：

- `content` 为空时不进入输出。
- priority 越小越靠前。
- section 之间用两个换行分隔。

### PromptEnvironment

```text
record PromptEnvironment(
    String workDir,
    String os,
    String shell,
    String dateTime,
    boolean gitRepo,
    String gitBranch,
    String gitStatus
)
```

Git 探测失败时：

- `gitRepo=false`
- `gitBranch=""`
- `gitStatus="unknown"` 或空字符串

### PromptOptions

```text
record PromptOptions(
    String projectInstructions,
    String toolRules,
    String skillSummary,
    String memorySummary
)
```

当前阶段：

- `projectInstructions` 可接入 `AGENTS.md`。
- `skillSummary` 和 `memorySummary` 预留。

### SystemReminder

```text
class SystemReminder {
    static ChatMessage environment(PromptEnvironment env)
    static ChatMessage message(String title, String body)
}
```

输出格式：

```text
<system-reminder>
标题：...
内容：...
</system-reminder>
```

### PlanModeReminder

```text
class PlanModeReminder {
    static String build(int iteration)
}
```

规则：

- `iteration == 1` 返回完整提醒。
- `iteration % 5 == 0` 返回完整提醒。
- 其他轮次返回精简提醒。

## 4. Stable Prompt Section 设计

固定 section：

1. `Identity`，priority 0  
   说明 CN Code 是 Java 21 实现的本地 Coding Agent。

2. `Behavior`，priority 10  
   诚实报告、不编造文件内容、不把系统提醒当用户请求。

3. `ToolUse`，priority 20  
   需要本地信息必须优先使用专用工具；编辑前先读。

4. `CodeStyle`，priority 30  
   遵守项目风格，中文注释，最小改动。

5. `Safety`，priority 40  
   高风险命令谨慎，Plan 模式禁止写和命令类动作。

6. `TaskModes`，priority 50  
   Do 模式执行，Plan 模式澄清和规划，但这里只放稳定解释，不放当前状态。

7. `OutputStyle`，priority 60  
   中文回答，简洁，文件引用使用可识别路径。

8. `ProjectInstructions`，priority 70  
   注入 `AGENTS.md` 稳定项目规则。

后续 Skill/Memory 可用 priority 80/90 插入。

## 5. AgentLoop 接入设计

`AgentLoop.withAgentLoopSystemPrompt` 改造成：

```text
stable prompt system message
environment reminder system/user-level message
plan reminder when planOnly
conversation history
```

如果当前 `ChatRole` 只有 `SYSTEM/USER/ASSISTANT` 三类，则 `<system-reminder>` 可以先作为 `SYSTEM` 消息注入；Provider 若不支持多 system，也可以作为靠前 `USER` 消息注入，内容仍用标签包裹。

本阶段优先使用 `ChatRole.SYSTEM`，保持现有 `ChatRequest` 结构简单。

## 6. 缓存策略设计

### 稳定前缀

稳定 system prompt 在以下变化时重建：

- 项目规则文件变化。
- 工具描述变化。
- Provider 或模型切换。
- Skill/Memory 接入变化。

环境、时间、Git 状态和 Plan 模式不触发稳定 prompt 变化。

### Provider 缓存统计

新增轻量统计：

```text
record PromptCacheStats(long readTokens, long writeTokens, long hitTokens)
```

OpenAI-compatible / DeepSeek 没有对应字段时返回全 0。

Anthropic 若后续响应暴露 cache read/write 字段，再在 Provider 内解析。

本阶段只准备结构和安全降级，不强制真实命中。

## 7. 工具描述强化设计

每个工具 description 增加明确使用边界：

- `read_file`：读取前不要猜内容；需要文件内容时优先使用。
- `write_file`：只在执行模式使用；写入前确认目标和内容。
- `replace_file`：修改前先读文件；原文必须唯一匹配。
- `run_command`：优先专用工具；命令用于测试、构建、缺少专用工具的场景。
- `find_files`：查文件名优先使用。
- `search_code`：查代码内容优先使用。

## 8. 风险与缓解

- 多 system message 对不同 Provider 兼容性不同：先复用现有消息结构，必要时后续折叠到第一条 system。
- 环境 reminder 每轮注入会增加 token：内容保持短，Plan reminder 用完整/精简节奏控制。
- 缓存字段 Provider 差异大：用全 0 统计安全降级。
- 工具描述过长影响工具数组 token：只补关键规则，不写长篇教程。
