# 本轮验证记录

状态以真实命令结果为准。实现、模拟协议测试、真实模型测试、真实外部服务测试分别记录。

| 检查 | 状态 | 证据 |
|---|---|---|
| 独立 D: worktree | 通过 | `codex/cn-code-vnext`，起点 `03ffabf7` |
| 本机推送权限 | 通过 | GitHub CLI 返回目标仓库 push=true |
| TypeScript 类型检查 | 通过 | 2026-11-02 在 vnext 执行 typescript/bin/tsc --noEmit，退出 0 |
| TypeScript 构建、单测 | 未完成 | WIP；React UI 与本轮测试尚未实现 |
| Java smokeTest | 待执行 | — |
| 自研内核真实模型 | 待执行 | — |
| DSH 真实交互 | 待执行 | — |
| Docker Linux 基线四次运行 | 待执行 | Docker daemon 初次探测未运行 |
| OpenViking 真实服务与跨会话 | 待执行 | — |
| Web 与桌面截图 | 待执行 | — |
| 终端验收 | 待执行 | — |
| 推送与草稿 PR | 待执行 | — |


## 2026-11-02 接续实施：运行记录与基线尝试

- `pnpm typecheck`：通过；`pnpm test`：11/11，通过且零跳过。覆盖未知/重复 usage、purpose、并发人工等待、原生多工具历史、取消恢复、幂等输入、SSE 重放、进程树停止、记录脱敏与截断事件恢复。
- `gradlew.bat --no-daemon smokeTest`：退出 0，37 个历史测试入口全部执行；Gradle home 与 Java 临时目录均在 D:。
- 固定 DSH 0.2.2-alpha.2 的真实子进程 + 本地模拟模型端点：文件写入、`tools/execute` 实际计时、`llm/stream` usage、关闭后恢复同一会话通过。证据 `docs/evidence/dsh-protocol.json`；这是协议验证，不是真实模型验收。
- 自研和 DSH 的真实 DeepSeek 调用均返回 HTTP 401。证据 `docs/evidence/provider-auth-failures.json`。没有成功模型输出，token 保持 null，不能把 DSH 适配器在错误时产生的零 usage 算作提供方实际消耗。
- Docker Linux daemon 已启动；已核对 Local/Roaming Docker 目录的 D: junction，磁盘为 `D:/Docker-Desktop-State/local/wsl/disk/docker_data.vhdx`。未清理或修改其他项目的容器、卷或 VM。
- `pnpm eval prepare`：两项固定提交已获取并生成 SHA-256 清单。`pnpm eval build`：Linux 镜像构建通过。
- `pnpm eval run`：四次串行干净容器调用，模型均为本地配置的 deepseek-v4-flash、记忆与学习均关闭；四次全部因 401 记为 infra_error。证据 `docs/evidence/baseline-attempts.json`。这四次不是成功基线，也不是四次编码失败；没有可比较的成功率、速度或记忆收益。
- OpenViking：未配置服务端点，本机 1933 端口未监听。仅修正 SDK 调用中的项目 URI、peer 消息归属和管理路径检查；真实隔离与提取未验收，DSH 记忆显式拒绝启用。
- UI：正在按用户补充通过 Figma MCP 设计 Codex 风格黑白两主题；React、客户端截图与 PR 尚待后续验证。

已知限制：评测容器使用 Node 24 + Debian Python venv 提供固定 gRPC 版本，未声称与 Terminal-Bench 原版 Python 3.13 镜像完全等价；评测报告记录此环境区别。外部可信验证脚本尚需正反例验证，不能仅凭四次 401 认定评测器全部验收。
