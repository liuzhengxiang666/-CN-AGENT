# CN Code 会话、记忆与指令加载验收清单

## 指令文件加载

- [ ] 存在 `src/main/java/cncode/instruction/InstructionLoader.java`。
- [ ] 项目根存在 `CNCODE.md` 时，启动后最终 prompt 包含 `CNCODE.md` 的正文。
- [ ] 项目根不存在 `CNCODE.md` 但存在 `AGENTS.md` 时，启动后最终 prompt 包含 `AGENTS.md` 的正文。
- [ ] 指令文件中写入 `@include docs/rules.md` 时，最终 prompt 包含 `docs/rules.md` 的正文。
- [ ] include 文件不存在时，加载结果包含可见警告，且启动不崩溃。
- [ ] include 路径为 `../outside.md` 时，加载器拒绝读取工作目录外文件。
- [ ] A include B、B include A 时，加载器不会无限递归，并返回循环引用提示。
- [ ] `grep -rn "InstructionLoader" src/main/java` 至少命中 Agent/Web/TUI 任一主流程接入点。
- [ ] 最终 prompt 中仍包含 CN Code 内置身份、安全、工具使用和上下文管理规则。

## 会话 JSONL 持久化

- [ ] 存在 `src/main/java/cncode/session/SessionStore.java`。
- [ ] 当前项目下创建 `.cncode/sessions/` 目录。
- [ ] 发送一条用户消息后，`.cncode/sessions/` 下出现一个 `.jsonl` 会话文件。
- [ ] `.jsonl` 每一行都是可解析 JSON。
- [ ] 每条 JSON 记录包含 `role` 字段。
- [ ] 每条 JSON 记录包含 `content` 字段。
- [ ] 每条 JSON 记录包含 `timestamp` 字段。
- [ ] 每条 JSON 记录包含完整性校验字段。
- [ ] 用户消息、助手消息、工具调用记录和工具结果进入 `ChatSession` 后，JSONL 行数随之增加。
- [ ] 保存失败时不会清空已有 `.jsonl` 文件。

## 会话恢复与完整性

- [ ] `/session info` 返回当前会话标识、消息数量和会话文件路径。
- [ ] `/session list` 返回当前项目可恢复的历史会话列表。
- [ ] `/session restore <id>` 成功后，当前 `ChatSession` 消息数量变为目标会话消息数量。
- [ ] `/session restore <id>` 成功后，再发送普通消息，模型请求包含恢复后的历史。
- [ ] 手动修改 JSONL 中某条消息 content 后，恢复该会话失败。
- [ ] 手动删除 JSONL 中间一行后，恢复该会话失败。
- [ ] 损坏会话恢复失败后，当前对话消息数量保持不变。
- [ ] `/session`、`/session info`、`/session list`、`/session restore <id>` 不作为普通用户消息进入模型对话。

## 记忆文件

- [ ] 存在 `src/main/java/cncode/memory/MemoryManager.java`。
- [ ] 当前项目根目录可以创建或读取 `memories.md`。
- [ ] `memories.md` 不存在时，加载记忆返回空内容且不抛异常。
- [ ] `/memory add 我喜欢中文简洁回答` 后，`memories.md` 包含 `我喜欢中文简洁回答`。
- [ ] `/memory list` 返回当前 `memories.md` 中的记忆内容。
- [ ] `/memory clear` 后，`memories.md` 不再包含旧记忆内容。
- [ ] `memories.md` 使用 Markdown 文本，而不是 JSON 或二进制格式。
- [ ] 手动编辑 `memories.md` 后，下次启动能读取编辑后的内容。

## 自动记忆抽取

- [ ] 存在 `src/main/java/cncode/memory/MemoryExtractor.java` 或等价抽取模块。
- [ ] Agent Loop 完成一轮后会触发后台记忆抽取逻辑。
- [ ] 抽取请求的工具列表为空数组。
- [ ] 抽取 prompt 要求只记录长期有用信息。
- [ ] 抽取 prompt 覆盖用户偏好、项目约定、重要决策、长期任务线索。
- [ ] fake provider 返回一条记忆后，`memories.md` 追加该记忆。
- [ ] fake provider 返回空内容时，`memories.md` 不新增空条目。
- [ ] fake provider 抛异常时，主对话仍正常完成。
- [ ] 后台抽取不阻塞 Web Chat 的下一条用户输入。

## 记忆注入

- [ ] `PromptOptions` 或等价结构包含 memory 字段。
- [ ] `PromptAssembler.buildStablePrompt` 或等价流程会注入 memory 内容。
- [ ] `memories.md` 包含 `项目使用 Java 21` 时，最终 prompt 包含 `项目使用 Java 21`。
- [ ] 没有记忆时，最终 prompt 不出现空的 Memory 噪声段。
- [ ] 记忆注入优先级低于内置安全规则。
- [ ] 记忆注入不会覆盖项目指令。

## Web `/memory` 命令

- [ ] Web Chat 输入 `/memory` 后收到 system SSE 事件。
- [ ] Web Chat 输入 `/memory list` 后显示当前记忆列表。
- [ ] Web Chat 输入 `/memory add 测试记忆` 后显示追加成功提示。
- [ ] Web Chat 输入 `/memory clear` 后显示清空成功提示。
- [ ] Web Chat 输入未知子命令时显示用法提示。
- [ ] `/memory` 命令不会增加 `ChatSession` 普通用户消息数量。
- [ ] 页面处理 `/memory` 返回结果时没有 JavaScript 错误。

## Web `/session` 命令

- [ ] Web Chat 输入 `/session` 后收到 system SSE 事件。
- [ ] Web Chat 输入 `/session info` 后显示当前会话信息。
- [ ] Web Chat 输入 `/session list` 后显示历史会话列表。
- [ ] Web Chat 输入 `/session restore <id>` 成功后显示恢复成功提示。
- [ ] Web Chat 输入不存在的 `<id>` 时显示找不到会话提示。
- [ ] Web Chat 输入损坏会话 `<id>` 时显示完整性校验失败提示。
- [ ] `/session` 命令不会增加 `ChatSession` 普通用户消息数量。
- [ ] 页面处理 `/session` 返回结果时没有 JavaScript 错误。

## 路径安全

- [ ] include 只能读取当前工作目录内文件。
- [ ] 会话 JSONL 只能写入 `.cncode/sessions/`。
- [ ] 记忆文件只读写当前项目的 `memories.md`。
- [ ] `/session restore ../x` 不会读取工作目录外文件。
- [ ] `/memory add` 只写入 `memories.md`，不会按用户文本当路径写文件。

## 端到端验收

- [ ] 准备 `CNCODE.md`，内容含 `项目规则：回答必须先给结论`；启动 Web Chat 后，模型能遵守该规则。
- [ ] `CNCODE.md` 中 include `docs/team-rules.md`，启动后模型能参考 include 文件中的规则。
- [ ] 在 Web Chat 发送普通消息，`.cncode/sessions/` 下生成 JSONL 会话文件。
- [ ] 重启 Web Chat 后输入 `/session list` 能看到上一条会话。
- [ ] 使用 `/session restore <id>` 恢复后，继续提问时模型能参考恢复前的上下文。
- [ ] 输入 `/memory add 我偏好简洁中文回答`，重启 Web Chat 后模型回答体现该偏好。
- [ ] Agent 对话结束后，自动抽取出的长期信息出现在 `memories.md`。
- [ ] `.\gradlew.bat smokeTest` 通过。
