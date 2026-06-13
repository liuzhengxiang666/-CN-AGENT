# CN Code TUI UI 优化任务拆解

## 1. 文件清单

预计修改：

- `src/main/java/cncode/tui/TuiState.java`
- `src/main/java/cncode/tui/TuiChatApp.java`
- `src/main/java/cncode/tui/TuiRenderer.java`
- `src/test/java/cncode/tui/TuiStateTest.java`
- `docs/specs/cncode-tui-ui-polish/spec.md`
- `docs/specs/cncode-tui-ui-polish/plan.md`
- `docs/specs/cncode-tui-ui-polish/task.md`
- `docs/specs/cncode-tui-ui-polish/checklist.md`

## 2. 有序任务

### T1. 写入本次 UI 优化文档

步骤：

1. 创建 `docs/specs/cncode-tui-ui-polish/`。
2. 写入 `spec.md`。
3. 写入 `plan.md`。
4. 写入 `task.md`。
5. 写入 `checklist.md`。

验证：

- 四份文档存在。
- 文档内容与本次 UI 优化范围一致。

### T2. 调整输入快捷键

步骤：

1. 修改 `TuiChatApp.handleInput(...)`。
2. Enter 改为发送消息。
3. Ctrl+Enter 改为插入换行。
4. 保持 `exit` / `quit` 退出逻辑。

验证：

- Enter 触发 `sendInput()`。
- Ctrl+Enter 触发 `insertNewline()`。
- 发送后输入框清空。

### T3. 增加横向偏移状态

步骤：

1. 在 `TuiState` 中新增 `horizontalOffset`。
2. 新增 `horizontalOffset()`。
3. 新增 `moveTextLeft()`。
4. 新增 `moveTextRight(int maxOffset)`。
5. 确保偏移不小于 0，不超过最大值。

验证：

- 连续左移不会小于 0。
- 连续右移不会超过最大偏移。
- `TuiStateTest` 覆盖偏移逻辑。

### T4. 接入左右调整快捷键

步骤：

1. 在 `TuiChatApp.handleInput(...)` 处理 `Ctrl+ArrowLeft`。
2. 在 `TuiChatApp.handleInput(...)` 处理 `Ctrl+ArrowRight`。
3. Ctrl+Left 调用 `state.moveTextLeft()`。
4. Ctrl+Right 根据当前终端宽度计算最大偏移并调用 `state.moveTextRight(maxOffset)`。

验证：

- Ctrl+Left 不再只移动输入光标。
- Ctrl+Right 不再只移动输入光标。
- 普通 Left/Right 仍移动输入光标。

### T5. 优化 TUI 视觉渲染

步骤：

1. `TuiRenderer` 增加顶部标题栏。
2. 优化历史区域分隔线。
3. 优化输入框边界和提示文本。
4. 状态栏保持 `default    protocol/model`。
5. 渲染历史和输入时应用 `horizontalOffset`。
6. 确保宽度不足时不会抛出字符串越界异常。

验证：

- TUI 启动后有标题栏。
- 输入提示清晰。
- 消息角色区分更明显。
- 横向偏移影响历史和输入区域。

### T6. 回归测试

步骤：

1. 更新 `TuiStateTest`。
2. 运行 `.\gradlew.bat check`。
3. 运行 `.\gradlew.bat installDist`。
4. 非交互 fallback 验证 `echo exit | cncode.bat`。

验证：

- Gradle check 通过。
- installDist 通过。
- fallback 仍能正常退出。
