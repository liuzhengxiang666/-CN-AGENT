# CN Code System Prompt 规格说明

## 1. 背景与目标

CN Code 当前的 Agent 系统提示主要写在 `AgentLoop` 内部，Plan 模式提示也直接拼在同一个 system prompt 中。随着工具系统、Plan 模式、Web UI、后续 Skill/Memory/Team/Worktree 等能力继续增加，把所有规则塞进一个字符串会带来几个问题：

- 全局指令职责混杂，后续插入新规则容易互相覆盖。
- 稳定规则和动态环境混在一起，每轮环境变化都会让缓存前缀不稳定。
- 工具描述和全局指令没有形成可维护的双重约束。
- Plan 模式、临时提醒、外部工具上线等会话级变化缺少统一的注入形式。
- Provider 侧 prompt cache 是否命中没有可观测入口。

本阶段目标是建立 CN Code 的 System Prompt 拼装管线：把稳定全局指令拆成按优先级排序的模块，把变化环境和动态补充从全局 prompt 中剥离出来，通过 `<system-reminder>` 形式注入对话通道。这样既能让模型行为更稳定，也给后续章节继续插入 Skill、Memory、权限、团队等模块留出位置。

## 2. 功能需求

### F1. 模块化全局指令

系统必须把全局指令按职责拆成多个稳定 section，并按优先级拼装。

至少包含：

- 身份与目标
- 行为准则
- 工具使用规则
- 代码规范
- 安全边界
- 任务模式
- 输出风格

section 顺序必须稳定，后续新增 section 时通过 priority 排序，而不是依赖手写拼接顺序。

### F2. 稳定内容与变化内容分离

稳定内容包括：

- CN Code 身份
- 行为规则
- 工具使用规则
- 代码规范
- 安全边界
- 输出风格
- 工具描述

变化内容包括：

- 当前工作目录
- 操作系统
- 当前日期时间
- Git 仓库状态
- 当前模式提醒
- 动态补充指令
- 对话历史

稳定内容走可缓存的 system prompt；变化内容走对话通道或动态 reminder，避免破坏缓存前缀。

### F3. 环境信息独立注入

工作目录、操作系统、时间、Git 状态等环境信息不得直接写进稳定全局指令。

Agent 每次运行前应构造一条系统级补充消息，作为对话首条或靠前的 `<system-reminder>` 注入，让模型知道当前环境，但不污染稳定 system prompt。

### F4. 工具规则双重强化

关键工具规则必须同时出现在：

- 全局工具使用 section
- 工具自身 description

至少强化：

- 需要本地信息时优先调用专用工具，而不是凭空猜测。
- 读文件要用读文件工具。
- 搜索文件名要用找文件工具。
- 搜索代码内容要用搜代码工具。
- 修改文件前必须先读相关文件。
- 写入和命令类操作必须遵守 Plan 模式和安全边界。

### F5. `<system-reminder>` 动态消息

系统必须支持一种特殊标签消息形式：

```text
<system-reminder>
...
</system-reminder>
```

它用于运行中向模型注入补充指令，例如：

- 当前环境信息
- 当前 Plan 模式提醒
- 外部工具上线
- 温和行为提示

模型必须被告知：`<system-reminder>` 是系统提示，不是用户请求，不应当直接向用户复述。

### F6. 会话级开关动态注入

Plan 模式这类会话级开关不能写死在全局 system prompt 中。

Plan 模式应在每轮 Agent 调用时按节奏注入：

- 首轮注入完整提醒。
- 每隔固定轮数重新注入完整提醒。
- 其他轮次注入精简提醒。

这样既能维持模型状态，又避免每轮重复大量动态内容。

### F7. 缓存策略可观测

Provider 如果返回缓存命中相关字段，系统应能解析并记录。

本阶段至少要为缓存统计预留数据结构和接入点；如果当前 DeepSeek/OpenAI-compatible 响应没有缓存字段，必须安全降级，不影响对话。

### F8. 典型行为场景评估

本阶段需要准备一组人工对比场景，用于定性判断 prompt 是否有效。

至少包含：

- 需要读本地文件时是否调用 `read_file`。
- 修改文件前是否先读。
- Plan 模式是否先澄清需求。
- 工具失败时是否阅读结果并调整。
- 普通聊天是否保持简洁中文输出。

### F9. 接入现有 AgentLoop

当前 `AgentLoop` 不应继续手写完整 system prompt。

AgentLoop 应调用新的 prompt 组件获得稳定 system prompt，并在每轮把环境 reminder、Plan reminder 等动态消息合并进发送给 Provider 的消息列表。

### F10. 不改变现有工具执行语义

本阶段只改 prompt 拼装和动态注入，不改变六个核心工具的执行逻辑、不新增工具类型、不改变 Web UI 工具活动流。

## 3. 非功能需求

### N1. Java 21

实现继续使用 Java 21，不引入额外依赖。

### N2. 可缓存稳定性

稳定 system prompt 在同一工作目录、同一工具集、同一项目规则下应保持字节级稳定。

环境时间、Git 状态、Plan 模式等变化不得改变稳定 system prompt 字符串。

### N3. 低侵入

优先新增 `cncode.prompt` 包和少量 Provider 统计结构，不重写 AgentLoop、Provider、ToolRegistry 的主体架构。

### N4. 安全降级

Git 不存在、非 Git 仓库、缓存字段不存在、环境变量不存在时，系统必须正常运行，不输出误导性错误。

### N5. 中文体验

对用户输出和系统内部提示默认使用中文。与模型协议相关的标签名可以保持英文，例如 `<system-reminder>`。

### N6. 测试可覆盖

Prompt section 排序、空 section 过滤、环境 reminder、Plan reminder 注入节奏、AgentLoop 接入都必须能用 smoke 测试覆盖。

## 4. 不做的事

本阶段明确不做：

1. 不实现 Skill Loader。
2. 不实现 Memory 系统。
3. 不实现复杂权限审批。
4. 不实现 Hook 引擎。
5. 不实现 Team/SubAgent/Worktree prompt 分支。
6. 不实现真正的跨请求缓存存储。
7. 不强依赖某个 Provider 的缓存字段。
8. 不改变工具调用协议。
9. 不改变 Web UI 的工具活动流。
10. 不把环境信息写死进稳定 system prompt。
11. 不把 Plan 模式规则永久写入全局 prompt。

## 5. 验收标准

### A1. 模块化 prompt

存在独立 prompt 组件，能按 priority 拼装多个 section，输出顺序稳定。

### A2. 稳定与动态分离

稳定 system prompt 不包含当前时间、Git 状态、当前 Plan 模式等动态内容。

### A3. 环境 reminder

Agent 请求中包含 `<system-reminder>` 环境补充消息，内容含工作目录、操作系统、时间和 Git 状态。

### A4. Plan reminder

Plan 模式下，首轮注入完整 Plan reminder；普通轮次注入精简 reminder；达到间隔轮次后重新注入完整 reminder。

### A5. 工具规则强化

全局工具 section 和工具 description 都能看到“优先专用工具、编辑前先读”等关键规则。

### A6. AgentLoop 接入

`AgentLoop` 不再直接维护大段固定 system prompt，而是调用 prompt 组件。

### A7. 缓存统计接入点

Provider 层具备缓存命中统计字段或解析占位，缺字段时不报错。

### A8. 构建验证

`.\gradlew.bat check --console=plain` 通过。

`.\gradlew.bat installDist --console=plain` 通过。
