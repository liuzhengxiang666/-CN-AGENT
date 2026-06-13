# CN Code 纯对话 TUI MVP 规格说明

## 1. 背景与目标

CN Code 是一个使用 Java 开发的终端 AI 编程助手，定位类似 Claude Code。当前阶段目标不是实现完整 Coding Agent，而是先完成一个可运行的纯对话 MVP。

用户在终端执行 `cncode` 后，进入基础交互式对话界面。用户可以输入一行问题，CN Code 调用配置中的大模型 API，并通过 SSE 流式接收回复，将模型输出逐字或逐块打印到终端。程序在当前进程内保存多轮对话历史，使 AI 能参考之前的上下文继续回复。

本阶段需要支持 OpenAI 和 Anthropic Claude 两种 API 后端，并通过 `~/.cncode/config.yaml` 中的 YAML 配置切换。Provider 层需要抽象成统一接口，方便后续增加新的模型后端。

本阶段明确只做纯对话能力，不做 tool use、文件操作、代码编辑、命令执行或自动修改项目文件等 Agent 功能。

## 2. 功能需求

### F1. 启动命令

用户可以在终端执行 `cncode` 启动 CN Code。

启动成功后，程序进入基础 REPL 对话界面，并显示欢迎语和输入提示符。

### F2. 基础 REPL 输入

用户可以在提示符后输入一行自然语言请求。

程序读取用户输入后，将该输入追加到当前会话历史，并调用配置中指定的 LLM Provider 生成回复。

### F3. 流式回复输出

CN Code 必须使用 SSE 流式接收模型回复。

模型回复到达时，程序应逐字或逐块打印到终端，而不是等待完整回复生成后一次性输出。

回复结束后，程序打印换行并重新显示输入提示符。

### F4. 多轮对话记忆

CN Code 在当前进程内保存用户消息和助手消息历史。

同一次启动期间，后续请求必须携带之前的对话上下文，使模型能够基于前文继续回答。

程序退出后，本阶段不保存会话历史。

### F5. 退出命令

用户输入 `exit` 或 `quit` 时，程序应正常退出。

退出时不需要保存历史。

### F6. YAML 配置读取

CN Code 默认从 `~/.cncode/config.yaml` 读取 LLM 配置。

配置文件至少包含四个核心字段：

```yaml
protocol: openai
model: gpt-4.1
base_url: https://api.openai.com/v1
api_key: sk-...
```

字段含义：

- `protocol`：决定使用哪种协议和 Provider，例如 `openai` 或 `anthropic`
- `model`：指定调用的模型名称
- `base_url`：指定 API 请求地址
- `api_key`：用于认证

### F7. Provider 后端切换

用户可以通过修改 `protocol` 在 OpenAI 和 Anthropic Claude 两种 API 后端之间切换。

程序启动时根据配置选择对应 Provider。

如果 `protocol` 不受支持，程序应给出清晰错误信息并退出。

### F8. 统一 Provider 接口

OpenAI Provider 和 Anthropic Provider 必须通过统一接口暴露流式聊天能力。

该接口应支持传入模型、历史消息和当前用户请求，并以流式方式返回助手回复片段。

接口设计应允许后续新增其他 Provider，而不需要改动 REPL 主流程。

### F9. Claude extended thinking 扩展点

本阶段不实际启用 Claude extended thinking。

Anthropic Provider 的设计应保留后续接入 extended thinking 的扩展点，避免未来需要大规模重构。

### F10. 流式错误处理

当 SSE 请求、认证、网络连接、响应解析或 Provider 调用失败时，程序应在终端打印清晰错误信息。

错误发生后程序不应直接退出，而应返回输入提示符，允许用户继续输入下一轮请求。

## 3. 非功能需求

### N1. 实现语言

CN Code 使用 Java 实现。

项目代码、模块命名和构建方式应符合常见 Java 项目习惯。

### N2. 中文交互约定

CN Code 项目相关回答、文档说明和代码注释优先使用中文。

终端提示语和错误信息第一版使用中文。

### N3. 流式响应体验

模型回复应尽快显示到终端。

程序不得在收到完整响应后才开始打印内容。

### N4. 可扩展性

Provider 层必须与 REPL 主流程解耦。

新增 Provider 时，应尽量只新增 Provider 实现和配置映射，不修改核心对话循环。

### N5. 配置安全

`api_key` 从本地配置文件读取，不应硬编码在代码中。

程序输出错误信息时，不应完整打印 API Key。

### N6. 可诊断性

配置缺失、配置格式错误、未知 `protocol`、认证失败、网络失败、SSE 解析失败等问题，应给出用户可理解的错误信息。

### N7. 简洁 MVP

本阶段优先保证纯对话链路可运行、可测试、可扩展。

不引入 tool use、文件编辑、代码修改、项目索引、命令执行等 Agent 功能。

### N8. 端到端可验收

开发完成后，应能在 tmux 中启动 CN Code，输入真实对话请求，并对照 `checklist.md` 完成端到端验收。

## 4. 不做的事

本阶段明确不实现以下能力：

1. 不实现 tool use。
2. 不读取、搜索、创建、修改或删除用户项目文件。
3. 不执行 shell 命令。
4. 不实现代码编辑、补丁生成或自动重构。
5. 不实现项目索引、代码库理解或代码导航。
6. 不实现持久化会话历史。
7. 不实现多行输入、输入历史、快捷键、滚动窗口或完整 TUI 布局。
8. 不实际启用 Claude extended thinking，只保留后续扩展点。
9. 不实现多配置文件合并或项目级配置覆盖。
10. 不实现模型列表查询、余额查询、用量统计或成本估算。

## 5. 验收标准

### A1. 启动验收

在终端执行 `cncode` 后，程序成功启动，显示欢迎语和输入提示符，并等待用户输入。

### A2. 配置验收

当 `~/.cncode/config.yaml` 存在且包含合法的 `protocol`、`model`、`base_url`、`api_key` 字段时，程序能正确读取配置并选择对应 Provider。

当配置文件缺失、字段缺失或 `protocol` 不支持时，程序给出清晰错误信息。

### A3. OpenAI 流式对话验收

当配置 `protocol: openai` 且 API 信息有效时，用户输入真实问题后，CN Code 调用 OpenAI 协议接口，并将模型回复以流式方式打印到终端。

### A4. Anthropic 流式对话验收

当配置 `protocol: anthropic` 且 API 信息有效时，用户输入真实问题后，CN Code 调用 Anthropic 协议接口，并将模型回复以流式方式打印到终端。

### A5. 多轮上下文验收

用户先输入一条带有上下文的信息，再输入追问时，模型回复能够参考同一进程内的历史对话。

### A6. 退出验收

用户输入 `exit` 或 `quit` 后，程序正常退出。

### A7. 错误恢复验收

当 Provider 调用失败、认证失败、网络失败或 SSE 响应解析失败时，程序打印清晰错误信息，并回到输入提示符，而不是直接崩溃退出。

### A8. Provider 抽象验收

REPL 主流程不直接依赖 OpenAI 或 Anthropic 的具体请求格式。

新增 Provider 时，不需要重写主对话循环。

### A9. tmux 端到端验收

开发完成后，在 tmux 中启动 CN Code，输入真实对话请求，观察是否正确调用 Provider、流式输出回复，并对照 `checklist.md` 逐项验收。
