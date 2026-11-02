// UI protocol fixture, intentionally no real model or memory service.
import { mkdir } from "node:fs/promises";
import { Service } from "../src/service.js";
import { server } from "../src/server.js";
import { resolve } from "node:path";
import type { AppConfig } from "../src/types.js";
const root = process.env.CNCODE_FIXTURE_DIR ?? "../.cncode/vnext/ui-fixture";
const workspace = resolve(root, "workspace");
await mkdir(workspace, { recursive: true });
const config: AppConfig = {
  dataDir: resolve(root, "data"),
  model: "本地协议夹具 · 非真实模型",
  baseUrl: "http://unused",
  apiKey: "fixture",
  protocol: "openai",
  token: "ui-validation",
  host: "127.0.0.1",
  port: 3047,
  configPath: "",
  mcp: [],
};
const service = new Service(config, () => ({
  capabilities: {},
  async run(input, ctx) {
    ctx.emit("context", { validationMode: "protocol-fixture" });
    if (input.includes("停止")) {
      await new Promise((resolvePromise, reject) => {
        ctx.signal.addEventListener(
          "abort",
          () => reject(new Error("测试取消")),
          { once: true },
        );
      });
      return;
    }
    const a = await ctx.interact("approval", {
      tool: "write_file",
      args: { path: "review.txt", content: "协议夹具" },
    });
    if (a.allow !== true) {
      ctx.emit("assistant.message", {
        text: "本次工具请求已被拒绝。未产生修改。",
      });
      return;
    }
    const q = await ctx.interact("question", {
      question: "下一步要验证什么？",
      options: ["运行记录", "会话恢复"],
    });
    ctx.emit("assistant.message", {
      text:
        "已收到回答：" +
        q.text +
        "。这是本地协议夹具，运行结束，交付尚未验收。",
    });
  },
  async close() {},
}));
process.env.CNCODE_WORKSPACE = workspace;
const http = server(service, resolve("web-dist"));
http.listen(Number(process.env.CNCODE_PORT ?? 3047), "127.0.0.1", () =>
  console.log(
    "UI protocol fixture ready: http://127.0.0.1:3047/#token=ui-validation",
  ),
);
async function close() {
  await service.close();
  http.closeAllConnections();
  http.close(() => process.exit());
}
process.on("SIGTERM", () => void close());
process.on("SIGINT", () => void close());
