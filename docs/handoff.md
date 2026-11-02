> 本文件原始交接截至 ca3546f；请先看文末“接续后的当前状态”。

# CN Code vNext 接续实施交接（2026-11-02）

## 接续位置

- 仓库：https://github.com/liuzhengxiang666/-CN-AGENT
- 实施 worktree：`D:\Codex-Workspaces\cn-code-vnext`
- 分支：`codex/cn-code-vnext`，基于 `03ffabf7ca1edceea85301d2df0815f76baa3c2d`
- 原始克隆：`D:\Codex-Workspaces\cn-code`；不要在原始克隆或 `D:\blueframe` 中实现。
- 用户将自行在 **CN coding** 项目下打开新聊天。新聊天先读取本文件、根 AGENTS.md、spec/tasks/checklist，以及 docs/evolution.md、docs/validation.md。
- 这是未完成实现的交接，不是可发布版本。保持已有用户修改，继续同一分支，不重新搭一套。

## 已确认的最终范围

保留 Java；TypeScript 主线支持自研内核和 DSH 内核，两者都保留。每个会话固定内核，切换即新建会话。DSH SDK 与运行时固定 `0.2.2-alpha.2`，独立 home/profile，不修改用户已有 DSH。

先运行记录与评测，再记忆，随后完善客户端。Electron 桌面主要自用入口，React Web 复用界面，终端也必须交互；未来可单独部署 Linux 服务。统一协议处理消息、工具、权限审批、问题回答、停止、恢复、SSE 重放与幂等输入。

记录实际耗时、实际提供方 usage、单个工具执行耗时与交付结果；人工等待另计，辅助请求独立 purpose，未知 token 为 null。执行结束不等于交付验收通过，不能把并行工具时间相加当 wall time。

两项基线：
- Aider-AI/polyglot-benchmark `7e0611e77b54e2dea774cdc0aa00cf9f7ed6144f`，`javascript/exercises/practice/phone-number`；原始 xtest 必须全部启用，零跳过，300 秒。
- harbor-framework/terminal-bench-2 `2fd12b88aafdd04a52c298e3940bcb189f9766d6`，`kv-store-grpc`；检查生成的 pb2、真实 5328 端口、Get/Set/更新，900 秒。
- 每项×两内核共四次，串行、固定模型路由，干净 Linux 容器，关闭记忆及学习，可信验收代码和答案不交给 Agent 修改。保留模型/内核版本、代码差异、日志和交付物。任务失败和环境错误分开。

评测之后接可选 OpenViking。DSH 优先复用 `@openviking/dsh-memory-plugin`，自研用 `@openviking/sdk`；一条捕获/召回路径，项目范围隔离，提交与提取完成分开，增加新会话、跨内核、纠正旧值、隔离与故障验证。后续才做 Jev、Dreaming 整理、受控 RSI，本轮不实现这三项。

## 已保存的实现

- 提交 `274fac6`：旧三份规格归档；新 spec、12 项 tasks、checklist；演化路线与验证状态文档。
- TS workspace 和锁文件，依赖已安装在 D:；根命令转发至 vnext。
- `vnext/src/` 已有 types/config/records/provider/tools/context/mcp/service/server/server-main/terminal、engines/cncode 与 engines/dsh、memory。
- 自研循环有原生结构化工具消息、SSE 按工具 index 聚合、usage、压缩 purpose、文件/shell/搜索/提问/Skill/MCP 基础路径。
- 记录为 run.json + events.jsonl + artifacts；交互等待、幂等输入、会话持久化、SSE 重放已有代码。
- DSH 适配已按安装 SDK 类型接启动、恢复、审批与提问回调，使用独立目录；尚未真实启动验证。
- `vnext/desktop/main.cjs` 有安全窗口与子服务生命周期骨架；**React UI 尚未实现，构建未验证，不能说桌面已完成。**
- 已实际执行：`vnext/node_modules/typescript/bin/tsc --noEmit`，通过。**尚无本轮自动化测试文件，未跑真实模型、编码基线、OpenViking、UI、终端或 Java smokeTest。**

## 下一步优先处理

1. 审阅现有 WIP 再补测试，尤其 DSH 真实规范与 OpenViking 发布 SDK 契约；不要将代码能编译当成协议正确。
2. DSH 原始 session 事件中 `assistant/message.data.usage` 使用 TokenUsage 格式；当前 observe 的字段映射只是初版，核对实际 input/output/cache 口径、请求去重及 compaction。
3. DSH 当前只有 tool.request/result，**尚未采集真实 tools/execute 开始/结束**；需要优先复用执行层扩展点。不能拿 UI 收到时间冒充实际执行耗时。
4. DSH memory 尚未装入/启用现成插件；当前 capability 声明必须与实际能力对齐，基线完整关闭所有记忆路径。
5. OpenViking 的项目归属与检索范围要依据实际服务验证；当前 Memory 的 URI、头、错误状态、提交任务结构均需联调。不能把 actorPeerId 或一个自拟 header 当成已验证隔离。
6. 自研工具的参数验证、超时与进程树取消、失败结果、Skill 工具策略、旧配置里的扁平 MCP 项以及凭据脱敏需要补齐。当前 config 只迁移了主字段与 mcp_servers 数组。
7. 会话中途退出、错误工具调用后的历史完整性、run.end 与清理顺序、辅助请求/未知 usage、SSE 重连边界均应测试。测试用临时目录必须在 D:。
8. 完成隔离评测器与固定任务获取、可信验收；再接记忆、真实 React UI 和三客户端验收。
9. `.npmrc` 当前写了 D: store/cache 路径；Linux 容器必须覆盖，不能照抄 Windows 路径。安装时 pnpm 跳过部分原生脚本，DSH 子进程相关依赖需按证据检查。
10. 根 spec 只描述能力，函数参数等细节写 tasks/checklist。每阶段同步 validation/evolution，不把“待验证”改成“完成”而无证据。

## 环境和交付

- Node `D:\nodejs\node.exe` v24.16.0；Java Zulu 21；Git `D:\git\Git\cmd\git.exe`；GitHub CLI 有仓库 push 权限，连接器只有 pull 权限。优先 CLI 推送，禁止回显凭据。
- 原有 `~/.cncode/config.yaml` 存在，模型为 `deepseek-v4-flash`；从文件读取，不在输出或仓库暴露 api_key，不静默换模型。模型网络可用性还没验证。
- Docker Linux daemon 初次检查未运行；磁盘映像位置未确认。先核对存储位置再启动和构建，避免新增 C: 数据。
- 所有新开发目录、缓存、临时数据在 D:。不修改 BlueFrame，也不在 C: 的旧 CN coding 目录重新搭建。
- 已获授权：完成必要实现、测试和 git push，创建草稿 PR；不自动 merge main。UI 改动必须有本次真实运行截图，提交到仓库并在 PR 描述中嵌入可访问的图片链接。
- 保持独立工作区与当前分支，按里程碑提交。最终提供 PR、提交、启动步骤、真实评测报告及未完成项。

## 用户口述和事实边界

用户最初就是为了研究 Harness，用 ChatGPT Codex 辅助写 CN Code，运行时接 DeepSeek 主要写代码。具体最早模型版本和开发日期未确认，公开两次提交日期只说明集中上传。

口述顺序：一开始给几个具体任务跑，主要看耗时、token、工具执行和交付效果；后来建立运行记录与评测，之后验证记忆和路由是否减少重复探索。未通过验收的能力用将来时，不编造成功率、性能收益或企业项目经历。


## 接续后的当前状态（2026-11-02）

仍在同一 D: 工作区与 codex/cn-code-vnext 分支。第一阶段提交 a865c77 修正记录并加入评测器；后续提交补全黑白主题、客户端协议验证、验收器控制实验与文档（精确提交以 git log 为准）。

- 根 AGENTS.md 已从旧 Java/每次重新调研规则更新为批准的 TS 接续路线。CNCODE.md 原来重复旧规则，现保留 team-rules include 并 include AGENTS.md，避免自研指令加载器读到过期副本。
- 单元/协议检查目前13项；Java smokeTest38入口通过；build生成真实 React界面。
- DSH 使用 runtime/dsh-observer.mjs 在执行层与LLM流层记录，不使用通知到达时间冒充工具执行时间。原生审批拒绝、追问、恢复和停止有真实运行时+模拟模型端点证据。错误时SDK产生的零usage保持未知。
- 可信评测器和Linux镜像已有，两个任务×两内核真实尝试全部因401归为环境错误；无成功基线，仍需更新本机凭据。Verifier参考解/负例均通过；不计模型成绩。后续评测记录镜像ID、资源限制与coding-only-v1工具范围。
- OpenViking未配置服务。SDK项目范围/消息peer已修正并协议测试；现成插件复核发现共享用户级profile/记忆与兼容降级，不将其当严格项目隔离。DSH记忆仍拒绝开启，没有新写替代插件；临时安装依赖已移除。
- 黑白主题经Figma MCP建立可编辑设计，参考Codex风格；主视图、审批、追问、新会话均有节点。Figma文件及状态账本见docs/ui-design.md与design-state.json。
- Web/终端交互自动检查已过；Electron实际启动、两主题、恢复历史、沙箱、子服务退出检查已过，桌面消息/审批/追问/停止用真实自研循环和本地模型端点通过。截图在output/playwright，提交并嵌入本阶段草稿PR。
- 最新启动、验证命令见README.md。所有开发缓存/临时目录放D:；不要将CLI产生的.playwright-cli或私有运行原件提交。
- 仍需：有效无记忆模型基线 → OpenViking真实部署与隔离/纠正/跨内核 → 记忆收益及成本对照。Jev/Dreaming/RSI不实施。草稿PR不合并main。

阶段草稿PR：https://github.com/liuzhengxiang666/-CN-AGENT/pull/1；四张实际UI截图已嵌入且链接返回200。接续时先解决401与OpenViking真实服务/隔离，不要把草稿交付当作完整vNext验收。
