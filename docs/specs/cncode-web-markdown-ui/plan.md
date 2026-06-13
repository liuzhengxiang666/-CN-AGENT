# CN Code Web Markdown UI 技术设计

## 1. 架构概览

本阶段只修改 `WebAssets.java` 中的 HTML/CSS/JS 字符串。

数据流保持不变：

```text
/api/chat SSE -> 前端 parseEvents -> delta 累积 Markdown -> renderMarkdown -> 写入助手消息 body
```

## 2. 模块边界

### HTML

保留：

- sidebar 状态信息
- messages 容器
- composer 表单

调整：

- 文案更接近 ChatGPT。
- 输入框 placeholder 调整为中文。

### CSS

重写视觉风格：

- 浅色背景。
- 对话内容居中。
- 用户气泡右对齐。
- 助手消息正文布局。
- 工具系统消息低调样式。
- 错误消息独立警告样式。
- Markdown 元素样式。

### JS

新增：

- `escapeHtml`
- `renderMarkdown`
- `renderInlineMarkdown`
- `setMarkdown`

调整：

- user/system/error 仍用纯文本。
- assistant 使用 Markdown 渲染。
- delta 事件累积 `assistantRaw`，每次重新渲染。
- tool_result 根据 success 选择 system 或 error 样式。

## 3. Markdown 渲染策略

不引入第三方库，使用小型解析器。

处理顺序：

1. 先按三反引号拆分代码块。
2. 代码块内容做 HTML escape。
3. 非代码块按行处理标题、引用、列表、段落。
4. 段落内处理粗体、行内代码和链接。
5. 所有用户可控文本先 escape，再进行有限替换。

## 4. 安全策略

任何模型输出都不直接进入 `innerHTML`。

必须先 `escapeHtml`。

Markdown 替换只生成有限标签：

- `h1/h2/h3`
- `p`
- `strong`
- `code`
- `pre`
- `ul/ol/li`
- `blockquote`
- `a`
- `br`

链接 href 只允许 `http://`、`https://`、`#`，其他协议显示为普通文本或空链接。

## 5. 风险与缓解

- 风险：手写 Markdown 不完整。缓解：只覆盖常用输出，后续再引入本地依赖。
- 风险：流式过程中 Markdown 暂不闭合。缓解：每次按当前文本尽力渲染，未闭合代码块按代码块展示到当前结尾。
- 风险：CSS 改动影响移动端。缓解：增加移动端 media query。
