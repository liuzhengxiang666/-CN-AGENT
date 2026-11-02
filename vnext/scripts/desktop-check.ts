import { _electron } from "@playwright/test";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { createServer } from "node:http";
import { once } from "node:events";
import { mkdir, writeFile } from "node:fs/promises";
const require = createRequire(import.meta.url);
const output = resolve("../output/playwright");
await mkdir(output, { recursive: true });
const env = {
  ...process.env,
  TEMP: "D:/Temp",
  TMP: "D:/Temp",
  CNCODE_DESKTOP_DATA: resolve("../.cncode/vnext/desktop-validation"),
  CNCODE_DATA_DIR: resolve("../.cncode/vnext/smoke-cncode/data"),
};
delete env.ELECTRON_RUN_AS_NODE;
const app = await _electron.launch({
  executablePath: require("electron"),
  args: [resolve("desktop/main.cjs")],
  env,
  timeout: 45000,
});
try {
  const page = await app.firstWindow();
  await page.getByRole("heading", { name: "实现、检查，再继续。" }).waitFor();
  const url = new URL(page.url());
  await page
    .getByRole("navigation", { name: "会话列表" })
    .getByRole("button")
    .first()
    .click();
  await page
    .getByText(/模型 HTTP 401/)
    .first()
    .waitFor();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await page.screenshot({ path: resolve(output, "electron-dark.png") });
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await page.screenshot({ path: resolve(output, "electron-light.png") });
  const security = await app.evaluate(({ BrowserWindow }) => {
    const w = BrowserWindow.getAllWindows()[0]!;
    return w.webContents.getLastWebPreferences();
  });
  if (
    security.nodeIntegration ||
    !security.contextIsolation ||
    !security.sandbox
  )
    throw new Error("Unsafe renderer preferences");
  await app.close();
  let closed = false;
  try {
    await fetch(url.origin + "/health");
  } catch {
    closed = true;
  }
  if (!closed) throw new Error("Child server survived desktop exit");
  await writeFile(
    resolve(output, "desktop-result.json"),
    JSON.stringify(
      {
        mode: "real-electron-restored-provider-auth-failure",
        render: true,
        themes: ["dark", "light"],
        sandbox: security.sandbox,
        contextIsolation: security.contextIsolation,
        nodeIntegration: security.nodeIntegration,
        childServerStopped: closed,
      },
      null,
      2,
    ),
  );
  console.log(
    "PASS: Electron rendered both themes, restored run, sandboxed renderer, child server stopped",
  );
} finally {
  await app.close().catch(() => {});
}

// A second launch tests interactions with the actual CN Code loop and a local
// deterministic model endpoint. It is explicitly labeled, never model acceptance.
let requests = 0;
const model = createServer(async (req, res) => {
  for await (const _ of req) {
  }
  requests++;
  res.writeHead(200, { "content-type": "text/event-stream" });
  if (requests >= 4) {
    res.write(": cancellation fixture\n\n");
    return;
  }
  let delta: any = { content: "Electron 协议验证结束；交付尚未验收。" },
    finish = "stop";
  if (requests <= 2) {
    const name = requests === 1 ? "write_file" : "ask_user_question",
      args =
        requests === 1
          ? { path: "electron.txt", content: "protocol-fixture" }
          : { question: "继续验证吗？" };
    delta = {
      tool_calls: [
        {
          index: 0,
          id: "desktop-" + requests,
          type: "function",
          function: { name, arguments: JSON.stringify(args) },
        },
      ],
    };
    finish = "tool_calls";
  }
  res.end(
    "data: " +
      JSON.stringify({
        choices: [{ index: 0, delta, finish_reason: finish }],
      }) +
      "\n\ndata: [DONE]\n\n",
  );
});
model.listen(0, "127.0.0.1");
await once(model, "listening");
const fixtureRoot = resolve("../.cncode/vnext/electron-protocol-" + Date.now());
await mkdir(resolve(fixtureRoot, "workspace"), { recursive: true });
const fixtureEnv = {
  ...env,
  CNCODE_DESKTOP_DATA: resolve(fixtureRoot, "desktop"),
  CNCODE_DATA_DIR: resolve(fixtureRoot, "records"),
  CNCODE_WORKSPACE: resolve(fixtureRoot, "workspace"),
  CNCODE_CONFIG: resolve(fixtureRoot, "no-config"),
  CNCODE_API_KEY: "fixture-not-real",
  CNCODE_MODEL: "本地协议夹具 · 非真实模型",
  CNCODE_BASE_URL: `http://127.0.0.1:${(model.address() as any).port}/v1`,
  CNCODE_PROTOCOL: "openai",
};
const second = await _electron.launch({
  executablePath: require("electron"),
  args: [resolve("desktop/main.cjs")],
  env: fixtureEnv,
  timeout: 45000,
});
try {
  const page = await second.firstWindow();
  await page.getByRole("button", { name: "＋ 新建会话" }).click();
  await page.getByRole("button", { name: "创建会话", exact: true }).click();
  const input = page.getByRole("textbox", { name: "任务输入" });
  await input.fill("验证桌面消息、审批与追问");
  await page.getByRole("button", { name: "发送 ↑" }).click();
  await page.getByRole("button", { name: "批准本次" }).click();
  await page.getByRole("textbox", { name: "回答内容" }).fill("继续");
  await page.getByRole("button", { name: "提交回答" }).click();
  await page
    .getByText("Electron 协议验证结束；交付尚未验收。", { exact: false })
    .waitFor();
  await input.fill("测试停止");
  await page.getByRole("button", { name: "发送 ↑" }).click();
  await page.getByRole("button", { name: "■ 停止" }).click();
  await page.getByRole("definition").filter({ hasText: "已停止" }).waitFor();
  await writeFile(
    resolve(output, "electron-protocol-result.json"),
    JSON.stringify(
      {
        mode: "real-electron-real-cncode-loop-local-model-fixture",
        checks: ["message", "approval", "question", "stop"],
        modelAcceptance: false,
      },
      null,
      2,
    ),
  );
  console.log(
    "PASS: Electron message, approval, question and model cancellation",
  );
} finally {
  await second.close();
  model.closeAllConnections();
  model.close();
}
