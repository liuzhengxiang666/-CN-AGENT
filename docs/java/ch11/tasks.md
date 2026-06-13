# ch11: Skills 系统任务拆分

## T1. 梳理现有 Prompt、命令和工具注册入口

影响文件：
- `src/main/java/cncode/prompt/PromptAssembler.java`
- `src/main/java/cncode/prompt/PromptOptions.java`
- `src/main/java/cncode/prompt/PromptSections.java`
- `src/main/java/cncode/command/CommandRegistry.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/tool/ToolRegistry.java`

依赖任务：无

参考资料定位：
- `java-reference.md` ch11 的 “Skills 系统 Spec”
- `docs/java/ch11/spec.md`
- `src/main/java/cncode/prompt/PromptSections.java` 的 Skills section
- `src/main/java/cncode/command/CommandRegistry.java` 的 `/skills` 与 `PROMPT` 命令分支

步骤：
1. 确认现有 Prompt 组装是否已有 skill summary 注入口。
2. 确认 Slash 命令注册中心是否支持动态注册 PROMPT 命令。
3. 确认 Web Chat 当前如何分发 PROMPT 命令和系统消息。
4. 确认 `ToolRegistry` 可用于白名单存在性校验。

验证：
- 能列出 Skill 系统需要新增的包、类、测试和主流程接入点。

## T2. 定义 Skill 数据模型与解析结果

影响文件：
- `src/main/java/cncode/skill/SkillMeta.java`
- `src/main/java/cncode/skill/SkillDefinition.java`
- `src/main/java/cncode/skill/SkillSource.java`
- `src/main/java/cncode/skill/SkillLoadIssue.java`
- `src/test/java/cncode/skill/SkillModelTest.java`

依赖任务：T1

参考资料定位：
- `docs/java/ch11/spec.md` F1、F2、F9、F10、F11
- `java-reference.md` ch11 的 `SkillMeta` / `Skill` 字段说明

步骤：
1. 定义 Skill 元信息字段：名称、描述、适用场景、标签、工具白名单、执行模式、模型、上下文携带策略。
2. 定义完整 Skill 定义，包含元信息、SOP 正文、来源、是否已加载正文、目录型资源引用。
3. 定义加载来源和加载问题结构，用于记录项目/用户/内置来源与解析失败信息。
4. 约束默认值：执行模式默认共享，模型默认继承，隔离上下文默认清空。

验证：
- 单元测试覆盖默认值、名称规范化、空白字段处理和不可变副本行为。

## T3. 实现 Skill 文件解析

影响文件：
- `src/main/java/cncode/skill/SkillParser.java`
- `src/main/java/cncode/skill/SkillCatalog.java`
- `src/test/java/cncode/skill/SkillParserTest.java`

依赖任务：T2

参考资料定位：
- `docs/java/ch11/spec.md` F1、F3、N2、N5
- `java-reference.md` ch11 的 `parseSkillMD`、`skill.yaml + prompt.md`

步骤：
1. 解析 `SKILL.md` 的 YAML frontmatter 与 Markdown 正文。
2. 支持缺省 frontmatter 时从目录名和正文首个有效行回退生成元信息。
3. 支持目录型 Skill 的元信息文件与 prompt 正文文件。
4. YAML 解析失败时降级或记录单文件问题，不阻断整体目录加载。
5. 读取完整正文时支持热更新；读取失败保留旧缓存。

验证：
- 单元测试覆盖 frontmatter、无 frontmatter、坏 YAML、缺正文、目录型布局和热更新失败保留旧缓存。

## T4. 实现三层 Skill 编目与覆盖

影响文件：
- `src/main/java/cncode/skill/SkillCatalog.java`
- `src/test/java/cncode/skill/SkillCatalogTest.java`

依赖任务：T2、T3

参考资料定位：
- `docs/java/ch11/spec.md` F2、F3、F4、N1、N2
- `java-reference.md` ch11 的 `loadCatalog / loadFromDirectory / reload / getFull`

步骤：
1. 按内置、用户、项目三层顺序加载 Skill。
2. 同名 Skill 后加载者覆盖先加载者，并保留最终来源。
3. 启动阶段只加载轻量元信息，不读完整 SOP。
4. 提供列表、详情、按需完整加载和强制重扫能力。
5. 记录解析失败的单个 Skill，不影响其他 Skill。

验证：
- 单元测试覆盖三层覆盖、目录不存在、单个坏 Skill 跳过、列表顺序、重扫发现新增 Skill。

## T5. 实现工具白名单校验与过滤模型

影响文件：
- `src/main/java/cncode/skill/SkillToolPolicy.java`
- `src/main/java/cncode/tool/ToolRegistry.java`
- `src/test/java/cncode/skill/SkillToolPolicyTest.java`

依赖任务：T2、T4

参考资料定位：
- `docs/java/ch11/spec.md` F5、F10、F11、N4
- `java-reference.md` ch11 的 `assertAllowedToolsExist`

步骤：
1. 校验 Skill 声明的工具白名单均存在于当前工具注册表或 Skill 专属工具集中。
2. 白名单为空时不额外收窄工具。
3. 白名单非空时构造工具过滤策略。
4. 确保 Skill 加载工具属于系统级工具，不受白名单过滤影响。
5. 对目录型 Skill 的专属工具预留合并入口。

验证：
- 单元测试覆盖不存在工具 fail-fast、空白名单不过滤、白名单过滤、Skill 加载工具豁免。

## T6. 实现激活 Skill 运行态与环境上下文构建

影响文件：
- `src/main/java/cncode/skill/ActiveSkillState.java`
- `src/main/java/cncode/prompt/PromptOptions.java`
- `src/main/java/cncode/prompt/PromptAssembler.java`
- `src/main/java/cncode/prompt/PromptSections.java`
- `src/test/java/cncode/skill/ActiveSkillStateTest.java`
- `src/test/java/cncode/prompt/PromptAssemblerTest.java`

依赖任务：T2、T4

参考资料定位：
- `docs/java/ch11/spec.md` F4、F6、F14、N6
- `java-reference.md` ch08 的 RecoveryState Active skills 段
- `java-reference.md` ch11 的 `buildActiveContext`

步骤：
1. 维护当前会话已激活 Skill 列表和完整 SOP。
2. 支持多个 Skill 并存，后续激活不覆盖已有激活项。
3. 构建环境上下文中的 Active Skills 段。
4. 将 Active Skills 接入 Prompt 组装，位置高于普通历史。
5. 提供清空当前激活状态的入口。

验证：
- 单元测试覆盖空状态不输出、多个 Skill 并存、清空后不输出、最终 prompt 包含 Active Skills。

## T7. 实现 Skill 加载内置工具

影响文件：
- `src/main/java/cncode/skill/LoadSkillTool.java`
- `src/main/java/cncode/tool/ToolRegistry.java`
- `src/test/java/cncode/skill/LoadSkillToolTest.java`

依赖任务：T4、T5、T6

参考资料定位：
- `docs/java/ch11/spec.md` F5、F10、N3、N4
- `java-reference.md` ch11 的按需 `getFull`

步骤：
1. 新增系统级 Skill 加载工具。
2. 工具按名称查找 Skill，并按需读取完整 SOP。
3. 加载成功后写入 ActiveSkillState，并返回可见摘要。
4. 工具白名单不应屏蔽该工具。
5. 加载失败时返回清晰错误，不让 Agent 根据摘要脑补 SOP。

验证：
- 单元测试覆盖加载成功、未知 Skill、坏 Skill、白名单下仍可调用加载工具。

## T8. 实现共享与隔离执行器

影响文件：
- `src/main/java/cncode/skill/SkillExecutor.java`
- `src/main/java/cncode/skill/SkillExecutionMode.java`
- `src/main/java/cncode/skill/ForkContextMode.java`
- `src/test/java/cncode/skill/SkillExecutorTest.java`

依赖任务：T2、T5、T6

参考资料定位：
- `docs/java/ch11/spec.md` F7、F8、F9、N6
- `java-reference.md` ch11 的 `executeInline / executeFork / buildForkSeed`

步骤：
1. 共享模式：激活 Skill SOP，按白名单收窄工具，结果留在主会话。
2. 隔离模式：构造独立执行上下文，完成后摘要回流主会话。
3. 支持隔离上下文三档：完全清空、最近若干条、全量摘要。
4. 保留 Skill 声明模型，并在执行决策中可观测。
5. 支持参数注入；无参数时原样执行 SOP。

验证：
- 单元测试覆盖共享模式、隔离模式、三档上下文、模型继承、参数注入和白名单传递。

## T9. 支持目录型 Skill 专属工具

影响文件：
- `src/main/java/cncode/skill/SkillPackageLoader.java`
- `src/main/java/cncode/skill/SkillToolLoader.java`
- `src/main/java/cncode/tool/ToolRegistry.java`
- `src/test/java/cncode/skill/SkillPackageLoaderTest.java`

依赖任务：T3、T5、T7

参考资料定位：
- `docs/java/ch11/spec.md` F11、N3、N4

步骤：
1. 识别目录型 Skill 的工具 schema 与实现脚本。
2. 校验资源路径不越出 Skill 目录。
3. 只在 Skill 激活后把专属工具加入当前可见工具集。
4. 未激活 Skill 的专属工具不污染全局工具列表。
5. 专属工具仍接入现有权限检查和工具结果预算。

验证：
- 单元测试覆盖目录型资源发现、路径越界拒绝、激活前不可见、激活后可见。

## T10. 实现内置 commit / review / test 样板 Skill

影响文件：
- `src/main/resources/skills/commit/SKILL.md`
- `src/main/resources/skills/review/SKILL.md`
- `src/main/resources/skills/test/SKILL.md`
- `src/main/java/cncode/skill/BuiltinSkills.java`
- `src/test/java/cncode/skill/BuiltinSkillsTest.java`

依赖任务：T3、T4

参考资料定位：
- `docs/java/ch11/spec.md` F15
- `java-reference.md` ch11 的 builtin tier 占位说明

步骤：
1. 提供 commit 样板 Skill，用于检查 diff、生成提交说明并执行提交流程。
2. 提供 review 样板 Skill，用于从逻辑、安全、性能、风格角度审查改动。
3. 提供 test 样板 Skill，用于识别项目测试方式并运行相关验证。
4. 样板覆盖共享和隔离两种模式。
5. 用户层或项目层同名 Skill 可覆盖内置样板。

验证：
- 单元测试覆盖内置 Skill 可见、字段完整、同名覆盖内置样板。

## T11. 接入 Slash 命令与 Skill 管理入口

影响文件：
- `src/main/java/cncode/command/CommandRegistry.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/web/WebAssets.java`
- `src/test/java/cncode/command/CommandRegistryTest.java`
- `src/test/java/cncode/web/WebAssetsTest.java`

依赖任务：T4、T7、T8

参考资料定位：
- `docs/java/ch11/spec.md` F12、F13、F14
- `java-reference.md` ch10 的 PROMPT 命令注册
- `java-reference.md` ch11 的 `wireSkillsToAgent`

步骤：
1. 将所有已发现 Skill 自动注册为 `/<skill-name>` PROMPT 命令。
2. `/skills` 支持列表、详情、重扫。
3. 执行 `/<skill-name>` 时按需读取完整 Skill 并进入对应执行模式。
4. Web 输入框 Slash 提示包含动态 Skill 命令。
5. `/clear` 清空对话时同步清空已激活 Skill 和临时工具过滤状态。

验证：
- 测试覆盖 `/skills` 列表、详情、重扫、动态命令注册、`/clear` 清激活状态。

## T12. 接入主流程

影响文件：
- `src/main/java/cncode/app/Main.java`
- `src/main/java/cncode/agent/AgentLoop.java`
- `src/main/java/cncode/web/WebChatServer.java`
- `src/main/java/cncode/compact/RecoveryState.java`
- `src/test/java/cncode/agent/AgentLoopTest.java`
- `src/test/java/cncode/compact/RecoveryAttachmentTest.java`

依赖任务：T4、T6、T7、T8、T10、T11

参考资料定位：
- `docs/java/ch11/spec.md` 主流程
- `java-reference.md` ch08 的 Active skills 恢复块
- `java-reference.md` ch11 的启动期和运行期调用链

步骤：
1. 启动 Web/TUI/REPL 主流程时创建 SkillCatalog 并扫描三层 Skill。
2. 将 Skill 摘要注入 PromptOptions。
3. 将 ActiveSkillState 接入 AgentLoop 每轮 prompt 构建。
4. 将 LoadSkillTool 注册为系统级工具。
5. 将激活 Skill 信息接入上下文压缩恢复块。
6. 确保执行 Skill 时不绕过现有权限和工具结果预算。

验证：
- AgentLoop 测试能观察 Skill 摘要、Active Skills、LoadSkillTool 注册和压缩恢复块。

## T13. 端到端验证

影响文件：
- `src/test/java/cncode/skill/SkillEndToEndTest.java`
- `build.gradle`
- `docs/java/ch11/checklist.md`

依赖任务：T1-T12

参考资料定位：
- `docs/java/ch11/spec.md` 验收标准
- `java-reference.md` ch11 Checklist

步骤：
1. 准备项目级 `.cncode/skills/demo/SKILL.md`。
2. 启动或模拟主流程，确认 `/skills` 列出 demo 和内置样板。
3. 执行 `/demo hello`，确认完整 SOP 热读取并进入 Active Skills。
4. 同时激活两个 Skill，确认后续 prompt 同时包含两者。
5. 执行 `/clear`，确认 Active Skills 清空。
6. 构造白名单不存在工具的 Skill，确认启动或重扫 fail-fast。
7. 构造隔离模式 Skill，确认摘要回流主会话。
8. 运行 `.\gradlew.bat smokeTest`。

验证：
- 所有 checklist 项可被自动测试或手动步骤覆盖，`smokeTest` 通过。

## 进度

- [ ] T1. 梳理现有 Prompt、命令和工具注册入口
- [ ] T2. 定义 Skill 数据模型与解析结果
- [ ] T3. 实现 Skill 文件解析
- [ ] T4. 实现三层 Skill 编目与覆盖
- [ ] T5. 实现工具白名单校验与过滤模型
- [ ] T6. 实现激活 Skill 运行态与环境上下文构建
- [ ] T7. 实现 Skill 加载内置工具
- [ ] T8. 实现共享与隔离执行器
- [ ] T9. 支持目录型 Skill 专属工具
- [ ] T10. 实现内置 commit / review / test 样板 Skill
- [ ] T11. 接入 Slash 命令与 Skill 管理入口
- [ ] T12. 接入主流程
- [ ] T13. 端到端验证
