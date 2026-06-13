# CN Code TUI 对话界面任务拆解

## 1. 文件清单

预计创建或修改：

- `build.gradle`
- `src/main/java/cncode/app/Main.java`
- `src/main/java/cncode/tui/TuiChatApp.java`
- `src/main/java/cncode/tui/TuiMessage.java`
- `src/main/java/cncode/tui/TuiMessageRole.java`
- `src/main/java/cncode/tui/TuiState.java`
- `src/main/java/cncode/tui/TuiRenderer.java`
- `src/test/java/cncode/tui/...`
- `docs/specs/cncode-tui-chat/checklist.md`

## 2. 有序任务

### T1. 添加 Lanterna 依赖

步骤：

1. 在 `build.gradle` 中添加 `com.googlecode.lanterna:lanterna` 依赖。
2. 确认 Java 21 toolchain 保持不变。
3. 运行 Gradle 构建确认依赖能下载和编译。

验证：

- `.\gradlew.bat check` 能通过。
- 主项目仍能编译。

### T2. 定义 TUI 展示状态模型

步骤：

1. 创建 `TuiMessageRole`。
2. 创建 `TuiMessage`。
3. 创建 `TuiState`。
4. 在 `TuiState` 中维护展示消息列表、当前输入内容、滚动偏移和流式状态。

验证：

- 单元或冒烟测试覆盖消息追加、助手消息增量追加、输入清空、滚动偏移变更。

### T3. 实现 TUI 渲染器

步骤：

1. 创建 `TuiRenderer`。
2. 基于 Lanterna `Screen` 绘制历史区域。
3. 绘制多行输入框。
4. 绘制底部状态栏。
5. 对用户消息、助手消息、系统提示做基础区分。
6. 处理终端宽度下的文本截断或换行。

验证：

- 可使用 fake state 渲染不抛异常。
- 状态栏显示 `default    protocol/model`。
- 输入框能显示多行输入内容。

### T4. 实现 TUI 主循环

步骤：

1. 创建 `TuiChatApp`。
2. 初始化 Lanterna terminal 和 screen。
3. 开启鼠标捕获。
4. 读取键盘和鼠标事件。
5. 普通字符写入输入缓冲。
6. Enter 插入换行。
7. Ctrl+Enter 发送消息。
8. Backspace 删除字符。
9. 鼠标滚轮调整历史滚动偏移。
10. 循环刷新界面。

验证：

- 启动 TUI 不崩溃。
- 输入字符能显示在输入框。
- Enter 能插入换行。
- 鼠标滚轮事件不会导致异常。

### T5. 接入 Provider 流式调用

步骤：

1. Ctrl+Enter 发送时，将用户消息加入 TUI 展示列表和 `ChatSession`。
2. 创建空 assistant 展示消息。
3. 后台线程调用 `ChatProvider.streamChat(...)`。
4. `onDelta` 追加到当前 assistant 展示消息。
5. 每次 delta 后触发渲染并自动滚动到底部。
6. `onComplete` 后将完整助手回复写入 `ChatSession`。
7. Provider 异常时显示系统错误消息。

验证：

- 使用 fake Provider 验证流式片段按顺序显示。
- assistant 完整回复写入 `ChatSession`。
- Provider 失败后 UI 仍可继续输入。

### T6. 实现退出和并发保护

步骤：

1. 完整输入为 `exit` 或 `quit` 时退出 TUI。
2. Provider 正在响应时避免重复发送，或给出忙碌提示。
3. 对 TUI state 更新加同步保护。
4. TUI 退出时关闭 screen 并恢复终端状态。

验证：

- 发送 `exit` 或 `quit` 能正常退出。
- 流式响应期间重复发送不会破坏状态。
- 退出后终端显示恢复正常。

### T7. Main 接入 TUI 和 fallback

步骤：

1. 修改 `Main`，创建 `ChatSession` 后优先启动 `TuiChatApp`。
2. 捕获 TUI 初始化或运行异常。
3. 打印 fallback 提示。
4. 启动现有 `ChatRepl`。
5. 复用同一个 `ChatSession`。

验证：

- 正常终端优先进入 TUI。
- 模拟 TUI 失败时能进入 REPL。
- REPL fallback 保持原有能力。

### T8. 测试和端到端验收

步骤：

1. 补充 TUI 状态和 fake Provider 冒烟测试。
2. 运行 `.\gradlew.bat check`。
3. 运行 `.\gradlew.bat installDist`。
4. 启动 `cncode.bat`。
5. 使用真实 DeepSeek 配置输入真实问题。
6. 测试多轮上下文。
7. 测试鼠标滚轮和退出。

验证：

- 本阶段 `checklist.md` 能逐项验收。
