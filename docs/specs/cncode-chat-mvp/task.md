# CN Code 纯对话 TUI MVP 任务拆解

## 1. 文件清单

预计创建或修改：

- `settings.gradle`
- `build.gradle`
- `src/main/java/cncode/app/Main.java`
- `src/main/java/cncode/config/AppConfig.java`
- `src/main/java/cncode/config/ConfigLoader.java`
- `src/main/java/cncode/chat/ChatMessage.java`
- `src/main/java/cncode/chat/ChatRole.java`
- `src/main/java/cncode/chat/ChatSession.java`
- `src/main/java/cncode/provider/ChatProvider.java`
- `src/main/java/cncode/provider/ChatRequest.java`
- `src/main/java/cncode/provider/StreamHandler.java`
- `src/main/java/cncode/provider/ProviderException.java`
- `src/main/java/cncode/provider/ProviderFactory.java`
- `src/main/java/cncode/provider/openai/OpenAiProvider.java`
- `src/main/java/cncode/provider/anthropic/AnthropicProvider.java`
- `src/main/java/cncode/repl/ChatRepl.java`
- `src/test/java/...`，用于配置、会话、Provider 工厂等单元测试
- `docs/specs/cncode-chat-mvp/checklist.md`

## 2. 有序任务

### T1. 初始化 Java/Gradle 项目骨架

步骤：

1. 创建 Gradle Java application 项目结构。
2. 配置主类为 `cncode.app.Main`。
3. 配置应用名或启动脚本，使安装后命令名为 `cncode`。
4. 添加必要依赖：YAML 解析、JSON 处理、测试框架。

验证：

- `gradle test` 能运行。
- `gradle run` 能进入主类或显示占位启动信息。

### T2. 实现配置读取与校验

步骤：

1. 创建 `AppConfig`。
2. 创建 `ConfigLoader`。
3. 默认读取 `~/.cncode/config.yaml`。
4. 解析字段 `protocol`、`model`、`base_url`、`api_key`。
5. 对缺失文件、缺失字段、空字段给出中文错误。
6. 确保错误信息不完整打印 API Key。

验证：

- 配置存在时能得到完整 `AppConfig`。
- 配置缺失或字段缺失时抛出可读错误。
- 单元测试覆盖成功和失败场景。

### T3. 实现会话模型

步骤：

1. 创建 `ChatRole`。
2. 创建 `ChatMessage`。
3. 创建 `ChatSession`。
4. 支持追加用户消息、追加助手消息、获取完整历史。

验证：

- 多条消息按顺序保存。
- 获取历史时不会破坏内部状态。
- 单元测试覆盖基础会话行为。

### T4. 定义 Provider 抽象

步骤：

1. 创建 `ChatProvider`。
2. 创建 `ChatRequest`。
3. 创建 `StreamHandler`。
4. 创建 `ProviderException`。
5. 创建 `ProviderFactory`。
6. 根据 `protocol` 创建 OpenAI 或 Anthropic Provider。
7. 未知 `protocol` 返回中文错误。

验证：

- `openai` 创建 OpenAI Provider。
- `anthropic` 创建 Anthropic Provider。
- 未知协议失败且错误清晰。
- REPL 层不依赖具体 Provider 类。

### T5. 实现 OpenAI Provider 流式调用

步骤：

1. 使用 Java `HttpClient` 构造请求。
2. 根据 `AppConfig` 使用 `base_url`、`model`、`api_key`。
3. 将 `ChatMessage` 历史转换为 OpenAI 消息格式。
4. 发送启用 stream 的请求。
5. 解析 SSE 增量文本。
6. 通过 `StreamHandler.onDelta` 输出文本片段。
7. 请求完成时调用 `onComplete`。

验证：

- 可通过 mock 或轻量测试验证请求体结构。
- 可解析典型 OpenAI SSE 片段。
- 网络或响应异常包装成 `ProviderException`。

### T6. 实现 Anthropic Provider 流式调用

步骤：

1. 使用 Java `HttpClient` 构造请求。
2. 根据 `AppConfig` 使用 `base_url`、`model`、`api_key`。
3. 将 `ChatMessage` 历史转换为 Anthropic messages 格式。
4. 发送启用 stream 的请求。
5. 解析 Anthropic SSE 增量文本事件。
6. 通过 `StreamHandler.onDelta` 输出文本片段。
7. 保留 extended thinking 扩展位置，但不发送 thinking 参数。

验证：

- 可通过 mock 或轻量测试验证请求体结构。
- 可解析典型 Anthropic SSE 片段。
- 网络或响应异常包装成 `ProviderException`。

### T7. 实现基础 REPL

步骤：

1. 创建 `ChatRepl`。
2. 启动时打印欢迎语。
3. 循环读取单行输入。
4. 识别 `exit` / `quit` 并退出。
5. 用户输入后追加到 `ChatSession`。
6. 构造 `ChatRequest` 调用 Provider。
7. 将 `onDelta` 立即打印到终端并刷新。
8. 累积完整助手回复，完成后写入会话历史。
9. Provider 失败时打印中文错误并返回提示符。

验证：

- 可用 fake Provider 测试 REPL 行为。
- 流式片段会按顺序打印。
- 出错后继续等待输入。
- `exit` / `quit` 正常退出。

### T8. 接入 Main 主流程

步骤：

1. `Main` 加载配置。
2. 根据配置创建 Provider。
3. 创建 `ChatSession` 和 `ChatRepl`。
4. 启动 REPL。
5. 启动阶段错误打印中文提示并退出。

验证：

- 配置缺失时启动失败且提示清楚。
- 配置合法时进入 REPL。
- 未知协议时启动失败且提示支持值。

### T9. 端到端手动验收准备

步骤：

1. 创建示例配置说明或在 checklist 中写明配置格式。
2. 确认 `gradle installDist` 或等价方式能生成 `cncode` 启动脚本。
3. 准备 tmux 测试命令。
4. 对照 checklist 执行真实 OpenAI 或 Anthropic 流式对话测试。

验证：

- tmux 中能启动 CN Code。
- 能输入真实问题并看到流式回复。
- 多轮追问能参考上下文。
- 失败场景能回到提示符。
