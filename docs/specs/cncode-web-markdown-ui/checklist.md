# CN Code Web Markdown UI 验收清单

## 1. Markdown

- [ ] JS 中存在 `escapeHtml`。
- [ ] JS 中存在 `renderMarkdown`。
- [ ] JS 中存在 `renderInlineMarkdown`。
- [ ] assistant delta 会累积原始 Markdown。
- [ ] assistant body 使用 `innerHTML = renderMarkdown(...)`。
- [ ] 用户消息不使用 Markdown 渲染。
- [ ] `<script>` 会被转义，不会执行。

## 2. 样式

- [ ] 页面为浅色主题。
- [ ] 主聊天区居中。
- [ ] 用户消息右对齐并显示为气泡。
- [ ] 助手消息显示为正文样式。
- [ ] 输入框在底部，圆角，接近 ChatGPT 风格。
- [ ] 代码块有独立背景和横向滚动。
- [ ] 移动端隐藏 sidebar 或保持可用。

## 3. 工具事件

- [ ] `tool_start` 显示为 system 消息。
- [ ] 成功 `tool_result` 显示为 system 消息。
- [ ] 失败 `tool_result` 显示为 error 消息。
- [ ] `error` 事件显示为 error 消息。

## 4. 构建

- [ ] `.\gradlew.bat check` 通过。
- [ ] `.\gradlew.bat installDist` 通过。
