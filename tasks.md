# CN Code vNext 任务

## T1. 规格与历史归档

影响文件：根规格、docs/evolution.md

依赖：无

参考：AGENTS.md；旧 spec/tasks/checklist

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T2. 统一接口与运行存储

影响文件：vnext/src/types.ts、records.ts

依赖：T1

参考：Java AgentEvent、SessionStore；DSH SDK 类型

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T3. 配置与模型协议

影响文件：vnext/src/config.ts、provider.ts

依赖：T2

参考：Java ConfigLoader、OpenAiProvider；提供方 usage

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T4. 工具、权限与上下文

影响文件：vnext/src/tools.ts、context.ts

依赖：T3

参考：Java ToolRegistry、InstructionLoader、SkillCatalog

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T5. 自研内核主循环

影响文件：vnext/src/engines/cncode.ts

依赖：T4

参考：Java AgentLoop、ContextCompactor

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T6. DSH 适配

影响文件：vnext/src/engines/dsh.ts

依赖：T5

参考：固定 SDK README 与安装类型

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T7. 固定编码评测

影响文件：vnext/src/eval、fixtures

依赖：T6

参考：Aider phone-number 与 Terminal-Bench kv-store-grpc 固定提交

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T8. OpenViking 记忆

影响文件：vnext/src/memory.ts

依赖：T7

参考：OpenViking TS SDK 与 DSH 插件

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T9. 服务协议

影响文件：vnext/src/service.ts、server.ts

依赖：T8

参考：统一适配器和持久运行事件

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T10. 桌面、Web 与终端

影响文件：vnext/src/ui、desktop、terminal.ts

依赖：T9

参考：Electron 安全窗口文档

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T11. 接入主流程

影响文件：入口、根脚本、部署文件、README

依赖：T10

参考：所有子模块实际调用方

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。

## T12. 端到端验证

影响文件：测试、docs/validation.md、截图与 PR

依赖：T11

参考：checklist；真实模型与容器验收

验收：对应 checklist 项及 docs/validation.md 有可追踪结果。


## 接续定位（2026-11-02）

- T2–T6：`Records.finish/summarize`、`Service.submit/interact/artifacts`、`modelRequest`、`executeTool/command`、`Dsh.run/observe` 和 `runtime/dsh-observer.mjs:apply`；协议检查见 `src/tests/harness.test.ts` 与 `scripts/dsh-protocol.ts`。
- T7：`src/eval/fixtures.ts:prepare/hashTree`、`src/eval/cli.ts`、`src/eval/worker.ts`、`eval/verify-phone.mjs`、`eval/verify-kv.py`、`scripts/verifier-check.ts`。可信验收正反例已过；真实模型调用四次均401，成功基线未通过。
- T8：`Memory.client/scope/recall/capture/commit`；SDK 契约测试已过，现成 DSH 插件的共享用户记忆与降级路径需真实隔离验证，尚未启用；依赖 T7 有效基线。
- T9–T10：`server`、`src/ui/main.tsx`、`src/ui/primitives.tsx`、`src/ui/style.css`、`terminal.ts`、`desktop/main.cjs`；Figma 对应关系在 `docs/ui-design.md`。客户端协议验证见 `scripts/ui-check.ts`、`scripts/desktop-check.ts`。
- T11 接入主流程：根脚本转发既有 workspace；`README.md` 提供配置、D: 环境、客户端和评测命令；未另搭服务或新内核。
- T12 端到端验证：单测、Java 回归、客户端协议与可信验收控制实验有记录，真实模型和记忆端到端仍未通过；不得把阶段草稿 PR 当作全部计划已完成。

T7最新进展：新凭据后的四个组合均通过真实Linux可信验收，见evidence/baseline-results.json；定向重跑复用cli.ts的task/engine过滤。T8仍需embedding和服务配置，不要求购买云托管。
