# CN Code 纯对话 TUI MVP 技术设计

## 1. 架构概览

CN Code 第一阶段采用分层结构：

- CLI 启动层：负责启动 `cncode` 命令并进入程序入口。
- REPL 层：负责终端交互、读取用户输入、打印流式回复。
- 会话层：负责维护当前进程内的多轮消息历史。
- 配置层：负责读取 `~/.cncode/config.yaml` 并校验字段。
- Provider 层：通过统一接口屏蔽 OpenAI 和 Anthropic 的协议差异。
- SSE 层：负责接收 HTTP 流式响应并解析增量文本。

主流程只依赖统一 Provider 接口，不直接关心 OpenAI 或 Anthropic 的请求格式。

## 2. 模块划分

### app

程序入口模块。

职责：

- 加载配置
- 创建 Provider
- 启动 REPL
- 处理启动阶段错误

### config

配置模块。

核心对象：

- `AppConfig`
- `ConfigLoader`

职责：

- 从 `~/.cncode/config.yaml` 读取 YAML
- 校验 `protocol`、`model`、`base_url`、`api_key`
- 避免在错误信息中泄露完整 API Key

### repl

终端交互模块。

核心对象：

- `ChatRepl`

职责：

- 显示欢迎语和提示符
- 读取单行输入
- 识别 `exit` / `quit`
- 调用会话和 Provider
- 打印流式回复
- Provider 调用失败时打印错误并继续下一轮

### chat

会话模型模块。

核心对象：

- `ChatMessage`
- `ChatRole`
- `ChatSession`

职责：

- 保存当前进程内的用户消息和助手消息
- 为 Provider 提供完整历史
- 在收到完整助手回复后写入历史

### provider

Provider 抽象模块。

核心对象：

- `ChatProvider`
- `ProviderFactory`
- `StreamHandler`
- `ProviderException`

职责：

- 统一 OpenAI 和 Anthropic 的流式聊天接口
- 根据 `protocol` 创建具体 Provider
- 为后续 Provider 扩展保留边界

### provider.openai

OpenAI 协议实现。

职责：

- 构造 OpenAI chat/completions 或 responses 兼容请求
- 通过 SSE 解析增量文本
- 将增量文本传给 REPL 输出

### provider.anthropic

Anthropic Claude 协议实现。

职责：

- 构造 Anthropic messages 请求
- 通过 SSE 解析增量文本
- 保留 extended thinking 的配置和接口扩展点，但本阶段不启用

## 3. 核心接口

### ChatProvider

```text
interface ChatProvider {
    void streamChat(ChatRequest request, StreamHandler handler) throws ProviderException;
}
```

### ChatRequest

包含：

- `model`
- `messages`
- 未来扩展字段，例如 thinking 配置

### StreamHandler

包含：

- `onDelta(String text)`
- `onComplete()`
- `onError(Exception error)`

REPL 使用 `onDelta` 立即打印内容，同时累积完整助手回复，完成后写入 `ChatSession`。

## 4. 数据结构

### AppConfig

字段：

- `protocol`
- `model`
- `baseUrl`
- `apiKey`

### ChatMessage

字段：

- `role`
- `content`

### ChatRole

枚举：

- `USER`
- `ASSISTANT`
- `SYSTEM`，预留

## 5. 技术决策

- 构建工具优先使用 Gradle，便于生成可执行命令和管理依赖。
- YAML 解析使用成熟 Java YAML 库，例如 SnakeYAML。
- HTTP 客户端优先使用 Java 标准 `HttpClient`，减少依赖。
- SSE 解析第一版在 Provider 内完成，后续可抽到公共模块。
- REPL 第一版使用标准输入输出，不引入完整 TUI 框架。
- 多轮历史只保存在内存中，不落盘。

## 6. 错误处理策略

- 配置文件不存在：启动失败并提示创建 `~/.cncode/config.yaml`。
- 配置字段缺失：启动失败并指出缺失字段。
- 未知 `protocol`：启动失败并列出支持值。
- Provider 请求失败：打印错误，返回提示符。
- SSE 解析失败：打印错误，返回提示符。
- API Key 不完整展示，只显示配置项存在或缺失。

## 7. Claude extended thinking 扩展点

本阶段不实际发送 extended thinking 参数。

设计上保留：

- `ChatRequest` 的扩展字段位置
- Anthropic Provider 内部构造请求时的扩展区域
- 后续可从 YAML 增加 thinking 配置，而不影响 REPL 主流程

## 8. 风险与缓解

- OpenAI 和 Anthropic SSE 事件格式不同：分别在 Provider 内隔离解析。
- 终端流式输出可能出现换行混乱：REPL 统一在完成后补换行。
- 多轮历史过长可能导致请求失败：本阶段不做裁剪，后续优化。
- 用户配置错误较常见：配置校验和中文错误提示要优先完成。
