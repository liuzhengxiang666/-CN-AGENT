# 本地项目记忆

当前方案是 OpenViking 0.5.3 本地服务、原有 DeepSeek 模型提取、BGE 小模型本地检索。不是全离线方案：会话文本会发送给已经配置的 DeepSeek API，按提供方规则消耗 token。本地 embedding 不使用云 embedding API；其本机运行开销另记。

## 使用

在 README 的 D: 环境变量配置下，从项目根目录执行：

```powershell
pnpm memory build
pnpm memory up
pnpm memory status
pnpm dev
# 或 pnpm desktop
```

Docker 必须使用 Linux daemon，且 Docker disk image 位于 D:。本次实测使用 amd64 Linux、Python 3.12.16、OpenViking 0.5.3、llama-cpp-python 0.3.36。`up` 创建或重启专用的 `cncode-openviking` 容器以加载配置；镜像更新时只重建这个容器，D: 数据卷保留。不要在正在提取时重启。

默认目录为 `D:\Codex-Workspaces\cn-code-vnext\.cncode\vnext\openviking`：

| 文件/目录 | 内容 |
|---|---|
| `ov.conf` | 私有服务配置与 DeepSeek 凭据 |
| `connection.json` | 本地连接及管理密钥 |
| `projects.json` | 项目专属本地账户与密钥 |
| `data/` | OpenViking 本地文件、向量索引、任务状态 |
| `cache/models/` | BGE GGUF 模型 |
| `home/`、`tmp/` | 服务 home 与临时文件 |
| `observations.jsonl` | 提供方实际 usage、模型耗时、本地 embedding 耗时，不保存提示词或密钥 |

这些私有文件被 Git 忽略。DSH 插件自己的配置、状态和 pending 队列放在当前会话独立的 D: profile 内，不复用用户的 `.dsh` 或 `.openviking`。

服务仅发布到 `127.0.0.1:1933`。创建启用记忆的会话时，CN Code 用本地管理 API 为项目创建独立账户；这不是注册火山云账号。日常数据 API 使用项目密钥，管理密钥不作为数据密钥传递。

已有项目目录的会话中勾选“启用 OpenViking 项目记忆”。完成的用户/助手回合先捕获为会话消息；点击“提交提取”后产生独立运行，界面先显示处理中，后台任务与索引完成后才显示已完成。空会话提交显示无需提取。提取完成仍不是编码验收或收益证明。

`pnpm memory down` 停止服务，保留记忆与模型缓存。

## 项目边界与两种内核

- 项目身份来自真实目录路径的散列，Windows 归一化大小写与分隔符。目录别名解析到同一真实路径；不同 checkout/worktree 默认使用不同账户，移动目录也会产生新身份。
- OpenViking 可以生成用户级记忆，不能把 peer URI 当作唯一隔离边界。因此每个项目独占一个本地账户，在该账户内召回用户级记忆，跨项目账户不混用。
- 自研内核复用 `@openviking/sdk`。DSH 复用固定 `@openviking/dsh-memory-plugin@0.5.32` 做召回；关闭其自动捕获、退出提交、MCP 和 Skill 扩展，完成回合统一走同一个 SDK 捕获/手动提取路径，防止重复写入和未计量的后台提取。
- 记忆是参考数据，不授予工具权限。实际验收包括开启记忆后拒绝写入审批、后台取消及服务故障，详见验证记录。

## 计量与验收

OpenViking 的任务结果含会话累计 token，不能每次提交都把累计值重新计费。专用运行时观察器挂在固定版本现有模型响应与 embedding 执行点，记录原始提供方 usage，以请求 ID 去重并归入 `memory_extraction` / `memory_recall`。检索注入内容增加的主对话 token 仍归主请求，用开关记忆的对照观察；不伪造一个精确的“注入 token”字段。

BGE 的内部 token 统计是估计值，不作为提供方实际 token；这里保存真实执行耗时，provider token 保持 null。取消时提供方未返回 usage，也保持未知，不推断免费或零消耗。停止会申请服务端取消并等待确认；若超时仍未确认，会明确记录 pending，不能当作后台已经结束。

```powershell
pnpm --dir vnext exec tsx scripts/memory-check.ts
pnpm --dir vnext exec tsx scripts/memory-negative.ts <上一步输出的实验目录>
pnpm --dir vnext exec tsx scripts/memory-ui-check.ts <上一步输出的实验目录>
```

这些会真实调用模型并消耗 token。实验使用独立项目和清理后的输出文件、服务外校验器；没有约定值的任务与显式给定值的成本控制分开。专用 JSON 工具策略禁止 shell、搜索、Skill 和子 Agent；DSH 的文件工具只允许本次输出文件，防止读取工作区外报告。它是定向实验的工具范围，不是通用操作系统沙箱，也不是原始两个编码基线的记忆排行榜成绩。

源契约：[官方配置](https://github.com/volcengine/OpenViking/blob/v0.5.3/docs/en/guides/01-configuration.md)、[账户认证](https://github.com/volcengine/OpenViking/blob/v0.5.3/docs/en/guides/04-authentication.md)、[异步提交](https://github.com/volcengine/OpenViking/blob/v0.5.3/docs/en/api/05-sessions.md)。
