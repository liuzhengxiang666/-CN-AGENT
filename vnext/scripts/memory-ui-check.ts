// Actual Web/Electron clients restoring real OpenViking extraction records.
import assert from "node:assert/strict";
import { chromium, _electron } from "@playwright/test";
import { createRequire } from "node:module";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { once } from "node:events";
import { loadConfig } from "../src/config.js";
import { Service } from "../src/service.js";
import { server } from "../src/server.js";
import type { EngineContext } from "../src/types.js";
const require = createRequire(import.meta.url),
  root = resolve(process.argv[2]!);
const report = JSON.parse(await readFile(join(root, "report.json"), "utf8"));
const original = loadConfig(),
  config = {
    ...original,
    mcp: [],
    dataDir: join(root, "records"),
    token: "memory-ui-local-check",
  };
const service = new Service(config),
  s = service.get(report.runs[0].sessionId);
const output = resolve("../output/playwright");
await mkdir(output, { recursive: true });
const seed = service.records.begin(s, config.model, {
  purpose: "memory_capture",
});
const c: EngineContext = {
  session: s,
  signal: AbortSignal.timeout(30000),
  save() {},
  async interact() {
    return {};
  },
  emit(t, d) {
    service.records.emit(seed.id, t, d);
  },
};
await service.memory.capture(
  "补充 Aurora 项目的长期约定：应用日志统一写入 aurora.log。",
  "已确认。",
  c,
);
service.records.finish(seed, "completed");
const http = server(service, resolve("web-dist"));
http.listen(0, "127.0.0.1");
await once(http, "listening");
const base = `http://127.0.0.1:${(http.address() as any).port}`;
const browser = await chromium.launch({ channel: "msedge", headless: true });
const errors: string[] = [],
  result: any = {
    mode: "actual-local-openviking-and-real-deepseek",
    screenshots: [],
  };
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base + "/#token=" + config.token);
  const sessions = [...service.sessions.values()];
  await page
    .locator(".session-item")
    .nth(sessions.findIndex((x) => x.id === s.id))
    .click();
  await page.getByRole("button", { name: "提交提取", exact: true }).click();
  await page.getByText("记忆提取 · 处理中", { exact: true }).waitFor();
  await page.screenshot({ path: join(output, "memory-web-processing.png") });
  await page
    .getByText("记忆提取 · 已完成", { exact: true })
    .waitFor({ timeout: 180000 });
  const run = service.records
    .runs()
    .filter(
      (r) => r.sessionId === s.id && r.versions.purpose === "memory_extraction",
    )
    .sort((a, b) => a.startedAt.localeCompare(b.startedAt))
    .at(-1)!;
  result.uiExtractionRun = run;
  for (const [theme, label] of [
    ["dark", "深色"],
    ["light", "浅色"],
  ]) {
    await page.getByRole("button", { name: label, exact: true }).click();
    await page.screenshot({ path: join(output, `memory-web-${theme}.png`) });
    result.screenshots.push(`memory-web-${theme}.png`);
  }
  await page.getByRole("button", { name: "查看记忆", exact: true }).click();
  assert(!(await page.locator(".inspector").innerText()).includes('"isDir"'));
  await page.getByRole("button", { name: "＋ 新建会话" }).click();
  await page.getByRole("combobox", { name: "执行内核" }).selectOption("dsh");
  assert(
    !(await page
      .getByRole("checkbox", { name: "启用 OpenViking 项目记忆" })
      .isDisabled()),
  );
  await page.getByRole("button", { name: "取消", exact: true }).click();
  result.web = {
    processing: true,
    completed: true,
    themes: ["dark", "light"],
    dshMemoryEnabled: true,
  };
} finally {
  await browser.close();
  await service.close();
  http.closeAllConnections();
  await new Promise<void>((r) => http.close(() => r()));
}

const env = {
  ...process.env,
  TEMP: "D:/Temp",
  TMP: "D:/Temp",
  CNCODE_DESKTOP_DATA: join(root, "desktop-memory-check"),
  CNCODE_DATA_DIR: config.dataDir,
  OPENVIKING_URL: original.memoryUrl!,
  OPENVIKING_PROJECTS_FILE: original.memoryProjectsFile!,
  OPENVIKING_OBSERVATIONS_FILE: original.memoryObservationsFile!,
};
delete (env as any).ELECTRON_RUN_AS_NODE;
const app = await _electron.launch({
  executablePath: require("electron"),
  args: [resolve("desktop/main.cjs")],
  env,
  timeout: 45000,
});
try {
  const page = await app.firstWindow();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.getByRole("heading", { name: "实现、检查，再继续。" }).waitFor();
  // The API list order is the same order used to render the session navigation.
  const sessions = JSON.parse(
    await readFile(join(root, "report.json"), "utf8"),
  );
  const actual = await page.evaluate(async () => {
    const token = sessionStorage.getItem("cncode-token");
    return (
      await fetch("/api/v1/sessions", {
        headers: { authorization: "Bearer " + token },
      })
    ).json();
  });
  await page
    .locator(".session-item")
    .nth(actual.findIndex((x: any) => x.id === sessions.runs[0].sessionId))
    .click();
  await page.getByText("记忆提取 · 已完成", { exact: true }).waitFor();
  for (const [theme, label] of [
    ["dark", "深色"],
    ["light", "浅色"],
  ]) {
    await page.getByRole("button", { name: label, exact: true }).click();
    await page.screenshot({
      path: join(output, `memory-electron-${theme}.png`),
    });
    result.screenshots.push(`memory-electron-${theme}.png`);
  }
  result.electron = { restoredRealExtraction: true, themes: ["dark", "light"] };
} finally {
  await app.close();
}
assert.deepEqual(errors, []);
result.consoleErrors = errors;
await writeFile(
  join(output, "memory-ui-result.json"),
  JSON.stringify(result, null, 2),
);
console.log("Real memory Web/Electron screenshots and UI state checks passed");
