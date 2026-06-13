# CN Code 会话、记忆与指令加载任务拆分

## T1. 梳理现有提示词、会话和 Web 命令入口

影响文件：
- `spec.md`
- `src/main/java/cncode/prompt/PromptAssembler.java`
- `src/main/java/cncode/prompt/PromptSections.java`
- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/web/WebChatServer.java`

依赖任务：无

参考资料定位：
- `java-reference.md` 的 ch09 记忆系统
- `java-reference.md` 的 ch10 Slash 命令系统
- `src/main/java/cncode/prompt/PromptAssembler.java`
- `src/main/java/cncode/web/WebChatServer.java`

步骤：
1. 对照 `spec.md` 标记现有项目已有能力和缺口。
2. 确认项目当前使用 `ChatSession` 而不是参考文档里的 `ConversationManager`。
3. 确认 Web Chat 已有 `/plan`、`/do`、`/compact` 命令分支，可扩展 `/session` 和 `/memory`。

验证：
- 能列出本章需要新增的包、类和测试文件。

## T2. 实现指令文件加载器

影响文件：
- `src/main/java/cncode/instruction/InstructionLoader.java`
- `src/test/java/cncode/instruction/InstructionLoaderTest.java`

依赖任务：T1

参考资料定位：
- `src/main/java/cncode/agent/AgentLoop.java` 中读取 `AGENTS.md` 的现有逻辑
- `src/main/java/cncode/prompt/PromptOptions.java`

步骤：
1. 新增指令加载模块。
2. 按优先级发现项目指令入口文件。
3. 支持 `@include` 引用项目内 Markdown 文件。
4. 限制 include 路径不能越过工作目录。
5. 检测循环 include 并给出可观察结果。

验证：
- 单测覆盖优先级、include 展开、缺失 include、循环 include、越界 include。

## T3. 接入项目指令到 PromptAssembler

影响文件：
- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/prompt/PromptSections.java`
- `src/test/java/cncode/prompt/PromptAssemblerTest.java`

依赖任务：T2

参考资料定位：
- `PromptAssembler.buildStablePrompt`
- `PromptSections.projectInstructions`
- `AgentLoop.readProjectInstructions`

步骤：
1. 用指令加载器替换或扩展现有 `AGENTS.md` 读取逻辑。
2. 保证展开后的项目指令进入 `PromptOptions.projectInstructions`。
3. 保持内置身份、安全、工具和上下文管理规则优先存在。

验证：
- 单测能观察到 include 后的内容进入最终 prompt。
- 内置安全规则仍存在于最终 prompt。

## T4. 实现 JSONL 会话存储

影响文件：
- `src/main/java/cncode/session/SessionRecord.java`
- `src/main/java/cncode/session/SessionStore.java`
- `src/test/java/cncode/session/SessionStoreTest.java`

依赖任务：T1

参考资料定位：
- `src/main/java/cncode/chat/ChatMessage.java`
- `src/main/java/cncode/chat/ChatSession.java`
- `src/main/java/cncode/provider/JsonUtil.java`

步骤：
1. 定义会话记录数据结构。
2. 将消息记录追加写入项目会话目录下的 JSONL 文件。
3. 每条记录写入角色、内容、时间戳和完整性校验信息。
4. 支持列出当前项目的历史会话。
5. 支持读取指定会话并恢复为 `ChatMessage` 列表。

验证：
- 单测覆盖追加、列出、恢复、空目录、损坏 JSONL。

## T5. 实现会话完整性校验

影响文件：
- `src/main/java/cncode/session/SessionStore.java`
- `src/test/java/cncode/session/SessionStoreTest.java`

依赖任务：T4

参考资料定位：
- `src/main/java/cncode/toolresult/ToolResultBudget.java` 中稳定 hash 思路

步骤：
1. 为每条消息记录生成链式校验字段。
2. 恢复会话时校验记录顺序和内容完整性。
3. 校验失败时返回失败结果，不替换当前 `ChatSession`。

验证：
- 单测手动篡改 JSONL 内容后恢复失败。
- 单测删除中间记录后恢复失败。

## T6. 实现 Markdown 记忆管理器

影响文件：
- `src/main/java/cncode/memory/MemoryManager.java`
- `src/test/java/cncode/memory/MemoryManagerTest.java`

依赖任务：T1

参考资料定位：
- `java-reference.md` ch09 记忆系统
- `src/main/java/cncode/prompt/PromptSections.java` 的 memory section

步骤：
1. 读取当前项目的 `memories.md`。
2. 支持追加记忆条目。
3. 支持清空记忆。
4. 输出可注入 prompt 的记忆文本。
5. 保持 Markdown 文件人类可读。

验证：
- 单测覆盖不存在文件、读取已有记忆、追加、清空、Markdown 格式。

## T7. 实现自动记忆抽取

影响文件：
- `src/main/java/cncode/memory/MemoryExtractor.java`
- `src/main/java/cncode/memory/MemoryManager.java`
- `src/test/java/cncode/memory/MemoryExtractorTest.java`

依赖任务：T6

参考资料定位：
- `java-reference.md` ch09 的抽取 prompt
- `src/main/java/cncode/compact/ContextCompactor.java` 中内部 LLM 调用禁用工具的做法

步骤：
1. 在 Agent 对话结束后构造记忆抽取请求。
2. 要求模型只抽取长期有用信息。
3. 禁止抽取过程调用工具。
4. 解析模型输出并追加到 `memories.md`。
5. 抽取失败时不影响主对话。

验证：
- 单测使用 fake provider 覆盖成功抽取、空抽取、异常失败。
- 摘要抽取请求工具列表为空。

## T8. 实现 `/memory` Web 命令

影响文件：
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebJson.java`
- `src/test/java/cncode/web/WebChatServerTest.java`
- `src/test/java/cncode/web/WebAssetsTest.java`

依赖任务：T6

参考资料定位：
- `WebChatServer.handleModeCommand`
- `WebChatServer.writeSse`
- `WebAssets.js` system/compact 事件处理

步骤：
1. 拦截 `/memory` 命令。
2. 支持查看当前记忆。
3. 支持追加一条记忆。
4. 支持清空记忆。
5. 命令结果通过 system SSE 返回。

验证：
- `/memory` 不进入普通对话历史。
- Web 测试能观察查看、追加、清空三种结果。

## T9. 实现 `/session` Web 命令

影响文件：
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/session/SessionStore.java`
- `src/test/java/cncode/web/WebChatServerTest.java`
- `src/test/java/cncode/session/SessionStoreTest.java`

依赖任务：T4、T5

参考资料定位：
- `WebChatServer.handleModeCommand`
- `ChatSession.replaceMessages`

步骤：
1. 拦截 `/session` 命令。
2. 支持查看当前会话信息。
3. 支持列出历史会话。
4. 支持恢复指定会话。
5. 恢复失败时保留当前会话。

验证：
- `/session` 不进入普通对话历史。
- 损坏会话恢复失败且当前会话不变。

## T10. 接入会话持久化主流程

影响文件：
- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/session/SessionStore.java`
- `src/test/java/cncode/agent/AgentLoopTest.java`

依赖任务：T4、T5、T9

参考资料定位：
- `AgentLoop.run`
- `AgentLoop.runLoop`
- `WebChatServer.handleChat`

步骤：
1. Web Chat 启动时创建当前会话标识和会话存储。
2. 用户消息、助手消息、工具调用记录和工具结果进入 `ChatSession` 后同步追加 JSONL。
3. 保存失败时通过可见事件或日志提示，但不阻塞对话。
4. 恢复会话后替换当前 `ChatSession`。

验证：
- Agent Loop 测试能观察 JSONL 文件生成。
- 恢复后后续模型请求使用恢复后的历史。

## T11. 接入记忆加载与后台抽取主流程

影响文件：
- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/prompt/PromptAssembler.java`
- `src/test/java/cncode/memory/MemoryManagerTest.java`
- `src/test/java/cncode/agent/AgentLoopTest.java`

依赖任务：T6、T7、T8

参考资料定位：
- `PromptOptions.memorySummary`
- `PromptSections.memory`
- `AgentLoop.collectModel`

步骤：
1. Web Chat 初始化时加载 `memories.md`。
2. 记忆内容进入 `PromptOptions.memorySummary`。
3. Agent Loop 完成一轮后后台触发记忆抽取。
4. 抽取结果追加到 `memories.md`。

验证：
- 有记忆文件时最终 prompt 包含记忆内容。
- 对话结束后 fake provider 抽取结果写入 `memories.md`。

## T12. 端到端验证

影响文件：
- `checklist.md`
- `build.gradle`
- 相关测试文件

依赖任务：T1-T11

参考资料定位：
- 根目录 `checklist.md`
- `build.gradle` 的 `smokeTest`

步骤：
1. 运行相关单元测试和 smoke test。
2. 在 Web Chat 手动验证 `/memory`。
3. 在 Web Chat 手动验证 `/session`。
4. 验证指令 include 能进入模型上下文。
5. 对照 `checklist.md` 勾选通过项。

验证：
- 自动化测试通过。
- 至少一条 Web 端到端会话恢复验收通过。
- 至少一条 Web 端到端记忆加载验收通过。
