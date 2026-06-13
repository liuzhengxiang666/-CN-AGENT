# ch11: Skills 系统验收清单

## 1. 文档与范围

- [ ] `docs/java/ch11/spec.md` 存在，并包含“不实现 Skill 市场、远程分发、版本管理”。
- [ ] `docs/java/ch11/tasks.md` 存在，并包含 5 到 15 个任务。
- [ ] `docs/java/ch11/tasks.md` 最后包含“接入主流程”和“端到端验证”任务。
- [ ] `docs/java/ch11/checklist.md` 存在。
- [ ] 全文搜索参考项目名时，`docs/java/ch11`、`src/main/java`、`src/test/java` 没有误用非 CN Code 项目名。

## 2. Skill 数据模型

- [ ] 存在 `src/main/java/cncode/skill/` 包。
- [ ] Skill 元信息包含 `name` 字段。
- [ ] Skill 元信息包含 `description` 字段。
- [ ] Skill 元信息包含 `whenToUse` 或等价适用场景字段。
- [ ] Skill 元信息包含 `tags` 字段。
- [ ] Skill 元信息包含 `allowedTools` 字段。
- [ ] Skill 元信息包含 `mode` 字段。
- [ ] Skill 元信息包含 `model` 字段。
- [ ] Skill 元信息包含 `forkContext` 字段。
- [ ] 未声明 `mode` 时，默认值为共享执行模式。
- [ ] 未声明 `model` 时，行为为继承当前会话模型。
- [ ] 未声明 `forkContext` 时，默认值为清空父上下文。
- [ ] Skill 名称缺省时，可以从目录名生成。
- [ ] Skill 名称生成时，空格会被替换为 `-`。

## 3. Skill 文件解析

- [ ] `SKILL.md` 支持 `---` 包裹的 YAML frontmatter。
- [ ] `SKILL.md` frontmatter 后的 Markdown 正文被解析为 SOP。
- [ ] `SKILL.md` 没有 frontmatter 时，仍能作为 Skill 加载。
- [ ] `SKILL.md` 缺少 `description` 时，可以从正文第一条非标题文本回退生成说明。
- [ ] 坏 YAML frontmatter 不会导致整个 Skill 扫描崩溃。
- [ ] 坏 YAML frontmatter 对应的单个 Skill 会被跳过或降级，并产生可诊断问题记录。
- [ ] 目录型 Skill 支持入口 Markdown。
- [ ] 目录型 Skill 支持元信息文件加 prompt 正文文件。
- [ ] 修改 Skill 正文后，下次执行同名 Skill 能读取到新正文。
- [ ] 正文热读取失败时，不会用半成品覆盖旧缓存。

## 4. 三层加载与覆盖

- [ ] 内置 Skill 层会被扫描或注册。
- [ ] 用户目录 `~/.cncode/skills/` 会被扫描。
- [ ] 项目目录 `.cncode/skills/` 会被扫描。
- [ ] 三层存在同名 Skill 时，项目层覆盖用户层。
- [ ] 三层存在同名 Skill 时，用户层覆盖内置层。
- [ ] 目录不存在时，扫描返回空结果或跳过，不抛出致命异常。
- [ ] 单个 Skill 解析失败时，其他有效 Skill 仍出现在列表里。
- [ ] 强制重扫后，新建的项目 Skill 能出现在列表里。
- [ ] 强制重扫后，被删除的项目 Skill 不再出现在列表里。
- [ ] Skill 列表能显示每个 Skill 的来源层级或路径。

## 5. 启动摘要与按需加载

- [ ] 启动阶段注入给模型的 Skill 摘要包含 Skill 名称。
- [ ] 启动阶段注入给模型的 Skill 摘要包含一句说明。
- [ ] 启动阶段注入给模型的 Skill 摘要不包含完整 SOP 正文。
- [ ] 启动阶段不会读取目录型 Skill 的工具实现脚本正文。
- [ ] 执行 Skill 时会按名称读取完整 SOP。
- [ ] 执行未知 Skill 时返回可见错误，而不是把未知 Skill 名字发给模型脑补。

## 6. 激活状态与环境上下文

- [ ] 存在当前会话已激活 Skill 的运行态结构。
- [ ] 激活一个 Skill 后，后续 prompt 中出现 `Active Skills` 或等价段落。
- [ ] 激活一个 Skill 后，后续 prompt 中包含该 Skill 的完整 SOP。
- [ ] 同时激活两个 Skill 后，后续 prompt 中同时包含两个 Skill 名称。
- [ ] 同时激活两个 Skill 后，后续 prompt 中同时包含两个 Skill SOP。
- [ ] Active Skills 内容不作为普通用户消息长期追加到 `ChatSession`。
- [ ] `/clear` 执行后，已激活 Skill 列表为空。
- [ ] `/clear` 执行后，后续 prompt 不再包含清空前的 Skill SOP。
- [ ] 上下文压缩恢复块包含已激活 Skill 的名称。
- [ ] 上下文压缩恢复块包含已激活 Skill 的 SOP 片段。

## 7. Skill 加载工具

- [ ] 存在系统级 Skill 加载工具。
- [ ] Skill 加载工具可以按名称加载完整 Skill。
- [ ] Skill 加载工具成功后返回包含 Skill 名称的可见结果。
- [ ] Skill 加载工具遇到未知 Skill 时返回错误结果。
- [ ] Skill 加载工具遇到解析失败 Skill 时返回错误结果。
- [ ] Skill 加载工具不受当前 Skill 的 `allowedTools` 白名单屏蔽。
- [ ] 当前 Skill 白名单只包含 `ReadFile` 时，Skill 加载工具仍可调用。

## 8. 工具白名单

- [ ] Skill 未声明 `allowedTools` 时，不额外过滤工具。
- [ ] Skill 声明空 `allowedTools` 时，不额外过滤工具。
- [ ] Skill 声明 `allowedTools: [ReadFile]` 时，当前 Skill 可见工具只保留 `ReadFile` 和系统豁免工具。
- [ ] Skill 声明不存在工具名时，启动扫描或重扫阶段 fail-fast。
- [ ] fail-fast 错误信息包含不存在的工具名。
- [ ] 工具白名单不会绕过现有权限检查。
- [ ] 工具白名单不会扩大当前会话原本不可用的工具。

## 9. 共享执行模式

- [ ] `mode: inline` 或等价共享模式可以被解析。
- [ ] 共享模式执行后，Skill SOP 进入 Active Skills。
- [ ] 共享模式执行后，工具白名单应用到主会话后续工具选择。
- [ ] 共享模式执行结果保留在主会话历史。
- [ ] 共享模式 Skill 支持 `$ARGUMENTS` 参数替换。
- [ ] 共享模式 Skill 没有 `$ARGUMENTS` 占位符时，会以可见方式附加用户参数。

## 10. 隔离执行模式

- [ ] `mode: fork` 或等价隔离模式可以被解析。
- [ ] 隔离模式执行时，不直接污染主会话的完整消息历史。
- [ ] 隔离模式完成后，主会话收到摘要回流。
- [ ] `forkContext: none` 时，隔离会话不携带父消息。
- [ ] `forkContext: recent` 时，隔离会话最多携带最近 5 条父消息。
- [ ] `forkContext: full` 或等价全量摘要模式时，隔离会话携带父上下文摘要。
- [ ] 隔离模式声明模型时，执行链路能观测到该模型值。
- [ ] 隔离模式模型不可用时，系统给出可见提示或一致回退。

## 11. 目录型 Skill 与专属工具

- [ ] 目录型 Skill 可以包含工具 schema 文件。
- [ ] 目录型 Skill 可以包含工具实现脚本。
- [ ] 目录型 Skill 的资源路径不能越出该 Skill 目录。
- [ ] 目录型 Skill 未激活时，其专属工具不出现在全局可见工具列表。
- [ ] 目录型 Skill 激活后，其专属工具出现在当前会话可见工具列表。
- [ ] 目录型 Skill 专属工具仍受现有权限检查。
- [ ] 目录型 Skill 专属工具的输出仍受大结果存磁盘和上下文预算处理。

## 12. Slash 命令与管理命令

- [ ] `/skills` 返回 Skill 列表。
- [ ] `/skills list` 返回 Skill 列表。
- [ ] `/skills info <name>` 返回指定 Skill 的详情。
- [ ] `/skills reload` 触发强制重扫。
- [ ] Skill 详情包含名称。
- [ ] Skill 详情包含来源路径或来源层级。
- [ ] Skill 详情包含执行模式。
- [ ] Skill 详情包含模型声明。
- [ ] Skill 详情包含工具白名单。
- [ ] 已发现 Skill 自动注册为 `/<skill-name>` 命令。
- [ ] `/help` 输出包含动态 Skill 命令或能引导用户查看 `/skills`。
- [ ] Web 输入框 `/` 提示包含动态 Skill 命令。
- [ ] 执行 `/<skill-name>` 时不作为普通自然语言消息直接发给模型。
- [ ] 执行 `/<skill-name>` 时会重新读取源文件以支持热更新。

## 13. 内置样板 Skill

- [ ] 内置 `/commit` Skill 可见。
- [ ] 内置 `/review` Skill 可见。
- [ ] 内置 `/test` Skill 可见。
- [ ] `/skills info commit` 显示 commit Skill 的说明。
- [ ] `/skills info review` 显示 review Skill 的说明。
- [ ] `/skills info test` 显示 test Skill 的说明。
- [ ] 内置样板至少覆盖一种共享执行模式。
- [ ] 内置样板至少覆盖一种隔离执行模式。
- [ ] 项目目录创建同名 `commit` Skill 后，项目 Skill 覆盖内置 commit Skill。

## 14. 安全与容错

- [ ] Skill 文件加载不能读取当前 Skill 目录之外的相对路径资源。
- [ ] Skill 内容中的指令不能关闭 CN Code 内置安全规则。
- [ ] Skill 专属工具脚本不能绕过现有工具权限系统。
- [ ] 单个坏 Skill 不会阻止 `/skills` 显示其他 Skill。
- [ ] 重扫遇到坏 Skill 时，错误能被用户看到。
- [ ] 启动时没有任何 Skill 时，系统仍可正常聊天。
- [ ] 启动时没有任何 Skill 时，`/skills` 显示空列表提示。

## 15. 自动化验证

- [ ] `src/test/java/cncode/skill/SkillParserTest.java` 存在。
- [ ] `src/test/java/cncode/skill/SkillCatalogTest.java` 存在。
- [ ] `src/test/java/cncode/skill/SkillToolPolicyTest.java` 存在。
- [ ] `src/test/java/cncode/skill/ActiveSkillStateTest.java` 存在。
- [ ] `src/test/java/cncode/skill/LoadSkillToolTest.java` 存在。
- [ ] `src/test/java/cncode/skill/SkillExecutorTest.java` 存在。
- [ ] `src/test/java/cncode/skill/BuiltinSkillsTest.java` 存在。
- [ ] `src/test/java/cncode/skill/SkillEndToEndTest.java` 存在。
- [ ] `build.gradle` 的 `smokeTest` 包含 Skill 相关测试类。
- [ ] `.\gradlew.bat smokeTest` 通过。

## 16. 端到端验收

- [ ] 在 `.cncode/skills/demo/SKILL.md` 写入 `name: demo` 和 `description: demo skill` 后，`/skills` 列出 `demo`。
- [ ] `/skills info demo` 显示 `demo skill`。
- [ ] 输入 `/demo hello` 后，系统显示 Skill 已加载或已执行的可见提示。
- [ ] 输入 `/demo hello` 后，后续 Agent prompt 包含 demo 的完整 SOP。
- [ ] 修改 `.cncode/skills/demo/SKILL.md` 正文后，再次输入 `/demo hello` 使用新正文。
- [ ] 同时执行 `/demo` 和另一个 Skill 后，后续 Agent prompt 包含两个 Active Skill。
- [ ] 输入 `/clear` 后，再发普通消息，后续 Agent prompt 不包含 demo SOP。
- [ ] 创建 `allowedTools: [DefinitelyMissingTool]` 的 Skill 后，启动或 `/skills reload` 报错且错误包含 `DefinitelyMissingTool`。
- [ ] 创建隔离模式 Skill 并执行后，主会话中出现隔离执行摘要。
- [ ] `.\gradlew.bat smokeTest` 通过。
