// Runs the built UI against the real Service protocol using a labeled fixture engine.
import { chromium } from "@playwright/test";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const output = resolve("../output/playwright"),
  root = resolve("../.cncode/vnext/ui-check-" + Date.now());
await mkdir(output, { recursive: true });
const env = {
  ...process.env,
  TEMP: "D:/Temp",
  TMP: "D:/Temp",
  CNCODE_PORT: "3048",
  CNCODE_FIXTURE_DIR: root,
  CNCODE_SERVER: "http://127.0.0.1:3048",
  CNCODE_TOKEN: "ui-validation",
};
const fixture = spawn(
  process.execPath,
  ["--import", "tsx", "scripts/ui-fixture.ts"],
  { env, windowsHide: true },
);
let browser;
const errors: string[] = [];
let terminal: any;
try {
  const deadline = Date.now() + 15000;
  while (true) {
    try {
      if ((await fetch(env.CNCODE_SERVER + "/health")).ok) break;
    } catch {}
    if (Date.now() > deadline) throw new Error("Fixture startup failed");
    await new Promise((r) => setTimeout(r, 100));
  }
  browser = await chromium.launch({
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 920 },
    deviceScaleFactor: 1,
  });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(env.CNCODE_SERVER + "/#token=ui-validation");
  await page.getByRole("button", { name: "＋ 新建会话" }).click();
  assert.equal(
    await page.getByRole("combobox", { name: "执行内核", exact: true }).inputValue(),
    "dsh",
  );
  assert.equal(
    await page.getByRole("checkbox", { name: "启用 OpenViking 项目记忆" }).isChecked(),
    false,
  );
  await page.screenshot({ path: resolve(output, "default-dsh-dark.png") });
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await page.getByRole("button", { name: "＋ 新建会话" }).click();
  await page.screenshot({ path: resolve(output, "default-dsh-light.png") });
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await page.getByRole("button", { name: "＋ 新建会话" }).click();
  await page.getByRole("button", { name: "创建会话", exact: true }).click();
  const input = page.getByRole("textbox", { name: "任务输入" });
  await input.fill("验证审批和追问（本地协议夹具）");
  await page.getByRole("button", { name: "发送 ↑" }).click();
  await page.getByRole("button", { name: "批准本次" }).waitFor();
  await page.screenshot({ path: resolve(output, "web-dark-approval.png") });
  await page.getByRole("button", { name: "批准本次" }).click();
  await page.getByRole("textbox", { name: "回答内容" }).waitFor();
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await page.screenshot({ path: resolve(output, "web-light-question.png") });
  await page.getByRole("textbox", { name: "回答内容" }).fill("运行记录");
  await page.getByRole("button", { name: "提交回答" }).click();
  await page.getByText("已收到回答：运行记录。", { exact: false }).waitFor();
  assert.equal(await input.isDisabled(), false);
  await page.reload();
  assert.equal(
    await page
      .getByRole("button", { name: "浅色", exact: true })
      .getAttribute("aria-pressed"),
    "true",
  );
  await page
    .getByRole("navigation", { name: "会话列表" })
    .getByRole("button")
    .first()
    .click();
  await page.getByText("已收到回答：运行记录。", { exact: false }).waitFor();
  await input.fill("测试停止");
  await page.getByRole("button", { name: "发送 ↑" }).click();
  await page.getByRole("button", { name: "■ 停止" }).click();
  await page.getByRole("definition").filter({ hasText: "已停止" }).waitFor();
  await page
    .getByRole("combobox", { name: "内核", exact: true })
    .selectOption("cncode");
  await page.getByRole("button", { name: "创建会话", exact: true }).click();
  await page
    .getByRole("navigation", { name: "会话列表" })
    .getByRole("button")
    .filter({ hasText: "CN Code" })
    .waitFor();
  assert.equal(
    await page
      .getByRole("navigation", { name: "会话列表" })
      .getByRole("button")
      .count(),
    2,
  );
  await input.fill("拒绝本次工具");
  await page.getByRole("button", { name: "发送 ↑" }).click();
  await page.getByRole("button", { name: "拒绝", exact: true }).click();
  await page
    .getByText("本次工具请求已被拒绝。未产生修改。", { exact: false })
    .waitFor();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > innerWidth,
  );
  assert.equal(overflow, false);
  assert.deepEqual(errors, []);
  // Terminal runs against the same HTTP Service. Commands stay responsive during a run.
  terminal = spawn(process.execPath, ["--import", "tsx", "src/terminal.ts"], {
    env,
    windowsHide: true,
  });
  let text = "";
  terminal.stdout.on("data", (b: Buffer) => (text += b.toString()));
  terminal.stderr.on("data", (b: Buffer) => (text += b.toString()));
  async function waitFor(pattern: RegExp) {
    const start = Date.now();
    while (!pattern.test(text)) {
      if (Date.now() - start > 10000)
        throw new Error("Terminal did not produce " + pattern + "\n" + text);
      await new Promise((r) => setTimeout(r, 50));
    }
  }
  await waitFor(/CN Code ·/);
  assert.match(text, /会话 [^\r\n]+ · dsh/);
  terminal.stdin.write("终端协议验证\n");
  await waitFor(/批准工具/);
  terminal.stdin.write("y\n");
  await waitFor(/下一步要验证什么/);
  terminal.stdin.write("运行记录\n");
  await waitFor(/执行 completed/);
  terminal.stdin.write("测试停止\n");
  await new Promise((r) => setTimeout(r, 250));
  terminal.stdin.write("/stop\n");
  await waitFor(/执行 cancelled/);
  terminal.stdin.write("/quit\n");
  await new Promise<void>((r, j) => {
    const timer = setTimeout(
      () => j(new Error("Terminal exit timeout: " + text)),
      10000,
    );
    terminal.once("exit", (code: number) => {
      clearTimeout(timer);
      code === 0 ? r() : j(new Error("Terminal exit " + code));
    });
  });
  await writeFile(resolve(output, "terminal.log"), text);
  await writeFile(
    resolve(output, "ui-result.json"),
    JSON.stringify(
      {
        mode: "protocol-fixture-not-real-model",
        web: [
          "create",
          "default-dsh-memory-off",
          "approval",
          "question",
          "stop",
          "reopen",
          "theme-persistence",
          "switch-engine-new-session",
          "reject",
        ],
        themes: ["dark", "light"],
        terminal: [
          "default-dsh",
          "message",
          "approval",
          "question",
          "stop-while-running",
          "quit",
        ],
        consoleErrors: errors,
      },
      null,
      2,
    ),
  );
  console.log(
    "PASS: Web and terminal lifecycle; two themes; no browser exceptions",
  );
} finally {
  await browser?.close();
  terminal?.stdin.destroy();
  terminal?.kill();
  fixture.kill();
}
