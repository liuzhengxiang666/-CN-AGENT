// Runs inside the Agent container. No verifier files or answers are mounted here.
import { readFile, writeFile } from "node:fs/promises";
import { Service } from "../service.js";
import { loadConfig } from "../config.js";
const config = loadConfig();
config.dataDir = "/data/records";
config.mcp = [];
config.memoryUrl = undefined;
const service = new Service(config);
const engine = process.env.CNCODE_ENGINE === "dsh" ? "dsh" : "cncode";
const session = await service.create(engine, "/app", false);
const start = performance.now();
let id = "";
const poll = setInterval(() => {
  for (const i of service.pending.values())
    service.answer(
      i.id,
      i.kind === "approval"
        ? { allow: true }
        : {
            text: "Follow the task specification. Do not ask a human; report any unresolved ambiguity.",
          },
      "automation",
    );
}, 50);
const deadline = setTimeout(
  () => void service.stop(session.id),
  Number(process.env.CNCODE_DEADLINE_SECONDS) * 1000,
);
try {
  id = service.submit(
    session.id,
    "evaluation",
    await readFile("/data/prompt.txt", "utf8"),
  );
  await service.wait(session.id);
  const run = service.records.get(id);
  run.versions = {
    ...run.versions,
    task: process.env.CNCODE_TASK,
    fixtureCommit: process.env.CNCODE_FIXTURE_COMMIT,
    mode: "baseline",
    memory: "off",
    learning: "off",
    approvalPolicy: "allow-in-isolated-container",
    maxTokens: 8192,
    toolPolicy: "coding-only-v1",
  };
  service.records.save(run);
  await writeFile(
    "/data/result.json",
    JSON.stringify({ ...run, workerMs: performance.now() - start }, null, 2),
  );
} finally {
  clearInterval(poll);
  clearTimeout(deadline);
  await service.close();
}
