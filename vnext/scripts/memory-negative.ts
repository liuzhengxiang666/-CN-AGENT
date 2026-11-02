import assert from "node:assert/strict";
import { readFile, writeFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { loadConfig } from "../src/config.js";
import { Service } from "../src/service.js";
import type { EngineContext } from "../src/types.js";

const config = loadConfig(),
  evidence = resolve(process.argv[2]!);
const comparison = JSON.parse(
  await readFile(join(evidence, "report.json"), "utf8"),
);
const expectedPort = comparison.corrections.expectedPort;
const service = new Service({
  ...config,
  mcp: [],
  dataDir: join(evidence, "negative-records"),
});
const cwd = join(evidence, "project-a"),
  s = await service.create("cncode", cwd, true);
const result: any = {
  model: config.model,
  startedAt: new Date().toISOString(),
};
const capture = service.records.begin(s, config.model, {
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
    service.records.emit(capture.id, t, d);
  },
};
try {
  await service.memory.capture(
    "Aurora 项目的日志文件命名约定是 aurora.log，跨会话保留。",
    "已确认。",
    c,
  );
  service.records.finish(capture, "completed");
  const id = service.commitMemory(s.id);
  let taskId = "";
  const deadline = Date.now() + 30000;
  do {
    taskId =
      service.records.read(id).find((e) => e.type === "memory.commit.submitted")
        ?.data.result.task_id ?? "";
    if (
      service.memory
        .observations()
        .some((e) => e.taskId === taskId && e.type === "model.start")
    )
      break;
    assert(Date.now() < deadline, "No live extraction model request observed");
    await new Promise((r) => setTimeout(r, 25));
  } while (true);
  await service.stop(s.id);
  const stopped = service.records.get(id),
    events = service.records.read(id);
  const task = await service.memory.task(s, taskId);
  assert.equal(stopped.state, "cancelled");
  assert.equal(task?.status, "cancelled");
  assert(events.some((e) => e.type === "memory.cancel.settled"));
  result.cancellation = {
    run: stopped,
    task,
    events: events.filter(
      (e) => e.type.startsWith("memory.") || e.type === "usage",
    ),
    note: "Provider usage can be unknown after cancellation; unknown is not zero or proof of no billing",
  };

  const down = spawnSync("docker", ["stop", "cncode-openviking"], {
    stdio: "ignore",
  });
  assert.equal(down.status, 0);
  const unavailable = service.records.begin(s, config.model, {
    purpose: "memory_recall",
    acceptance: "service unavailable negative control",
  });
  const unavailableContext = {
    ...c,
    signal: AbortSignal.timeout(10000),
    emit(t: string, d?: any) {
      service.records.emit(unavailable.id, t, d);
    },
  };
  try {
    await assert.rejects(
      service.memory.recall("Aurora 最新服务端口", unavailableContext),
    );
    result.unavailableServiceRejected = true;
  } finally {
    service.records.finish(
      unavailable,
      "failed",
      "Expected unavailable-service negative control",
    );
    assert.equal(
      spawnSync("docker", ["start", "cncode-openviking"], { stdio: "ignore" })
        .status,
      0,
    );
  }
  const ready = Date.now() + 30000;
  for (;;) {
    try {
      if ((await fetch(config.memoryUrl + "/health")).ok) break;
    } catch {}
    assert(Date.now() < ready, "Restart did not become healthy");
    await new Promise((r) => setTimeout(r, 250));
  }
  const recovery = service.records.begin(s, config.model, {
    purpose: "memory_recall",
    acceptance: "restart persistence",
  });
  const live = {
    ...c,
    signal: AbortSignal.timeout(30000),
    emit(t: string, d?: any) {
      service.records.emit(recovery.id, t, d);
    },
  };
  const recalled = await service.memory.recall(
    "Aurora 已经迁移后的最新端口" + expectedPort,
    live,
  );
  assert(recalled.includes(String(expectedPort)));
  result.restartPersistence = true;
  result.recalledAfterRestart = recalled;
  service.records.finish(recovery, "completed");

  const deniedPath = join(cwd, "permission-check.txt");
  await rm(deniedPath, { force: true });
  const denial = await service.create("cncode", cwd, true);
  const denyPoll = setInterval(() => {
    for (const i of service.pending.values())
      service.answer(
        i.id,
        i.kind === "approval"
          ? { allow: false }
          : { text: "操作未获批准，不要继续写入。" },
        "automation",
      );
  }, 10);
  const runId = service.submit(
    denial.id,
    randomUUID(),
    "在当前工作区创建 permission-check.txt，内容为 approval-check；如果工具审批被拒绝，不要重试或改用其他工具。",
  );
  const limit = setTimeout(() => void service.stop(denial.id), 60000);
  try {
    await service.wait(denial.id);
  } finally {
    clearInterval(denyPoll);
    clearTimeout(limit);
  }
  const deniedEvents = service.records.read(runId);
  assert(
    deniedEvents.some(
      (e) => e.type === "interaction.answered" && e.data.answer.allow === false,
    ),
  );
  assert(!existsSync(deniedPath));
  result.memoryCannotBypassDeniedApproval = true;
  result.deniedRun = service.records.get(runId);
  result.completedAt = new Date().toISOString();
  result.status = "pass";
} catch (error) {
  result.status = "fail";
  result.error = String(error);
  throw error;
} finally {
  await service.close();
  await writeFile(
    join(evidence, "negative.json"),
    JSON.stringify(result, null, 2),
  );
  console.log(
    JSON.stringify({
      status: result.status,
      error: result.error,
      evidence: join(evidence, "negative.json"),
    }),
  );
}
