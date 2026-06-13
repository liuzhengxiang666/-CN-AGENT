# CN Code 单工具 Agent 能力验收清单

## 1. 工具注册

- [ ] 六个核心工具已注册。
- [ ] 六个核心工具按稳定顺序注册：`read_file`、`write_file`、`replace_file`、`run_command`、`find_files`、`search_code`。
- [ ] 注册中心能按 `read_file` 查到读文件工具。
- [ ] 注册中心能按 `write_file` 查到写文件工具。
- [ ] 注册中心能按 `replace_file` 查到改文件工具。
- [ ] 注册中心能按 `run_command` 查到执行命令工具。
- [ ] 注册中心能按 `find_files` 查到找文件工具。
- [ ] 注册中心能按 `search_code` 查到搜索代码工具。
- [ ] 注册中心能输出 LLM Provider 可用的工具定义列表。
- [ ] OpenAI-compatible 工具定义包含 `type: function` 或等价 function/tool 结构。
- [ ] 每个工具定义都有非空 `description`。
- [ ] 每个工具定义都有参数 JSON Schema。
- [ ] `read_file`、`find_files`、`search_code` 分类为 `READ`。
- [ ] `write_file`、`replace_file` 分类为 `WRITE`。
- [ ] `run_command` 分类为 `COMMAND`。
- [ ] 工具分类只作为元信息存在，本阶段没有引入并行调度。

## 1.1 工具描述质量

- [ ] `read_file` 的描述明确说明：用户要求查看、读取、总结、引用本地文件内容时应调用该工具。
- [ ] `write_file` 的描述明确说明：用户要求创建或覆盖项目内文本文件时应调用该工具。
- [ ] `replace_file` 的描述明确说明：用户要求修改已有文件局部内容时应调用该工具，并要求原文唯一匹配。
- [ ] `run_command` 的描述明确说明：用户要求运行构建、测试、查看目录或执行 shell 命令时应调用该工具。
- [ ] `find_files` 的描述明确说明：用户要求查找文件、列出某类路径或确认文件是否存在时应调用该工具。
- [ ] `search_code` 的描述明确说明：用户要求搜索代码、查找关键字、定位实现位置时应调用该工具。

## 2. 文件工具

- [ ] `read_file` 能读取工作目录内文本文件。
- [ ] `read_file` 拒绝工作目录外路径。
- [ ] `write_file` 能在工作目录内写入文件。
- [ ] `write_file` 必要时能创建父目录。
- [ ] `write_file` 拒绝工作目录外路径。
- [ ] `replace_file` 在原文匹配 1 次时替换成功。
- [ ] `replace_file` 在原文匹配 0 次时返回结构化错误。
- [ ] `replace_file` 在原文匹配多次时返回结构化错误。

## 3. 命令与搜索工具

- [ ] `run_command` 能在工作目录内执行简单命令。
- [ ] `run_command` 返回 stdout。
- [ ] `run_command` 返回 stderr。
- [ ] `run_command` 返回 exit_code。
- [ ] `run_command` 超时时返回结构化错误。
- [ ] `find_files` 能按模式返回相对路径列表。
- [ ] `search_code` 能返回匹配文件、行号和片段。
- [ ] 工具输出过长时会截断并说明。

## 4. Agent 调用链路

- [ ] 用户请求读取 `AGENTS.md` 时，模型能发起 `read_file` 工具调用。
- [ ] 系统能解析流式工具名和 JSON 参数碎片。
- [ ] 系统能执行对应工具。
- [ ] 系统能把工具结果写回对话历史。
- [ ] 模型能基于工具结果生成最终回复。
- [ ] 同一次用户请求最多执行一个工具。
- [ ] 工具执行失败时，模型能收到结构化失败结果并生成解释。

## 5. 单工具限制

- [ ] 工具结果回灌后的第二次模型调用不允许继续使用工具。
- [ ] 如果模型第二次仍请求工具，系统拒绝并提示本阶段只支持单工具调用。
- [ ] 一次请求中模型尝试多个工具时，系统只执行一个或返回限制错误。

## 6. Web UI 展示

- [ ] Web UI 显示工具开始事件。
- [ ] Web UI 显示工具名称。
- [ ] Web UI 显示工具执行成功或失败。
- [ ] Web UI 显示工具执行摘要。
- [ ] Web UI 显示最终助手回复。
- [ ] 普通纯聊天不显示工具过程。
- [ ] `/api/status` 显示工具已启用，或显示已注册工具数量，不再显示 `tools: not enabled`。

## 7. 安全与回归

- [ ] 路径越界读文件被拒绝。
- [ ] 路径越界写文件被拒绝。
- [ ] API Key 不暴露到工具结果或前端页面。
- [ ] `--web` 仍可启动。
- [ ] `--repl` 仍可启动。
- [ ] `--tui` 仍可启动或 fallback。
- [ ] 不需要工具的聊天仍正常流式回复。

## 8. 构建验证

- [ ] `.\gradlew.bat check` 通过。
- [ ] `.\gradlew.bat installDist` 通过。

## 9. 明确不做项验证

- [ ] 文档没有要求实现 ToolSearch。
- [ ] 文档没有要求实现 AskUser。
- [ ] 文档没有要求实现 Deferred 工具发现。
- [ ] 文档没有要求实现多工具 Agent Loop。
- [ ] 文档没有要求实现工具并发执行。
- [ ] 文档没有要求实现权限系统、Hook、SubAgent、Team 或 Worktree 工具。
