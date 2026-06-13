# CN Code TUI UI 优化技术设计

## 1. 架构概览

本次只修改 TUI 层，不改 Provider、配置读取、会话模型和 REPL fallback。

主要改动点：

- `TuiState`：新增文本横向偏移状态。
- `TuiChatApp`：调整键盘事件逻辑。
- `TuiRenderer`：升级视觉布局，并应用文本横向偏移。
- `TuiStateTest`：补充偏移和输入行为测试。

## 2. 输入行为调整

现有行为：

- Enter：换行
- Ctrl+Enter：发送

新行为：

- Enter：发送
- Ctrl+Enter：换行

实现方式：

- 在 `TuiChatApp.handleInput(...)` 中调整 `KeyType.Enter` 分支。
- `key.isCtrlDown()` 为 true 时调用 `state.insertNewline()`。
- 否则调用 `sendInput()`。

## 3. 横向偏移设计

在 `TuiState` 中新增：

- `horizontalOffset`
- `moveTextLeft()`
- `moveTextRight()`
- `horizontalOffset()`

快捷键：

- `Ctrl+Left` 调用 `moveTextLeft()`
- `Ctrl+Right` 调用 `moveTextRight()`

边界：

- 最小偏移为 `0`
- 最大偏移根据终端宽度限制，例如 `max(0, width / 3)`

`TuiRenderer` 渲染历史区域和输入区域时，在文本前增加对应空格偏移。

## 4. 视觉优化设计

`TuiRenderer` 调整为更清晰的三段式布局：

- 顶部标题栏：显示 `CN Code`
- 中间历史区域：显示对话消息
- 下方输入框：带边框和提示
- 底部状态栏：显示 `default    protocol/model`

消息样式：

- 用户消息：使用 `› 你` 前缀，青色或加粗。
- AI 消息：使用 `● AI` 前缀，白色。
- 系统消息：使用 `• 系统` 前缀，黄色。
- 每条消息之间保留空行。

边界样式：

- 使用横线分隔标题、历史、输入和状态栏。
- 输入框显示提示，例如 `Enter 发送 | Ctrl+Enter 换行 | Ctrl+←/→ 调整位置`。

## 5. 回归影响

需要确认：

- 发送后输入框仍清空。
- `exit` / `quit` 仍可退出。
- 流式回复仍自动滚动到底部。
- 鼠标滚轮仍能滚动历史。
- 非交互环境仍 fallback 到 REPL。
