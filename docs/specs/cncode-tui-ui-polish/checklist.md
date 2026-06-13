# CN Code TUI UI 优化验收清单

## 1. 输入行为

- [ ] 在 TUI 输入框输入一行文本后，按 Enter 会发送消息。
- [ ] 发送后输入框清空。
- [ ] 在 TUI 输入框输入文本后，按 Ctrl+Enter 会插入换行。
- [ ] Ctrl+Enter 不会发送消息。
- [ ] 输入完整 `exit` 后按 Enter，程序正常退出。
- [ ] 输入完整 `quit` 后按 Enter，程序正常退出。

## 2. 横向偏移

- [ ] 按 Ctrl+Right 后，历史区域文本整体向右移动。
- [ ] 按 Ctrl+Right 后，输入区域文本整体向右移动。
- [ ] 按 Ctrl+Left 后，历史区域文本整体向左移动。
- [ ] 按 Ctrl+Left 后，输入区域文本整体向左移动。
- [ ] 连续按 Ctrl+Left 不会让偏移小于 0。
- [ ] 连续按 Ctrl+Right 不会让主要内容完全不可见。
- [ ] 普通 Left/Right 仍用于移动输入光标。

## 3. 视觉效果

- [ ] TUI 顶部显示 `CN Code` 标题。
- [ ] 历史区域、输入区域、状态栏边界清晰。
- [ ] 输入框显示操作提示。
- [ ] 用户消息、AI 消息、系统消息视觉上可区分。
- [ ] 状态栏仍显示 `default    protocol/model`。
- [ ] 终端宽度变化或较窄时不会出现字符串越界崩溃。

## 4. 功能回归

- [ ] 真实模型回复仍能流式显示。
- [ ] 流式回复时仍自动滚动到底部。
- [ ] 鼠标滚轮仍能滚动历史。
- [ ] 多轮上下文仍有效。
- [ ] Provider 调用失败后仍显示错误并允许继续输入。
- [ ] 非交互环境仍 fallback 到基础 REPL。

## 5. 构建验证

- [ ] `.\gradlew.bat check` 通过。
- [ ] `.\gradlew.bat installDist` 通过。
- [ ] `cmd /c "echo exit| build\install\cncode\bin\cncode.bat"` 能正常退出。
