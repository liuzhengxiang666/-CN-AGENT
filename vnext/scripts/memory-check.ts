// Real-service acceptance. All real model calls are sequential.
import assert from "node:assert/strict";
import { mkdir, readFile, writeFile, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { loadConfig } from "../src/config.js";
import { Service } from "../src/service.js";
import type { Session, EngineContext, EngineId } from "../src/types.js";

const original = loadConfig();
process.env.CNCODE_MEMORY_EVAL = "1";
const root = process.argv[2]
  ? resolve(process.argv[2])
  : join(original.dataDir, "memory-check", randomUUID());
await mkdir(root, { recursive: true });
const service = new Service({
  ...original,
  mcp: [],
  dataDir: join(root, "records"),
});
const memory = service.memory;
const cwdA = join(root, "project-a"),
  cwdB = join(root, "project-b");
await mkdir(cwdA, { recursive: true });
await mkdir(cwdB, { recursive: true });
const a =
  [...service.sessions.values()].find((s) => s.cwd === cwdA) ??
  (await service.create("cncode", cwdA, true));
const b =
  [...service.sessions.values()].find((s) => s.cwd === cwdB) ??
  (await service.create("cncode", cwdB, true));
const report: any = {
  kind: "real-local-memory-acceptance",
  model: original.model,
  openviking: "0.5.3",
  embedding: "bge-small-zh-v1.5-f16",
  memoryPolicy: "one account and explicit peer per canonical project directory",
  startedAt: new Date().toISOString(),
  root,
  runs: [],
  isolation: {},
  corrections: {},
  limits: [
    "One sample per condition, not a stable success rate or performance superiority claim",
    "Synthetic configuration recall tasks, separate from the existing two coding benchmarks",
    "Local embedding token estimates are not provider actual tokens",
  ],
};
async function save() {
  await writeFile(join(root, "report.json"), JSON.stringify(report, null, 2));
}
if (process.argv[2]) {
  const previous = JSON.parse(
    await readFile(join(root, "report.json"), "utf8"),
  );
  if (previous.runs.some((r: any) => r.label === "extract-correction-a"))
    throw new Error("Cannot resume after mutating the original convention");
  await writeFile(
    join(root, "failed-before-resume-" + Date.now() + ".json"),
    JSON.stringify(previous, null, 2),
  );
  report.runs = previous.runs.filter((r: any) =>
    r.label.startsWith("extract-initial-"),
  );
  report.previousAttemptError = previous.error;
}
const tag = root
  .split(/[\\/]/)
  .at(-1)!
  .replaceAll("-", "")
  .slice(0, 6)
  .toUpperCase();
const initialPort = 40000 + (parseInt(tag.slice(0, 4), 16) % 15000);
const newPort = initialPort + 1;
const expected = {
  port: initialPort,
  envPrefix: `AURORA_${tag}_`,
  configFile: `aurora-${tag.toLowerCase()}.json`,
};
report.expected = expected;
report.toolPolicy =
  "memory-json-only-v1: no shell, glob, grep, skills, web or subagents; DSH file access limited to client-config.json";
const poll = setInterval(() => {
  for (const i of service.pending.values())
    service.answer(
      i.id,
      i.kind === "approval"
        ? { allow: true }
        : { text: "没有补充信息。不要猜测已有约定；缺失时明确报告。" },
      "automation",
    );
}, 25);
async function settle(s: Session, id: string, label: string) {
  const deadline = setTimeout(() => void service.stop(s.id), 180000);
  try {
    await service.wait(s.id);
  } finally {
    clearTimeout(deadline);
  }
  const run = service.records.get(id);
  const events = service.records.read(id);
  report.runs.push({
    label,
    ...run,
    memoryEvents: events.filter(
      (e) => e.type.startsWith("memory.") || e.type === "usage",
    ),
  });
  await save();
  console.log(
    JSON.stringify({ label, state: run.state, metrics: run.metrics }),
  );
  assert.equal(run.state, "completed", run.error);
  return run;
}
async function seed(s: Session, text: string, label: string) {
  if (
    report.runs.some((r: any) => r.label === label && r.state === "completed")
  )
    return;
  const run = service.records.begin(s, original.model, {
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
      service.records.emit(run.id, t, d);
    },
  };
  await memory.capture(text, "已确认这些项目约定。", c);
  service.records.finish(run, "completed");
  return settle(s, service.commitMemory(s.id), label);
}
try {
  await seed(
    a,
    `Aurora 项目跨会话长期约定：HTTP 服务端口${expected.port}，环境变量前缀 ${expected.envPrefix}，配置文件名 ${expected.configFile}。这些不是临时示例，后续编码必须沿用。`,
    "extract-initial-a",
  );
  await seed(
    b,
    "Borealis 项目长期配置约定：服务端口49183，环境变量前缀 BOREALIS_，配置文件名 borealis-config.json。只适用于 Borealis 项目。",
    "extract-initial-b",
  );
  const aListing = (await memory.list(a)) as any[];
  const aFile = aListing.find(
    (x) => !x.isDir && x.uri.endsWith(".md") && !x.uri.endsWith(".overview.md"),
  );
  assert(aFile, "No actual extracted memory file");
  const bRecall = JSON.stringify(
    await memory
      .client(b)
      .search(`Aurora ${expected.envPrefix} ${expected.port}`, {
        targetUri: "viking://~",
        contextType: "memory",
        limit: 10,
        telemetry: true,
      }),
  );
  report.isolation.broadSearchDoesNotLeakA =
    !bRecall.includes(expected.envPrefix) &&
    !bRecall.includes(String(expected.port));
  assert(report.isolation.broadSearchDoesNotLeakA);
  for (const read of [
    () => memory.read(b, aFile.uri),
    () => memory.client(b).read(aFile.uri),
  ]) {
    try {
      const content = await read();
      assert(
        !content.includes(expected.envPrefix) &&
          !content.includes(String(expected.port)),
        "Foreign project content leaked",
      );
    } catch (error: any) {
      assert([403, 404].includes(error.statusCode), String(error));
    }
  }
  report.isolation.foreignUriDoesNotExposeAContent = true;
  const taskA = report.runs[0].memoryEvents.find(
    (e: any) => e.type === "memory.commit.submitted",
  ).data.result.task_id;
  assert.equal(await memory.client(b).getTask(taskA), null);
  report.isolation.foreignTaskDenied = true;
  const spoof = await fetch(
    original.memoryUrl +
      "/api/v1/content/read?uri=" +
      encodeURIComponent(aFile.uri),
    {
      headers: {
        "X-API-Key": memory.identity(b).apiKey!,
        "X-OpenViking-Account": (memory.identity(a) as any).account,
      },
    },
  );
  report.isolation.spoofedAccountStatus = spoof.status;
  const spoofBody = JSON.stringify(await spoof.json());
  assert(
    !spoofBody.includes(expected.envPrefix) &&
      !spoofBody.includes(String(expected.port)),
    "Account header overrode tenant key",
  );
  assert([200, 400, 403, 404].includes(spoof.status));
  report.isolation.spoofedAccountHeaderCannotExposeA = true;
  await save();
  const prompt =
    "按本项目之前已经确定的长期配置约定，在当前目录生成 client-config.json，恰好包含 port（数字）、envPrefix（字符串）、configFile（字符串）三个字段。不要猜测约定值；不能确定时报告缺失。完成后检查文件。";
  const explicit =
    "在当前目录生成 client-config.json，内容恰好是 " +
    JSON.stringify(expected) +
    "，完成后读取检查。";
  for (const engine of ["cncode", "dsh"] as EngineId[]) {
    for (const task of ["withheld-conventions", "explicit-control"]) {
      for (const enabled of [false, true]) {
        await rm(join(cwdA, "client-config.json"), { force: true });
        const s = await service.create(engine, cwdA, enabled);
        const id = service.submit(
          s.id,
          randomUUID(),
          task === "explicit-control" ? explicit : prompt,
        );
        const label = `${task}-${engine}-${enabled ? "memory" : "no-memory"}`;
        await settle(s, id, label);
        let content: any;
        try {
          content = JSON.parse(
            await readFile(join(cwdA, "client-config.json"), "utf8"),
          );
        } catch {
          content = null;
        }
        const pass =
          !!content &&
          Object.keys(content).length === 3 &&
          Object.entries(expected).every(([k, v]) => content[k] === v);
        const run = service.records.get(id);
        run.outcome = pass ? "pass" : "fail";
        service.records.save(run);
        Object.assign(report.runs.at(-1), {
          outcome: run.outcome,
          verification: { expected, actual: content, pass },
        });
        await save();
        if (enabled || task === "explicit-control")
          assert(pass, label + " artifact acceptance failed");
        else
          assert(
            !pass,
            "No-memory withheld task unexpectedly obtained hidden convention values",
          );
      }
    }
  }
  await seed(
    a,
    `纠正 Aurora 的旧配置：HTTP 服务端口已经从${initialPort}迁移到${newPort}。今后新生成的配置只能使用${newPort}，旧端口${initialPort}已废弃。环境变量前缀 ${expected.envPrefix} 和配置名 ${expected.configFile} 都保持不变。`,
    "extract-correction-a",
  );
  await rm(join(cwdA, "client-config.json"), { force: true });
  const corrected = await service.create("cncode", cwdA, true);
  await settle(
    corrected,
    service.submit(corrected.id, randomUUID(), prompt),
    "corrected-new-session",
  );
  const updated = JSON.parse(
    await readFile(join(cwdA, "client-config.json"), "utf8"),
  );
  report.corrections = {
    expectedPort: newPort,
    actual: updated,
    pass: updated.port === newPort && updated.envPrefix === expected.envPrefix,
  };
  assert(report.corrections.pass, "Old port not superseded");
  const model = await readFile(
    join(
      original.dataDir,
      "openviking",
      "cache",
      "models",
      "bge-small-zh-v1.5-f16.gguf",
    ),
  );
  report.embeddingSha256 = createHash("sha256").update(model).digest("hex");
  report.completedAt = new Date().toISOString();
  report.status = "pass";
} catch (error) {
  report.status = "fail";
  report.error = String(error);
  throw error;
} finally {
  clearInterval(poll);
  await service.close();
  await save();
  console.log("Evidence: " + join(root, "report.json"));
}
