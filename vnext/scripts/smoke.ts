import { mkdir, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { loadConfig } from "../src/config.js";
import { Service } from "../src/service.js";
const engine = process.argv[2] === "dsh" ? "dsh" : "cncode";
const root = resolve("../.cncode/vnext/smoke-" + engine);
await mkdir(join(root, "workspace"), { recursive: true });
const config = loadConfig();
config.dataDir = join(root, "data");
config.mcp = [];
const service = new Service(config);
const session = await service.create(engine, join(root, "workspace"), false);
const timer = setTimeout(() => void service.stop(session.id), 120000);
try {
  const id = service.submit(
    session.id,
    "smoke",
    "Use your file tools to create hello.txt containing exactly CN Code smoke. Then read it and report what you observed.",
  );
  const poll = setInterval(() => {
    for (const i of service.pending.values())
      service.answer(i.id, { allow: true });
  }, 100);
  try {
    await service.wait(session.id);
  } finally {
    clearInterval(poll);
  }
  const run = service.records.get(id);
  console.log(
    JSON.stringify(
      {
        runId: id,
        engine,
        model: run.model,
        state: run.state,
        outcome: run.outcome,
        metrics: run.metrics,
        error: run.error,
      },
      null,
      2,
    ),
  );
  await writeFile(join(root, "summary.json"), JSON.stringify(run, null, 2));
  if (run.state !== "completed") process.exitCode = 1;
} finally {
  clearTimeout(timer);
  await service.close();
}
