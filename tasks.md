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
