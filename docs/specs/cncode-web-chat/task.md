# CN Code Web 聊天界面任务拆解

## 1. 文件清单

预计创建或修改：

- `src/main/java/cncode/app/Main.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebAssets.java`
- `src/main/java/cncode/web/WebJson.java`
- `src/main/java/cncode/web/PortFinder.java`
- `src/main/java/cncode/web/BrowserLauncher.java`
- `src/test/java/cncode/web/WebJsonTest.java`
- `src/test/java/cncode/web/PortFinderTest.java`
- `docs/specs/cncode-web-chat/checklist.md`

## 2. 有序任务

### T1. 写入 Web UI 文档

步骤：

1. 创建 `docs/specs/cncode-web-chat/`。
2. 写入 `spec.md`。
3. 写入 `plan.md`。
4. 写入 `task.md`。
5. 写入 `checklist.md`。

验证：

- 四份文档存在。
- 文档内容与本阶段 Web UI 范围一致。

### T2. 实现 Web 静态资源

步骤：

1. 创建 `WebAssets`。
2. 在 Java 字符串中提供 HTML。
3. 在 Java 字符串中提供 CSS。
4. 在 Java 字符串中提供 JS。
5. 页面包含聊天区、输入区、侧边栏。
6. 前端不包含 API Key。

验证：

- HTML 包含 root 容器。
- JS 包含 Enter 发送和 Shift+Enter 换行逻辑。
- CSS 能渲染基本布局。

### T3. 实现 JSON 与 SSE 工具

步骤：

1. 创建 `WebJson`。
2. 支持 JSON 字符串转义。
3. 支持从简单请求体中读取 `message` 字段。
4. 支持构造 `/api/status` JSON。
5. 支持构造 SSE 事件文本。

验证：

- message 字段能正确解析。
- 特殊字符能正确转义。
- SSE 事件格式正确。

### T4. 实现端口选择和浏览器打开

步骤：

1. 创建 `PortFinder`。
2. 从 `8765` 开始寻找可用端口。
3. 创建 `BrowserLauncher`。
4. 使用 `Desktop.browse(...)` 打开浏览器。
5. 自动打开失败时不影响服务运行。

验证：

- 可找到可用端口。
- 端口被占用时能返回下一个端口。
- 浏览器打开失败时不会抛出到主流程导致退出。

### T5. 实现 WebChatServer

步骤：

1. 使用 Java 标准 `HttpServer`。
2. 绑定 `127.0.0.1` 和可用端口。
3. 提供 `/`、`/app.css`、`/app.js`。
4. 提供 `/api/status`。
5. 提供 `/api/chat`。
6. `/api/chat` 返回 `text/event-stream`。
7. 调用 `ChatProvider.streamChat(...)` 并写入 SSE。
8. 完成后保存助手消息到 `ChatSession`。
9. Provider 失败时发送 error 事件。
10. 忙碌时拒绝并返回 error 事件。

验证：

- 静态资源可访问。
- 状态接口不暴露 API Key。
- fake Provider 可产生 SSE delta/done。
- Provider 异常产生 SSE error。

### T6. Main 接入 --web

步骤：

1. `Main` 识别 `--web`。
2. 创建 `WebChatServer`。
3. 启动后打印 URL。
4. 调用 `BrowserLauncher` 自动打开浏览器。
5. 阻塞主线程保持服务运行。
6. 保留 `--repl`、`--tui` 和默认行为。

验证：

- `cncode --web` 启动 Web 服务。
- `--repl` 仍可用。
- `--tui` 仍可用。
- 不带参数行为不变。

### T7. 构建与端到端验收

步骤：

1. 运行 `.\gradlew.bat check`。
2. 运行 `.\gradlew.bat installDist`。
3. 启动 `cncode --web`。
4. 浏览器自动打开。
5. 输入真实 DeepSeek 对话。
6. 验证流式回复。
7. 验证多轮上下文。
8. 查看页面源码确认没有 API Key。

验证：

- 对照 `checklist.md` 逐项验收。
