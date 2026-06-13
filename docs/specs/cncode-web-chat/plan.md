# CN Code Web 聊天界面技术设计

## 1. 架构概览

新增 Web 模式，不删除现有 REPL/TUI。

启动流程：

1. `Main` 读取配置。
2. 创建 `ChatProvider` 和共享 `ChatSession`。
3. 如果参数包含 `--web`，启动 `WebChatServer`。
4. `WebChatServer` 绑定 `127.0.0.1` 可用端口。
5. 服务启动后自动打开浏览器。
6. 前端页面通过 HTTP API 与后端通信。
7. 后端通过 SSE 向前端推送模型流式回复。

## 2. 模块划分

### app

修改：

- `Main`

职责：

- 识别 `--web` 参数。
- 启动 Web 模式。
- 保留 `--repl`、`--tui` 和默认模式。

### web

新增 Web 模块。

核心类：

- `WebChatServer`
- `WebChatHandler`
- `WebAssets`
- `WebJson`
- `PortFinder`
- `BrowserLauncher`

职责：

- 启动本地 HTTP 服务。
- 托管前端 HTML/CSS/JS。
- 提供状态接口。
- 提供发送消息并返回 SSE 的接口。
- 自动打开浏览器。
- 不向前端暴露 API Key。

## 3. HTTP 接口设计

### GET /

返回 Web UI HTML 页面。

### GET /app.css

返回 CSS。

### GET /app.js

返回浏览器端 JavaScript。

### GET /api/status

返回 JSON：

```json
{
  "protocol": "openai",
  "model": "deepseek-v4-flash",
  "messageCount": 0,
  "tools": "not enabled"
}
```

### POST /api/chat

请求 JSON：

```json
{
  "message": "你好"
}
```

响应类型：

```text
text/event-stream
```

SSE 事件：

```text
event: start
data: {}

event: delta
data: {"text":"你好"}

event: done
data: {}

event: error
data: {"message":"调用模型失败"}
```

## 4. 会话策略

`WebChatServer` 持有一个共享 `ChatSession`。

每次 `/api/chat`：

1. 读取用户消息。
2. 将用户消息写入 `ChatSession`。
3. 调用 `ChatProvider.streamChat(...)`。
4. 将 delta 以 SSE 推送给前端。
5. 完成后将完整助手消息写入 `ChatSession`。
6. Provider 失败时发送 `error` 事件，服务继续可用。

## 5. 前端设计

使用纯 HTML/CSS/JS。

页面结构：

- 左侧侧边栏：标题、模型信息、服务状态、消息数量、工具占位。
- 右侧主区域：聊天历史。
- 底部输入区：textarea 和发送按钮。

交互：

- Enter 发送。
- Shift+Enter 换行。
- 发送后创建用户消息和空助手消息。
- 使用 `fetch` 读取 SSE 流。
- 收到 delta 后追加到当前助手消息。
- 自动滚动到底部。
- 错误显示为系统消息。

## 6. 自动打开浏览器

使用 Java `Desktop.getDesktop().browse(uri)`。

如果当前环境不支持 Desktop：

- 捕获异常。
- 终端打印访问 URL。
- 服务继续运行。

## 7. 端口选择

`PortFinder` 从 `8765` 开始尝试。

绑定失败时递增端口，直到成功或达到上限，例如 `8865`。

## 8. 安全边界

- HTTP Server 绑定 `127.0.0.1`。
- 前端不包含 API Key。
- `/api/status` 只返回 protocol/model/messageCount/tools。
- 不提供任意文件访问接口。

## 9. 风险与缓解

- Java 标准 HTTP Server 处理 SSE 时需要手动 flush：每个事件写入后 flush。
- `fetch` 读取 SSE 需要手动解析文本流：前端实现简单 parser。
- 浏览器自动打开可能失败：终端打印 URL。
- 单会话并发请求可能打乱历史：第一版可在 Provider 调用期间拒绝新请求，返回错误事件。
