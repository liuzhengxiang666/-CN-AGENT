// Real pinned DSH runtime, deterministic local model endpoint. Not model acceptance.
import { createServer } from "node:http";
import { once } from "node:events";
import { mkdir, mkdtemp, writeFile, access } from "node:fs/promises";
import { join } from "node:path";
import { Service } from "../src/service.js";
import type { AppConfig } from "../src/types.js";
const root =
  process.platform === "win32"
    ? "D:/Temp/cncode-dsh-protocol"
    : "/tmp/cncode-dsh-protocol";
await mkdir(root, { recursive: true });
const dir = await mkdtemp(join(root, "run-"));
let count = 0;
const http = createServer(async (req, res) => {
  let body = "";
  for await (const x of req) body += x;
  const b = JSON.parse(body);
  count++;
  const tool = b.tools?.find((t: any) => t.function.name === "write");
  let delta: any = { content: "Protocol fixture finished" },
    reason = "stop";
  if (count === 1) {
    if (!tool) throw new Error("Pinned DSH write tool missing");
    delta = {
      tool_calls: [
        {
          index: 0,
          id: "native-write",
          type: "function",
          function: {
            name: "write",
            arguments: JSON.stringify({
              file_path: join(dir, "workspace", "fixture.txt"),
              content: "runtime-observer",
            }),
          },
        },
      ],
    };
    reason = "tool_calls";
  }
  if (count === 2 || count === 3) {
    const name = count === 2 ? "ask_user_question" : "write";
    const args =
      count === 2
        ? {
            questions: [
              {
                id: "verify",
                question: "Continue protocol check?",
                options: [{ label: "Continue" }],
              },
            ],
          }
        : {
            file_path: join(dir, "denied.txt"),
            content: "must not write",
            sandbox_permissions: "danger-full-access",
            justification: "Protocol denial test",
          };
    delta = {
      tool_calls: [
        {
          index: 0,
          id: "native-" + count,
          type: "function",
          function: { name, arguments: JSON.stringify(args) },
        },
      ],
    };
    reason = "tool_calls";
  }
  if (count === 6) {
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.write(": holding for cancellation\n\n");
    return;
  }
  res.writeHead(200, { "content-type": "text/event-stream" });
  res.end(
    "data: " +
      JSON.stringify({
        id: "req" + count,
        object: "chat.completion.chunk",
        choices: [{ index: 0, delta, finish_reason: reason }],
        usage: {
          prompt_tokens: 100,
          completion_tokens: 10,
          prompt_cache_hit_tokens: 20,
          total_tokens: 110,
        },
      }) +
      "\n\ndata: [DONE]\n\n",
  );
});
http.listen(0, "127.0.0.1");
await once(http, "listening");
await mkdir(join(dir, "workspace"));
await writeFile(join(dir, "workspace", "AGENTS.md"), "Protocol fixture only.");
const config: AppConfig = {
  dataDir: join(dir, "data"),
  apiKey: "fixture-not-real",
  baseUrl: `http://127.0.0.1:${(http.address() as any).port}/v1`,
  model: "deepseek-v4-flash",
  protocol: "openai",
  host: "127.0.0.1",
  port: 0,
  token: "fixture",
  configPath: "",
  mcp: [],
};
const service = new Service(config);
const session = await service.create("dsh", join(dir, "workspace"));
const timer = setInterval(() => {
  for (const i of service.pending.values())
    service.answer(
      i.id,
      i.kind === "approval"
        ? { allow: false }
        : {
            answers: i.payload.native.questions.map((q: any) => ({
              id: q.id,
              selected: ["Continue"],
            })),
          },
    );
}, 50);
try {
  for (const [inputId, text] of [
    ["one", "Create fixture.txt"],
    ["two", "Continue after resume"],
  ]) {
    const id = service.submit(session.id, inputId!, text!);
    const deadline = setTimeout(() => void service.stop(session.id), 30000);
    await service.wait(session.id);
    clearTimeout(deadline);
    const run = service.records.get(id),
      events = service.records.read(id);
    if (inputId === "one") {
      if (
        !events.some(
          (e) => e.type === "interaction" && e.data.kind === "question",
        ) ||
        !events.some(
          (e) => e.type === "interaction" && e.data.kind === "approval",
        )
      )
        throw new Error("Native interaction callbacks were not exercised");
      let exists = false;
      try {
        await access(join(dir, "denied.txt"));
        exists = true;
      } catch {}
      if (exists) throw new Error("Rejected write executed");
    }
    console.log(
      JSON.stringify({
        runId: id,
        state: run.state,
        error: run.error,
        metrics: run.metrics,
        toolEnds: events
          .filter((e) => e.type === "tool.end")
          .map((e) => e.data),
      }),
    );
    if (run.state !== "completed") process.exitCode = 1;
    if (
      inputId === "one" &&
      !events.some((e) => e.type === "tool.end" && e.data.durationMs > 0)
    )
      throw new Error("No real dispatch timing");
  }
  const third = service.submit(session.id, "three", "hold-cancel");
  const until = Date.now() + 30000;
  while (!service.records.read(third).some((e) => e.type === "model.start")) {
    if (Date.now() > until) throw new Error("No model start to cancel");
    await new Promise((r) => setTimeout(r, 50));
  }
  await service.stop(session.id);
  if (service.records.get(third).state !== "cancelled")
    throw new Error("DSH cancellation did not settle");
  console.log(
    "PASS: native question, approval denial, resume, active model cancellation",
  );
} finally {
  clearInterval(timer);
  await service.close();
  http.closeAllConnections();
  http.close();
}
