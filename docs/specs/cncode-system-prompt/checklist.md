# CN Code System Prompt 验收清单

## 1. Prompt 结构

- [ ] `src/main/java/cncode/prompt/PromptSection.java` 存在，包含 `record PromptSection`。
- [ ] `src/main/java/cncode/prompt/PromptEnvironment.java` 存在，包含工作目录、操作系统、shell、时间、Git 状态字段。
- [ ] `src/main/java/cncode/prompt/PromptOptions.java` 存在，包含项目规则、工具规则、Skill 摘要、Memory 摘要字段。
- [ ] `PromptAssembler` 支持按 priority 排序。
- [ ] `PromptAssembler` 会过滤空 section。
- [ ] section 之间使用两个换行分隔。
- [ ] stable prompt 包含 `Identity`、`Behavior`、`ToolUse`、`CodeStyle`、`Safety`、`TaskModes`、`OutputStyle`。

## 2. 稳定与动态分离

- [ ] stable prompt 不包含当前具体日期时间。
- [ ] stable prompt 不包含当前 Git 分支。
- [ ] stable prompt 不包含当前 Git status。
- [ ] stable prompt 不直接写死“当前处于 Plan 模式”。
- [ ] 环境信息通过 `<system-reminder>` 注入。
- [ ] Plan 模式通过动态 reminder 注入。

## 3. 环境 Reminder

- [ ] 环境 reminder 文本包含 `<system-reminder>`。
- [ ] 环境 reminder 包含当前工作目录。
- [ ] 环境 reminder 包含操作系统。
- [ ] 环境 reminder 包含当前时间。
- [ ] 环境 reminder 包含 Git 是否可用或 Git 状态。
- [ ] 非 Git 目录下环境探测不抛异常。
- [ ] Git 命令不可用时环境探测不抛异常。

## 4. Plan Reminder

- [ ] `PlanModeReminder.build(1)` 返回完整提醒。
- [ ] `PlanModeReminder.build(5)` 返回完整提醒。
- [ ] `PlanModeReminder.build(2)` 返回精简提醒。
- [ ] 完整提醒包含“一次只问一个关键问题”。
- [ ] 完整提醒包含“能用选择题就给选择题”。
- [ ] 完整提醒包含 `cncode-options`。
- [ ] 精简提醒短于完整提醒。

## 5. AgentLoop 接入

- [ ] `AgentLoop` 不再直接维护大段固定 system prompt。
- [ ] `AgentLoop` 调用 prompt 组件构造 stable prompt。
- [ ] `AgentLoop` 每轮 Provider 请求包含环境 reminder。
- [ ] Plan 模式下 Provider 请求包含 Plan reminder。
- [ ] Do 模式下 Provider 请求不包含 Plan reminder。
- [ ] 对话历史仍在 prompt/reminder 之后追加。
- [ ] 普通聊天仍能流式输出。
- [ ] 工具调用仍能进入 Agent Loop。

## 6. 工具描述强化

- [ ] `ReadFileTool` 描述包含“需要文件内容时优先使用”或等价语义。
- [ ] `ReplaceFileTool` 描述包含“修改前必须先读”或等价语义。
- [ ] `RunCommandTool` 描述包含“优先使用专用工具”或等价语义。
- [ ] `FindFilesTool` 描述说明用于按文件名查找。
- [ ] `SearchCodeTool` 描述说明用于按代码内容搜索。
- [ ] `WriteFileTool` 描述说明 Plan 模式下不能写入。

## 7. 缓存统计

- [ ] 存在 `PromptCacheStats` 或等价数据结构。
- [ ] Provider 无缓存字段时不抛异常。
- [ ] `StreamHandler` 或 Provider 层存在缓存统计接入点。
- [ ] 缓存统计缺失时默认值为 0 或空对象。

## 8. 人工行为评估

- [ ] 输入“读取 AGENTS.md 并总结”，模型调用读文件工具，而不是凭空回答。
- [ ] 输入“修改 AGENTS.md 中的项目名”，模型先读文件，再尝试修改。
- [ ] 进入 Plan 模式后输入“我要做一个电商系统”，模型先问一个需求澄清问题。
- [ ] Plan 模式下模型给选择题时，前端仍能显示 `cncode-options` 选择栏。
- [ ] 工具失败时，模型阅读失败结果并调整下一步。
- [ ] 普通聊天问题仍能简洁中文回答。

## 9. 构建验证

- [ ] `.\gradlew.bat check --console=plain` 通过。
- [ ] `.\gradlew.bat installDist --console=plain` 通过。
