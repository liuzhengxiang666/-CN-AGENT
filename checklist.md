# CN Code vNext 验收清单

- [x] `pnpm typecheck`、`pnpm test`、`pnpm build` 成功。
- [x] `gradlew smokeTest` 验证 Java 历史实现。
- [x] 自研真实模型运行生成 run.json、events.jsonl、交付物。
- [x] 多工具调用保留 tool_call_id，模型请求无 XML 伪造工具消息。
- [x] 每个工具记录实际开始/结束；权限等待单列；并行总和不等于 wall time。
- [x] 实际 usage 按请求去重；缺失为 null；缓存数据保留原始口径。
- [x] 真实记忆提取运行的 usage 标为 `memory_extraction`，不重复计入任务结果的累计 token。
- [ ] 超长真实会话触发压缩并核对独立 purpose 与额外开销。
- [x] DSH SDK/运行时固定 0.2.2-alpha.2，独立 profile，不改 ~/.dsh。
- [x] DSH 审批、追问、停止、恢复有真实或明确标注的协议测试。
- [x] 切内核创建新会话，原会话可再次打开。
- [x] 新会话默认 DSH、记忆未勾选；无参数终端创建 DSH 会话，自研仍可显式选择（本地协议夹具验证）。
- [x] Aider phone-number 全量测试运行，无 skipped，限时 300 秒。
- [x] KV gRPC 验证真实监听、生成文件和 Get/Set/更新，限时 900 秒。
- [x] 两任务 × 两内核共四次真实 Linux 隔离运行；验收代码位于容器外可信副本。
- [x] 四次运行固定模型与工具条件，关闭记忆；失败与环境错误分开。
- [x] OpenViking 新会话与跨内核召回、纠正、项目账户隔离通过真实服务测试；见 memory-results.json。
- [x] 提交接收与提取完成分开显示；失败不发 completed，空提交跳过，取消获得服务端确认。
- [x] 开启记忆后拒绝写入审批，目标文件不存在；召回/提取开销和未知 token 分开记录。
- [x] SSE 重放事件 ID 不重复；重复 inputId 不重复启动模型请求。
- [x] Web、Electron、终端完成消息、审批、问题、停止的交互验收。
- [x] 实际 UI 截图上传仓库并嵌入草稿 PR 描述。
- [x] 分阶段提交已推送；演化文档区分历史事实、已实现、未验证和后续计划。

客户端/DSH基础协议勾选项使用明确标注的夹具；编码基线和本地记忆另有真实DeepSeek模型与可信验收。早期401、污染的DSH记忆样本和修复中的失败均保留；纳入样本、边界与计量说明见docs/validation.md。定向配置复用不等于长期复杂编码收益。

- [x] `pnpm --dir vnext exec tsx scripts/verifier-check.ts` 接受两项固定参考解，拒绝原始 skeleton 与无监听服务。
- [x] Figma MCP 中存在可编辑 Dark/Light 主视图及审批、追问、新会话视图，链接见 docs/ui-design.md。
- [x] 显式值控制任务在两内核开关记忆均通过；记录新增耗时与 token，不声称普遍提速。
- [x] 专用记忆实验拒绝读取工作区外报告，隐藏 shell/搜索/Skill/子 Agent；污染试验不进入最终对照。
