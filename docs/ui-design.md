# CN Code 黑白主题设计

设计文件：[CN Code UI · Black & White](https://www.figma.com/design/UUg6JVRobzrbugKYd0gqMx)。通过 Figma MCP 创建为可编辑图层，不是截图铺底。深色主视图节点 `5:186`，浅色 `5:258`；审批 `6:26`，追问 `6:111`，新建会话 `6:194`。节点和变量账本见 `design-state.json`。

已检查现有 vnext/package.json、index.html、desktop/main.cjs 与 UI 路径；原 UI 目录不存在，也没有 primitives 组件库或 Code Connect 文件。设计复用 Simple Design System 的 Button 和 Textarea Field；React 使用本地 `src/ui/primitives.tsx` 表达对应组件，未增加 UI 库。

黑白两主题独立于业务逻辑，CSS 自定义变量对应 Figma Theme 的 Dark/Light modes。设计采用 236px 会话栏、76px 顶栏、主会话与 344px 记录栏；响应式布局在窄窗口调整排列。Figma 字体为 Noto Sans SC / IBM Plex Mono；客户端使用本机 Segoe UI / Microsoft YaHei / Consolas 回退，避免依赖外网字体下载，字体度量不保证逐像素相同。

设计中的文案属于示意。实际界面只展示服务记录，不内置虚构成功数据。截图来自真实运行：

- `output/playwright/web-dark-approval.png`：Web 深色审批，顶部明确标注本地协议夹具。
- `output/playwright/web-light-question.png`：Web 浅色追问，属于协议交互验证。
- `output/playwright/electron-dark.png` 与 `electron-light.png`：实际 Electron 恢复真实提供方 401 的历史记录，不代表编码任务通过。

Web/终端与 Electron 的协议自动检查结果分别在同目录的 `ui-result.json`、`desktop-result.json` 和 `electron-protocol-result.json`。后续 UI 修改先更新 Figma，再跑客户端检查，刷新截图并更新 PR 图片链接。
# 记忆状态增量（2026-11-02）

沿用同一 Figma 文件与已有黑白 token、按钮和布局，新视图：`14:80`（Memory completed）、`14:134`（Memory processing）。先通过 MCP 更新并检查设计，再修改既有 React 记忆区域。提交后切到独立运行，实时状态与收益验收分开；恢复会话按 startedAt 选择最新运行，避免UUID目录顺序影响。

实际运行截图：`output/playwright/memory-web-processing.png`、`memory-web-dark.png`、`memory-web-light.png`、`memory-electron-dark.png`、`memory-electron-light.png`。这些使用真实本地 OpenViking 和 DeepSeek 提取记录，运行状态仍保留“尚未验收”，不把完成提取等同收益通过。界面实际检查另有 `memory-ui-result.json`；PR嵌入当前提交图片。
# 2026-11-02 日常默认内核

复用现有新会话设计 `6:194`，通过 Figma MCP 将字段 `6:254` 的选中值改为 DSH；不新增布局或组件。Web/Electron 共用默认选择，终端同步，记忆保持默认关闭。实际运行截图为 `output/playwright/default-dsh-dark.png`、`default-dsh-light.png`，使用本地协议夹具，不代表新增真实模型成绩。
