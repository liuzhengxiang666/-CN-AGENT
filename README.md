# CN Code vNext

个人自用的编码 Agent 与 Harness 研究项目。保留 Java 21 历史实现，TypeScript 主线提供自研内核和固定版 DSH 内核，共用运行记录、HTTP 服务、React Web、Electron 桌面与交互终端。切换内核会创建新会话。

当前为研究用未完成版本。两项任务×两内核的真实Linux无记忆基线均已通过外部可信验收（每组合一个样本）；OpenViking 真实隔离与记忆收益未验收。完整状态见 [验证记录](docs/validation.md)、[验收清单](checklist.md) 和 [演化路线](docs/evolution.md)。单次样本不支持稳定成功率或内核速度优势结论，尚无记忆性能收益数据。

## 启动

Windows 开发使用 Node 24.9+、pnpm 10.20.0，Java 回归需要 JDK 21。工作区、缓存与临时目录位于 D:。

```powershell
Set-Location D:\Codex-Workspaces\cn-code-vnext
$env:TEMP='D:\Temp'
$env:TMP='D:\Temp'
$env:COREPACK_HOME='D:\Codex-Workspaces\.corepack'
$env:npm_config_cache='D:\Codex-Workspaces\.npm-cache'
$env:electron_config_cache='D:\Codex-Workspaces\tool-cache\electron'
pnpm install --frozen-lockfile
pnpm build
pnpm dev
```

服务读取现有 `~/.cncode/config.yaml` 的 `protocol`、`model`、`base_url`、`api_key`，同时迁移旧扁平 MCP 配置。可用 `CNCODE_CONFIG` 指向其他配置，不要将密钥放到仓库。默认配置协议为 OpenAI compatible，保持已有模型路由，不静默切换模型。

Web 使用服务输出的本地链接；访问令牌存于当前窗口会话，加载后从 URL 移除。可通过 `CNCODE_HOST`、`CNCODE_PORT` 和 `CNCODE_TOKEN` 配置监听；远程监听需要显式令牌。`CNCODE_WORKSPACE` 指定新会话的初始工作区。

```powershell
pnpm desktop
# 桌面自动启动子服务，并在退出时关闭子服务。

$env:CNCODE_SERVER='http://127.0.0.1:3040'
$env:CNCODE_TOKEN='<服务启动输出的令牌>'
pnpm terminal
# /stop、/quit、/sessions、/session ID、/engine cncode|dsh
```

Electron 39.0.0 使用项目独立数据目录，renderer 开启 sandbox/contextIsolation 并关闭 nodeIntegration。DSH SDK 与运行时均固定 0.2.2-alpha.2，使用独立 home/profile；不会复用用户的 `~/.dsh`。

## 界面设计

UI 先在 [CN Code UI · Black & White](https://www.figma.com/design/UUg6JVRobzrbugKYd0gqMx) 中通过 Figma MCP 设计，再实现为 React。参考 Codex 的黑白工作台风格，深浅两主题具有相同布局；主题变量、设计节点与实际截图记录在 [设计说明](docs/ui-design.md)。后续 UI 修改继续先更新 Figma，并在 PR 描述嵌入修改后实际运行截图。

## 验证与评测

```powershell
pnpm typecheck
pnpm test
pnpm build
pnpm --dir vnext test:ui
# Web/终端对真实 Service 使用明确标注的协议夹具，不代表真实模型验收。

Set-Location vnext
pnpm exec tsx scripts/dsh-protocol.ts
pnpm exec tsx scripts/desktop-check.ts
pnpm exec tsx scripts/verifier-check.ts
Set-Location ..
$env:GRADLE_USER_HOME='D:\Codex-Workspaces\.gradle'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=D:\Temp'
.\gradlew.bat --no-daemon smokeTest
```

`test:ui` 在 Windows 使用已有 Edge，其他平台使用 Playwright Chromium；依赖的浏览器与 Electron 二进制须可用。`desktop-check` 的历史恢复截图使用本轮 `smoke.ts cncode` 保存的记录；首次运行先在 vnext 中执行该脚本。桌面交互测试另外使用本地模拟模型端点，结果明确标为协议验证。

```powershell
pnpm eval prepare
pnpm eval build
pnpm --dir vnext exec tsx scripts/verifier-check.ts
pnpm eval run
# 可定向重跑同一评测器：pnpm eval run phone-number dsh
```

Docker Linux 评测串行运行两个任务×两个内核。固定提交、限时、只编码工具、模型路由、无记忆/无学习、镜像 ID、运行轨迹、交付物清单和外部验收分别保存于 `.cncode/vnext/eval`。验收代码与参考答案不挂载进 Agent 容器。phone-number 启用原始全部 xtest 并检查零跳过；KV 由独立容器验证真实 5328 监听、生成绑定及 Get/Set/更新。任何非通过结果会让评测命令退出非零。

该研究镜像是 Node 24 + Debian Python 3.11 venv/gRPC 1.73.0，区别于 Terminal-Bench 原版 Python 3.13 镜像；不能冒充官方完整排行榜评测。正反例控制实验仅验证验收器，不计为 Agent 成绩。

## 运行记录与后续

每次运行保存 `run.json`、`events.jsonl` 与支持的文本交付物快照。token 缺失为 null；会话执行状态与交付 verdict 分开，按请求用途分账，人工与自动回答等待分别记录，取并发区间并集；早期基线字段分类更正见验证记录。工具计时来自实际调用层（DSH 为 tools/execute），包含工具内部发生的等待，不是 CPU 时间；人工等待另有记录。并行工具总和不能当作 wall time。

OpenViking SDK 尚处于协议验证阶段。现成 DSH 插件已做契约复核，但严格项目隔离、跨内核召回、纠正与提取完成状态仍待真实服务测试。先取得有效无记忆基线，再开放完整记忆并比较收益与额外成本。Jev、Dreaming 式整理和 RSI 只在路线中保留。
