# CN Code TUI 对话界面技术设计

## 1. 架构概览

在现有纯对话 MVP 基础上新增 TUI 层，不改 Provider 抽象和配置读取主模型。

启动流程调整为：

1. `Main` 读取配置。
2. 创建 `ChatProvider` 和 `ChatSession`。
3. 优先创建并启动 `TuiChatApp`。
4. 如果 TUI 初始化或运行失败，打印提示并启动现有 `ChatRepl` fallback。

TUI 层只负责界面、输入和渲染。模型调用仍通过 `ChatProvider.streamChat(...)` 完成。

## 2. 模块划分

### app

修改：

- `Main`

职责：

- 保持配置加载和 Provider 创建流程。
- 优先启动 TUI。
- 捕获 TUI 初始化失败并 fallback 到 `ChatRepl`。

### tui

新增 TUI 模块。

核心类：

- `TuiChatApp`
- `TuiMessage`
- `TuiMessageRole`
- `TuiState`
- `TuiRenderer`

职责：

- 初始化 Lanterna screen。
- 绘制历史对话区域、多行输入框和状态栏。
- 处理键盘输入、Ctrl+Enter 发送、鼠标滚轮滚动。
- 在模型流式输出时刷新历史消息区域。
- 维护 UI 展示状态，例如 scroll offset 和当前输入内容。

### chat

复用：

- `ChatSession`
- `ChatMessage`
- `ChatRole`

职责不变：

- 保存真实 Provider 请求所需的多轮上下文。
- TUI 发送用户消息时写入 session。
- 助手回复完成后写入 session。

### provider

复用：

- `ChatProvider`
- `ChatRequest`
- `StreamHandler`
- `ProviderException`

职责不变：

- TUI 不直接依赖具体 Provider。

## 3. Lanterna 布局设计

使用 Lanterna `Screen` 低层 API 手动绘制，避免 GUI 层组件对流式刷新和鼠标滚动的限制。

终端区域划分：

- 第 1 行到输入框上方：历史对话区域
- 底部倒数第 3 到倒数第 2 行：多行输入框
- 最后一行：状态栏

第一版输入框高度固定为 3 行。

当终端高度不足时：

- 尽量显示最小布局。
- 如果无法满足最小高度，抛出 TUI 初始化失败，由 `Main` fallback 到 REPL。

## 4. 输入处理

键盘规则：

- 普通字符：追加到当前输入缓冲。
- Enter：在输入缓冲中插入换行。
- Ctrl+Enter：发送当前输入。
- Backspace：删除前一个字符。
- Delete：删除当前字符，第一版可选。
- 方向键：移动输入光标，第一版至少支持左右移动。
- `exit` / `quit`：作为完整消息发送时退出程序。

鼠标规则：

- 鼠标滚轮向上：历史区域向上滚动。
- 鼠标滚轮向下：历史区域向下滚动。
- 流式输出时自动滚动到底部。

## 5. 流式调用策略

用户按 Ctrl+Enter 发送后：

1. TUI 将用户消息加入展示列表。
2. TUI 将用户消息加入 `ChatSession`。
3. TUI 创建一条空的 assistant 展示消息。
4. 后台线程调用 `ChatProvider.streamChat(...)`。
5. `onDelta` 收到文本片段后追加到 assistant 展示消息。
6. 每次 delta 后触发重新渲染并自动滚动到底部。
7. `onComplete` 后将完整 assistant 回复写入 `ChatSession`。
8. 如果 Provider 失败，将错误作为系统提示显示在历史区域，并保持程序可继续输入。

为了避免阻塞 UI 输入循环，Provider 调用放到后台线程执行。

## 6. 状态栏

状态栏显示：

```text
default    protocol/model
```

数据来源：

- `protocol` 来自 `AppConfig.protocol()`
- `model` 来自 `AppConfig.model()`
- 配置名第一版固定为 `default`

## 7. fallback 策略

`Main` 中 TUI 启动失败时：

1. 打印中文提示，例如：“TUI 启动失败，已切换到基础 REPL。”
2. 启动现有 `ChatRepl`。
3. 复用同一个 `ChatSession`，保证结构一致。

## 8. 依赖选择

新增依赖：

- `com.googlecode.lanterna:lanterna`

继续使用：

- Java 21
- Java 标准 `HttpClient`
- 当前 Provider 抽象

## 9. 风险与缓解

- Windows 终端鼠标事件支持不一致：优先在 Windows Terminal / PowerShell 验证；不支持时仍可自动滚动到底部。
- Ctrl+Enter 在不同终端可能编码不同：实现时需要实际测试 Lanterna key event。
- 流式线程和 UI 渲染存在并发风险：所有 UI 状态更新使用同步块或单线程事件队列。
- TUI 初始化失败会影响启动体验：保留 REPL fallback。
