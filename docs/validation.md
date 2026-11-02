# 本轮验证记录

当前状态以本文最后一节及 `evidence/baseline-results.json` 为准。下方历史章节保留早期401与协议验证事实，不用新结果覆盖原始失败。

| 检查 | 当前状态 | 证据 |
|---|---|---|
| 独立 D: worktree | 通过 | codex/cn-code-vnext |
| 类型检查、构建 | 通过 | pnpm typecheck/build |
| 单元与协议测试 | 15/15通过，零跳过 | pnpm test；含自动/人工等待分类 |
| Java smokeTest | 38入口通过 | 本轮历史回归 |
| 自研与DSH真实模型 | 文件写入/读取成功 | 原路由 deepseek-v4-flash；凭据仅保存在本机配置 |
| 两任务×两内核 Linux 无记忆基线 | 四个任务-内核组合通过可信验收，各一个样本 | evidence/baseline-results.json 与 baseline-artifacts/ |
| 可信验收正反例 | 通过 | evidence/verifier-controls.json |
| OpenViking真实服务与记忆收益 | 未验证 | 尚缺可用embedding/服务配置，不强制注册云账号 |
| Web、Electron、终端 | 实际客户端协议验证通过 | output/playwright/*.json 与截图 |
| 草稿PR与图片 | 已交付，后续增量继续更新 | PR #1；未合并main |

## 2026-11-02 接续实施：运行记录与基线尝试

- `pnpm typecheck`：通过；`pnpm test`：11/11，通过且零跳过。覆盖未知/重复 usage、purpose、并发人工等待、原生多工具历史、取消恢复、幂等输入、SSE 重放、进程树停止、记录脱敏与截断事件恢复。
- `gradlew.bat --no-daemon smokeTest`：退出 0，38 个历史测试入口全部执行；Gradle home 与 Java 临时目录均在 D:。
- 固定 DSH 0.2.2-alpha.2 的真实子进程 + 本地模拟模型端点：文件写入、`tools/execute` 实际计时、`llm/stream` usage、关闭后恢复同一会话通过。证据 `docs/evidence/dsh-protocol.json`；这是协议验证，不是真实模型验收。
- 自研和 DSH 的真实 DeepSeek 调用均返回 HTTP 401。证据 `docs/evidence/provider-auth-failures.json`。没有成功模型输出，token 保持 null，不能把 DSH 适配器在错误时产生的零 usage 算作提供方实际消耗。
- Docker Linux daemon 已启动；已核对 Local/Roaming Docker 目录的 D: junction，磁盘为 `D:/Docker-Desktop-State/local/wsl/disk/docker_data.vhdx`。未清理或修改其他项目的容器、卷或 VM。
- `pnpm eval prepare`：两项固定提交已获取并生成 SHA-256 清单。`pnpm eval build`：Linux 镜像构建通过。
- `pnpm eval run`：四次串行干净容器调用，模型均为本地配置的 deepseek-v4-flash、记忆与学习均关闭；四次全部因 401 记为 infra_error。证据 `docs/evidence/baseline-attempts.json`。这四次不是成功基线，也不是四次编码失败；没有可比较的成功率、速度或记忆收益。
- OpenViking：未配置服务端点，本机 1933 端口未监听。仅修正 SDK 调用中的项目 URI、peer 消息归属和管理路径检查；真实隔离与提取未验收，DSH 记忆显式拒绝启用。
- UI：正在按用户补充通过 Figma MCP 设计 Codex 风格黑白两主题；React、客户端截图与 PR 尚待后续验证。

已知限制：评测容器使用 Node 24 + Debian Python venv 提供固定 gRPC 版本，未声称与 Terminal-Bench 原版 Python 3.13 镜像完全等价；评测报告记录此环境区别。外部可信验证脚本尚需正反例验证，不能仅凭四次 401 认定评测器全部验收。


### OpenViking 插件契约复核（不代表接入成功）

检查了发布包 `@openviking/dsh-memory-plugin@0.5.32` 的 `config.mjs`、`capture.mjs`、`shared/recall-core.mjs`、`shared/profile-inject.mjs` 和 `shared/pending-queue.mjs`。其 actor recall 仍包含用户级 profile/记忆，而且旧服务器不接受 peer_scope 时存在降级兼容路径，不能直接声称满足本项目严格的项目隔离。默认 pending queue 位于用户 home，后续接入必须显式迁往 D:，并核对 memo/配置缓存目录。没有服务端点或成功无记忆基线，因此本轮没有开启该插件，也没有另写替代插件；临时安装用于契约检查的依赖已移除，锁文件恢复原版本。SDK 路径的修正另有协议测试，真实隔离、提取完成状态和收益比较保持未通过。


## 2026-11-02 客户端与可信验收控制实验

| 检查 | 结果与证据 | 边界 |
|---|---|---|
| `pnpm typecheck/test/build` | 通过；后续最终检查为 13 个单元/协议测试，零跳过 | 不代表真实编码任务通过 |
| 固定 DSH 原生回调 | 真实运行时完成 write、ask_user_question、越界写入审批拒绝、关闭后恢复、模型流中停止；见 `evidence/dsh-protocol.json` | 模型端点为本地夹具，输入/输出 token 为夹具数据，不是 DeepSeek 实际消耗 |
| `scripts/verifier-check.ts` | 原始 phone skeleton 失败、固定提交 proof 通过；原始18项测试全部启用，零跳过；KV reference 真实 RPC 通过，杀掉监听进程后失败 | 参考解仅进入独立控制实验，从未挂载到 Agent 基线容器；不计 Agent 成绩 |
| `pnpm --dir vnext test:ui` | Web 创建、批准/拒绝、回答、停止、重新打开、主题持久化、切内核新会话；终端消息/审批/追问/运行中停止/退出通过 | Service 与客户端真实运行，内核为明确标注的协议夹具，无浏览器脚本异常 |
| `scripts/desktop-check.ts` | Electron 39 实际渲染黑白主题并恢复真实 401 记录；sandbox/contextIsolation=true，nodeIntegration=false；退出后子服务已关闭 | 截图中的 401 保持尚未验收；另外以真实自研循环+本地模型夹具验证桌面消息、审批、追问、停止 |
| Figma MCP | 可编辑黑白主题、审批、追问、新会话视图；主题变量与资产账本见 `design-state.json` | 设计稿示意不作为运行成绩 |
| 截图 | `output/playwright/` 四张 PNG；两张 Web 协议界面、两张真实 Electron 历史记录界面 | PR 图片需在推送后核验链接 |

修复了 UI 测试中发现的终端退出悬挂与布局溢出。Electron 安装脚本曾退出但未完成解压，随后使用已下载 zip 和包内 checksums.json 验证 SHA-256，再解压到 D: 的 node_modules，实际启动通过；没有修改依赖版本。

评测器正反例检查还修复了 Jest config 的转义错误。Windows checkout 的参考 shell 脚本只在控制实验副本中归一化 CRLF，可信 fixture 原件及 SHA-256 清单不修改。基线新增 coding-only-v1 工具范围（不提供 subagent/workflow/goal/web），并记录镜像 ID 与环境配置；四次早期 401 报告先于该范围收紧，没有编码结果，不据此做内核对比。

仍阻塞：真实 DeepSeek 凭据返回 401；未配置可用的 OpenViking 服务。成功编码基线、跨会话/跨内核记忆、纠正旧值、真实项目隔离、提取完成状态与记忆额外 token 均未通过。工具执行层计时包括其内部阻塞，尤其追问和工具内部申请扩权；人工等待另计，不把两者相加当作 wall time。


## 阶段交付

- 已推送阶段提交 `a865c77`、`711f2a8` 至原分支；草稿 PR：[ #1](https://github.com/liuzhengxiang666/-CN-AGENT/pull/1)，保持 OPEN / DRAFT，未合并 main。
- PR 描述嵌入四张此次实际运行 PNG，均为提交 `711f2a8` 的稳定 raw 链接；四条链接 HTTP HEAD 均返回 200 / image/png。图片对应当前 UI 代码，后续仅更新验证文档。
- 最终 `pnpm typecheck/test/build` 与客户端协议检查通过；评测镜像再次构建通过。成功编码基线与真实记忆验收仍保持未通过。


## 2026-11-02 新凭据后的有效无记忆基线

用户提供的新凭据已保存到现有本机模型配置，测试后按用户要求保留，不回显、不写入仓库。模型路由仍为 deepseek-v4-flash。早期401卡点已解除。

自研真实smoke：4.237秒，输入3710、输出333 token；文件内容实际核对为 `CN Code smoke`。DSH真实smoke也完成写入与读取；两者smoke的执行结束本身不替代任务验收。

| 任务 | 内核 | run wall time | 实际输入token | 实际输出token | 外部验收 |
|---|---|---:|---:|---:|---|
| phone-number | CN Code | 15.952 s | 18023 | 2445 | 18/18通过，零跳过 |
| phone-number | DSH | 17.841 s | 28468 | 2117 | 18/18通过，零跳过 |
| kv-store-grpc | CN Code | 173.915 s | 209786 | 10403 | 生成文件、真实5328监听、Get/Set/更新通过 |
| kv-store-grpc | DSH | 52.330 s | 104666 | 5913 | 同一可信验证通过 |

所有运行关闭记忆和学习，使用同一模型路由、固定任务提交与同一Linux镜像。时间为run开始至清理结束，容器准备/验收等额外wall time另见报告。工具时间来自实际执行层；cache缺失仍为null。每组合仅一个纳入样本，不能推导稳定成功率或内核性能优势，尚无记忆收益实验。

第一批成功运行的部分区间与额外DSH smoke重叠，已保留于 `evidence/baseline-first-valid-attempts.json` 并标注。受影响的phone-number/DSH与KV/CN Code随后在无额外模型调用时串行重跑；最终纳入结果、精确run ID、环境镜像和原始metrics见 `evidence/baseline-results.json`。Agent生成的交付物、SHA-256与可信验收输出见 `evidence/baseline-artifacts/`。

计量纠正：这批无人评测中的旧humanWaitMs字段实际来自脚本自动回答的审批等待，并非人工。原始报告不改写，结果索引增加normalizedWaitMetrics，明确人工等待为0、自动交互等待另计。后续Service.answer记录actor，summarize分别计算humanWaitMs、automatedWaitMs和全部交互等待区间并集；新增单元与Service链路测试通过。用于此次基线的镜像先于这项分类修复，报告明确保留版本边界。

OpenViking费用与账户说明：自部署不要求开通火山云OpenViking账号；模型API调用仍按提供方计费，也可选择本地embedding。云托管需要火山账号，费用/额度以控制台为准。本机当前可用内存约2GiB，尚未直接启用额外本地模型；仍需确定embedding与服务配置，再进行记忆真实隔离和收益验证。

官方依据：[快速入门](https://github.com/volcengine/OpenViking/blob/main/docs/en/getting-started/02-quickstart.md)、[模型配置](https://github.com/volcengine/OpenViking/blob/main/docs/en/guides/01-configuration.md)。仅核对自部署路径，没有开通云付费服务。
