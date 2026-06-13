# CN Code System Prompt 任务拆解

## T1. 创建 prompt 包基础数据结构

- 影响文件：
  - `src/main/java/cncode/prompt/PromptSection.java`
  - `src/main/java/cncode/prompt/PromptEnvironment.java`
  - `src/main/java/cncode/prompt/PromptOptions.java`
- 依赖任务：无
- 步骤：
  1. 定义 `PromptSection` record。
  2. 定义 `PromptEnvironment` record。
  3. 定义 `PromptOptions` record。
- 验证方式：
  - `rg "record PromptSection|record PromptEnvironment|record PromptOptions" src/main/java/cncode/prompt`

## T2. 实现 PromptAssembler section 排序与拼接

- 影响文件：
  - `src/main/java/cncode/prompt/PromptAssembler.java`
- 依赖任务：T1
- 步骤：
  1. 实现 `add(PromptSection)` 链式添加。
  2. 实现按 priority 升序排序。
  3. 过滤空 content。
  4. section 之间用两个换行拼接。
- 验证方式：
  - 新增 `PromptAssemblerTest`，检查乱序 section 输出顺序稳定，空 section 不输出。

## T3. 实现稳定全局 section

- 影响文件：
  - `src/main/java/cncode/prompt/PromptSections.java`
- 依赖任务：T1, T2
- 步骤：
  1. 实现 Identity。
  2. 实现 Behavior。
  3. 实现 ToolUse。
  4. 实现 CodeStyle。
  5. 实现 Safety。
  6. 实现 TaskModes。
  7. 实现 OutputStyle。
  8. 实现 ProjectInstructions。
- 验证方式：
  - 测试输出包含 `CN Code`、`<system-reminder>`、`编辑前必须先读`、`中文回答`。

## T4. 实现环境探测与环境 reminder

- 影响文件：
  - `src/main/java/cncode/prompt/PromptEnvironmentDetector.java`
  - `src/main/java/cncode/prompt/SystemReminder.java`
- 依赖任务：T1
- 步骤：
  1. 探测工作目录、操作系统、shell、当前日期时间。
  2. 用 `git rev-parse --is-inside-work-tree` 判断 Git 仓库。
  3. 用 `git branch --show-current` 或等价命令获取分支。
  4. 用 `git status --short` 获取简短状态。
  5. 失败时安全降级。
  6. 构造 `<system-reminder>` 环境消息。
- 验证方式：
  - 测试 reminder 包含 `<system-reminder>`、工作目录、操作系统。

## T5. 实现 PlanModeReminder

- 影响文件：
  - `src/main/java/cncode/prompt/PlanModeReminder.java`
- 依赖任务：T1
- 步骤：
  1. 定义完整 Plan reminder。
  2. 定义精简 Plan reminder。
  3. 实现 `build(iteration)`。
  4. `iteration == 1` 返回完整提醒。
  5. `iteration % 5 == 0` 返回完整提醒。
  6. 其他轮次返回精简提醒。
- 验证方式：
  - 测试第 1、5 轮包含“一次只问一个关键问题”，第 2 轮只包含精简提醒。

## T6. 实现 buildStablePrompt 主入口

- 影响文件：
  - `src/main/java/cncode/prompt/PromptAssembler.java`
- 依赖任务：T2, T3
- 步骤：
  1. 新增 `buildStablePrompt(PromptOptions options)`。
  2. 添加固定 section。
  3. 注入 `AGENTS.md` 项目规则。
  4. 预留 Skill/Memory 可选 section。
  5. 确保动态环境不进入 stable prompt。
- 验证方式：
  - 测试 stable prompt 不包含当前 Git 状态和当前时间字段。

## T7. 接入 AgentLoop 主流程

- 影响文件：
  - `src/main/java/cncode/agent/AgentLoop.java`
- 依赖任务：T1~T6
- 步骤：
  1. 替换当前硬编码 system prompt。
  2. 调用 `PromptAssembler.buildStablePrompt`。
  3. 每轮注入环境 `<system-reminder>`。
  4. Plan 模式下按 iteration 注入 `PlanModeReminder`。
  5. 保留现有对话历史顺序。
- 验证方式：
  - 扩展 `AgentLoopTest`，捕获 fake Provider 的请求消息，确认包含 stable prompt、环境 reminder、Plan reminder。

## T8. 强化六个内置工具 description

- 影响文件：
  - `src/main/java/cncode/tool/builtin/ReadFileTool.java`
  - `src/main/java/cncode/tool/builtin/WriteFileTool.java`
  - `src/main/java/cncode/tool/builtin/ReplaceFileTool.java`
  - `src/main/java/cncode/tool/builtin/RunCommandTool.java`
  - `src/main/java/cncode/tool/builtin/FindFilesTool.java`
  - `src/main/java/cncode/tool/builtin/SearchCodeTool.java`
- 依赖任务：T3
- 步骤：
  1. 在读文件工具中强调需要内容时优先调用。
  2. 在改文件工具中强调编辑前必须先读。
  3. 在命令工具中强调优先专用工具。
  4. 在搜索类工具中强调适用场景。
- 验证方式：
  - `ToolRegistryTest` 或新增测试检查工具描述包含关键短语。

## T9. 增加缓存统计占位

- 影响文件：
  - `src/main/java/cncode/prompt/PromptCacheStats.java`
  - `src/main/java/cncode/provider/StreamHandler.java`
  - `src/main/java/cncode/provider/openai/OpenAiProvider.java`
  - `src/main/java/cncode/provider/anthropic/AnthropicProvider.java`
- 依赖任务：T1
- 步骤：
  1. 定义 `PromptCacheStats`。
  2. 给 `StreamHandler` 增加默认缓存统计回调。
  3. Provider 无缓存字段时不触发或触发全 0。
  4. 预留后续解析 Anthropic cache 字段的入口。
- 验证方式：
  - 编译通过；现有 Provider 流式行为不变。

## T10. 准备人工行为评估清单

- 影响文件：
  - `docs/specs/cncode-system-prompt/checklist.md`
- 依赖任务：T7, T8
- 步骤：
  1. 写出读取文件场景。
  2. 写出编辑前先读场景。
  3. 写出 Plan 模式澄清场景。
  4. 写出工具失败后调整场景。
- 验证方式：
  - checklist 中存在可执行的手动场景。

## T11. 接入主流程

- 影响文件：
  - `src/main/java/cncode/agent/AgentLoop.java`
  - `src/main/java/cncode/provider/ChatRequest.java`
  - 相关测试
- 依赖任务：T1~T9
- 步骤：
  1. 确认 Web UI、TUI、CLI 使用同一 AgentLoop prompt 管线。
  2. 确认 Plan/Do 切换仍只改变动态 reminder 和工具安全边界。
  3. 确认普通对话和工具调用链路不回退。
- 验证方式：
  - `.\gradlew.bat check --console=plain` 通过。

## T12. 端到端验证

- 影响文件：无
- 依赖任务：T11
- 步骤：
  1. 运行 `.\gradlew.bat check --console=plain`。
  2. 运行 `.\gradlew.bat installDist --console=plain`。
  3. 启动 CN Code。
  4. 在 Plan 模式输入“我要加一个新功能”，观察是否先问需求。
  5. 在 Do 模式输入“读取 AGENTS.md 并总结”，观察是否调用读文件工具。
  6. 要求修改文件，观察是否先读相关文件。
- 验证方式：
  - 对照 checklist 全部通过。
