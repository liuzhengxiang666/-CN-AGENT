# CN Code 纯对话 TUI MVP 验收清单

## 1. 配置检查

- [ ] `~/.cncode/config.yaml` 存在。
- [ ] 配置包含 `protocol`。
- [ ] 配置包含 `model`。
- [ ] 配置包含 `base_url`。
- [ ] 配置包含 `api_key`。
- [ ] `protocol: openai` 时能选择 OpenAI Provider。
- [ ] `protocol: anthropic` 时能选择 Anthropic Provider。
- [ ] 未知 `protocol` 会显示清晰中文错误。
- [ ] 缺失配置文件会显示清晰中文错误。
- [ ] 缺失必填字段会显示清晰中文错误。
- [ ] 错误信息不会完整泄露 API Key。

## 2. 启动检查

- [ ] 执行 `cncode` 后程序启动。
- [ ] 程序显示 CN Code 欢迎语。
- [ ] 程序显示输入提示符。
- [ ] 程序等待用户输入。
- [ ] 输入 `exit` 后程序正常退出。
- [ ] 输入 `quit` 后程序正常退出。

## 3. OpenAI 流式对话检查

- [ ] 使用 `protocol: openai` 和有效配置启动。
- [ ] 输入一条真实问题。
- [ ] CN Code 向 OpenAI 协议后端发送请求。
- [ ] 回复不是等待完整生成后一次性输出。
- [ ] 终端能看到回复逐字或逐块出现。
- [ ] 回复完成后重新显示输入提示符。
- [ ] 助手完整回复被写入当前会话历史。

## 4. Anthropic 流式对话检查

- [ ] 使用 `protocol: anthropic` 和有效配置启动。
- [ ] 输入一条真实问题。
- [ ] CN Code 向 Anthropic Claude 协议后端发送请求。
- [ ] 回复不是等待完整生成后一次性输出。
- [ ] 终端能看到回复逐字或逐块出现。
- [ ] 回复完成后重新显示输入提示符。
- [ ] 助手完整回复被写入当前会话历史。

## 5. 多轮上下文检查

- [ ] 启动 CN Code。
- [ ] 输入：“我叫小明，喜欢 Java。”
- [ ] 等待模型回复完成。
- [ ] 输入：“我刚才说我喜欢什么语言？”
- [ ] 模型回复能参考前文，并指出用户喜欢 Java。
- [ ] 程序退出后再次启动，不要求保留上次会话历史。

## 6. 错误恢复检查

- [ ] API Key 错误时，程序打印清晰错误信息。
- [ ] 网络失败时，程序打印清晰错误信息。
- [ ] SSE 响应解析失败时，程序打印清晰错误信息。
- [ ] Provider 调用失败后，程序返回输入提示符。
- [ ] Provider 调用失败后，用户可以继续输入下一轮请求。
- [ ] Provider 调用失败不会导致程序崩溃退出。

## 7. Provider 抽象检查

- [ ] REPL 主流程只依赖 `ChatProvider` 抽象。
- [ ] OpenAI 请求格式只存在于 OpenAI Provider 内。
- [ ] Anthropic 请求格式只存在于 Anthropic Provider 内。
- [ ] 新增 Provider 不需要重写 REPL 主循环。
- [ ] Anthropic Provider 保留 extended thinking 扩展位置，但本阶段不发送 thinking 参数。

## 8. tmux 端到端验收

- [ ] 在 tmux 中启动 CN Code。
- [ ] 输入一段真实对话请求。
- [ ] 观察 CN Code 正确调用配置的 Provider。
- [ ] 观察 CN Code 流式生成回复。
- [ ] 输入追问，确认多轮上下文有效。
- [ ] 输入 `exit` 或 `quit`，确认程序正常退出。
- [ ] 对照本清单逐项记录通过或失败。
