# CN Code vNext 验收清单

- [x] `pnpm typecheck`、`pnpm test`、`pnpm build` 成功。
- [x] `gradlew smokeTest` 验证 Java 历史实现。
- [ ] 自研真实模型运行生成 run.json、events.jsonl、交付物。
- [x] 多工具调用保留 tool_call_id，模型请求无 XML 伪造工具消息。
- [x] 每个工具记录实际开始/结束；权限等待单列；并行总和不等于 wall time。
- [x] 实际 usage 按请求去重；缺失为 null；缓存数据保留原始口径。
- [ ] 压缩与记忆提取标注独立请求 purpose。
- [x] DSH SDK/运行时固定 0.2.2-alpha.2，独立 profile，不改 ~/.dsh。
- [x] DSH 审批、追问、停止、恢复有真实或明确标注的协议测试。
- [x] 切内核创建新会话，原会话可再次打开。
- [ ] Aider phone-number 全量测试运行，无 skipped，限时 300 秒。
- [ ] KV gRPC 验证真实监听、生成文件和 Get/Set/更新，限时 900 秒。
- [ ] 两任务 × 两内核共四次真实 Linux 隔离运行；验收代码位于容器外可信副本。
- [ ] 四次运行固定模型与工具条件，关闭记忆；失败与环境错误分开。
- [ ] OpenViking 新会话与跨内核召回、纠正、项目隔离通过真实服务测试。
- [ ] 提交接收与提取完成分开显示，失败不会伪报成功。
- [ ] 检索内容不能绕过审批；记忆召回/提取开销独立记录。
- [x] SSE 重放事件 ID 不重复；重复 inputId 不重复启动模型请求。
- [x] Web、Electron、终端完成消息、审批、问题、停止的交互验收。
- [x] 实际 UI 截图上传仓库并嵌入草稿 PR 描述。
- [x] 分阶段提交已推送；演化文档区分历史事实、已实现、未验证和后续计划。

上述客户端/DSH 勾选项的证据来自明确标注的协议夹具与实际运行界面，不代表真实模型编码验收。编码任务与记忆相关未勾选项保持未通过。真实四次容器调用全部为 401 环境错误，见 docs/validation.md。

- [x] `pnpm --dir vnext exec tsx scripts/verifier-check.ts` 接受两项固定参考解，拒绝原始 skeleton 与无监听服务。
- [x] Figma MCP 中存在可编辑 Dark/Light 主视图及审批、追问、新会话视图，链接见 docs/ui-design.md。
