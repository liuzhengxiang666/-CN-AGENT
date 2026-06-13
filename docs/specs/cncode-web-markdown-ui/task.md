# CN Code Web Markdown UI 任务拆解

## 1. 文件清单

预计修改：

- `src/main/java/cncode/web/WebAssets.java`
- `src/test/java/cncode/web/WebAssetsTest.java`
- `build.gradle`

## 2. 有序任务

### T1. 更新文档

步骤：

1. 写入 `spec.md`。
2. 写入 `plan.md`。
3. 写入 `task.md`。
4. 写入 `checklist.md`。

验证：

- 四份文档存在。

### T2. 重写 Web CSS

步骤：

1. 改为浅色主题。
2. 对话区居中。
3. 用户消息右侧气泡。
4. 助手消息正文样式。
5. 输入框改为底部圆角栏。
6. 工具消息和错误消息区分。
7. 加入 Markdown 元素样式。

验证：

- `WebAssets.css()` 包含 `.markdown-body`、`.message.user`、`.composer`。

### T3. 实现 Markdown 渲染 JS

步骤：

1. 新增 `escapeHtml`。
2. 新增 `renderInlineMarkdown`。
3. 新增 `renderMarkdown`。
4. 新增 `setMarkdown`。
5. delta 事件累积原始 Markdown 后调用 `setMarkdown`。

验证：

- `WebAssets.js()` 包含 `renderMarkdown`。
- `WebAssets.js()` 包含 `escapeHtml`。

### T4. 调整事件展示

步骤：

1. assistant 消息使用 Markdown body。
2. user 消息使用纯文本。
3. tool_start 使用低调 system 消息。
4. tool_result 成功使用 system，失败使用 error。
5. thinking/turn/loop 继续安全忽略。

验证：

- 工具成功不会使用 error 样式。
- 错误事件仍用 error 样式。

### T5. 测试与构建

步骤：

1. 新增 `WebAssetsTest`。
2. 将测试加入 `smokeTest`。
3. 运行 `.\gradlew.bat check`。
4. 运行 `.\gradlew.bat installDist`。

验证：

- 构建通过。

## 3. 进度

- [ ] T1 更新文档
- [ ] T2 重写 Web CSS
- [ ] T3 实现 Markdown 渲染 JS
- [ ] T4 调整事件展示
- [ ] T5 测试与构建
