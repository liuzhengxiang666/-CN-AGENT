import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  rm,
  appendFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer } from "node:http";
import { once } from "node:events";
import { Records, summarize } from "../records.js";
import { Memory } from "../memory.js";
import { Service } from "../service.js";
import { server } from "../server.js";
import { usage, sse, modelRequest } from "../provider.js";
import { dshUsage } from "../engines/dsh.js";
import { builtins, command, executeTool } from "../tools.js";
import { migrateMcp } from "../config.js";
import type { AppConfig, EngineContext, RunEvent } from "../types.js";

const scratch =
  process.env.CNCODE_TEST_TMP ??
  (process.platform === "win32"
    ? "D:/Temp/cncode-tests"
    : join(tmpdir(), "cncode-tests"));
async function fixture(t: any) {
  await mkdir(scratch, { recursive: true });
  const dir = await mkdtemp(join(scratch, "case-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}
const config = (dir: string): AppConfig => ({
  dataDir: dir,
  model: "fixture",
  apiKey: "secret-key",
  baseUrl: "http://unused",
  protocol: "openai",
  token: "test-token",
  host: "127.0.0.1",
  port: 0,
  configPath: "",
  mcp: [],
});
const event = (type: string, data: any, ms = 0): RunEvent => ({
  type,
  data,
  seq: 0,
  runId: "run",
  at: new Date(ms).toISOString(),
});
function context(
  cwd: string,
  signal = new AbortController().signal,
): EngineContext {
  return {
    session: {
      id: "session",
      cwd,
      engine: "cncode",
      createdAt: "",
      messages: [],
      inputs: {},
      memory: false,
    },
    signal,
    emit() {},
    save() {},
    interact: async () => ({ allow: true }),
  };
}

test("usage is unknown without counters; DSH cache input normalizes to total prompt", () => {
  assert.equal(usage().input, null);
  assert.equal(usage({ prompt_tokens: 0 }).input, 0);
  assert.deepEqual(
    dshUsage({
      inputTokens: 10,
      outputTokens: 5,
      cacheReadTokens: 30,
      totalTokens: 45,
    }).input,
    40,
  );
  assert.equal(dshUsage({ inputTokens: 10, outputTokens: 5 }).input, null);
  const metrics = summarize([
    event("model.start", { requestId: "a", purpose: "conversation" }),
    event("usage", {
      requestId: "a",
      purpose: "conversation",
      usage: usage({ prompt_tokens: 10, completion_tokens: 2 }),
    }),
    event("usage", {
      requestId: "a",
      purpose: "conversation",
      usage: usage({ prompt_tokens: 10, completion_tokens: 2 }),
    }),
    event("model.start", { requestId: "b", purpose: "compaction" }),
  ]);
  assert.equal(metrics.requests, 2);
  assert.equal(metrics.inputTokens, null);
  assert.equal(metrics.byPurpose.conversation.inputTokens, 10);
  assert.equal(metrics.byPurpose.compaction.inputTokens, null);
});
test("parallel human waits use union and tool totals stay distinct from wall time", () => {
  const m = summarize([
    event("interaction", { id: "a" }, 0),
    event("interaction", { id: "b" }, 5),
    event("interaction.answered", { id: "a" }, 10),
    event("interaction.cancelled", { id: "b" }, 20),
    event("tool.end", { durationMs: 30 }, 30),
    event("tool.end", { durationMs: 40 }, 40),
  ]);
  assert.equal(m.humanWaitMs, 20);
  assert.equal(m.phaseTotalsMs["tool.end"], 70);
  assert.equal(m.toolTimeIsWallTime, false);
});
test("records redact secrets, enforce IDs and preserve unverified delivery", async (t) => {
  const dir = await fixture(t),
    r = new Records(dir, ["secret-key", "token"]);
  const run = r.begin(context(dir).session, "model", {});
  r.emit(run.id, "secret", { text: "secret-key Bearer abc.def token" });
  r.finish(run, "completed");
  assert.equal(r.get(run.id).outcome, "unverified");
  assert(!JSON.stringify(r.read(run.id)).includes("secret-key"));
  assert.throws(() => r.runDir("../oops"));
  assert.throws(() => r.session({ ...context(dir).session, id: "../oops" }));
});
test("SSE accepts CRLF split across chunks and multibyte text", async () => {
  const chunks = ['data: {"text":"你"}\r', "\n\r", "\ndata: [DONE]\r\n\r\n"];
  const bytes = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (const s of chunks) c.enqueue(bytes.encode(s));
      c.close();
    },
  });
  const result = [];
  for await (const item of sse(stream)) result.push(item);
  assert.deepEqual(result, [{ text: "你" }]);
});
test("tool rejects malformed arguments, denied permissions and path escape before execution", async (t) => {
  const dir = await fixture(t),
    ctx = context(dir),
    events: any[] = [];
  ctx.emit = (type, data) => events.push({ type, data });
  const tools = builtins();
  const call = (args: any) => ({
    id: "a",
    type: "function" as const,
    function: { name: "write_file", arguments: JSON.stringify(args) },
  });
  assert.match(await executeTool(call({ path: "x" }), tools, ctx), /缺失/);
  ctx.interact = async () => ({ allow: false });
  assert.match(
    await executeTool(call({ path: "x", content: "data" }), tools, ctx),
    /拒绝/,
  );
  ctx.interact = async () => ({ allow: true });
  assert.match(
    await executeTool(
      call({ path: "../outside", content: "data" }),
      tools,
      ctx,
    ),
    /越过/,
  );
  assert(!events.some((e) => e.type === "tool.end" && e.data.success));
  assert.equal(events[0].data.durationMs, null);
});
test("shell timeout and abort settle after process tree shutdown", async (t) => {
  const dir = await fixture(t);
  await assert.rejects(
    command(
      process.execPath,
      ["-e", "setTimeout(()=>{},60000)"],
      dir,
      undefined,
      100,
    ),
    /超时/,
  );
  const controller = new AbortController();
  const running = command(
    process.execPath,
    ["-e", "setTimeout(()=>{},60000)"],
    dir,
    controller.signal,
  );
  setTimeout(() => controller.abort(), 100);
  await assert.rejects(running, /取消/);
});
test("legacy flat MCP values retain names, headers and env", () => {
  assert.deepEqual(
    migrateMcp({
      "mcp.server.x.command": "node",
      "mcp.server.x.args": "a, b",
      "mcp.server.x.env.KEY": "hidden",
      "mcp.server.x.headers.X": "v",
    }),
    [
      {
        name: "x",
        command: "node",
        args: ["a", "b"],
        env: { KEY: "hidden" },
        headers: { X: "v" },
      },
    ],
  );
});
test("cancelled multi-tool call keeps one result per tool; resume sends valid native history", async (t) => {
  const dir = await fixture(t);
  let requests = 0;
  let history: any[] = [];
  const mock = createServer(async (req, res) => {
    let body = "";
    for await (const b of req) body += b;
    const input = JSON.parse(body);
    history = input.messages;
    requests++;
    res.writeHead(200, { "content-type": "text/event-stream" });
    const payload =
      requests === 1
        ? {
            choices: [
              {
                delta: {
                  tool_calls: [
                    {
                      index: 0,
                      id: "t1",
                      function: {
                        name: "ask_user_question",
                        arguments: '{"question":"q"}',
                      },
                    },
                    {
                      index: 1,
                      id: "t2",
                      function: { name: "list_files", arguments: "{}" },
                    },
                  ],
                },
                finish_reason: "tool_calls",
              },
            ],
          }
        : {
            choices: [
              { delta: { content: "recovered" }, finish_reason: "stop" },
            ],
            usage: { prompt_tokens: 20, completion_tokens: 3 },
          };
    res.end("data: " + JSON.stringify(payload) + "\n\ndata: [DONE]\n\n");
  });
  mock.listen(0, "127.0.0.1");
  await once(mock, "listening");
  t.after(() => mock.close());
  const c = config(join(dir, "data"));
  c.baseUrl = `http://127.0.0.1:${(mock.address() as any).port}`;
  const s = new Service(c);
  t.after(() => s.close());
  const session = await s.create("cncode", dir);
  const id = s.submit(session.id, "first", "go");
  while (!s.pending.size) await new Promise((r) => setTimeout(r, 10));
  await s.stop(session.id);
  assert.equal(s.records.get(id).state, "cancelled");
  assert.equal(session.messages.filter((m) => m.role === "tool").length, 2);
  const second = s.submit(session.id, "second", "continue");
  assert.equal(s.submit(session.id, "second", "continue"), second);
  await s.wait(session.id);
  assert.equal(requests, 2);
  assert.equal(s.records.get(second).state, "completed");
  assert.equal(history.filter((m) => m.role === "tool").length, 2);
});
test("run.end follows cleanup; restart marks interrupted and idempotency survives reload", async (t) => {
  const dir = await fixture(t);
  let calls = 0;
  const engine = {
    capabilities: {},
    async run(_i: string, c: EngineContext) {
      calls++;
      c.emit("assistant.message", { text: "ok" });
    },
    async close() {
      await new Promise((r) => setTimeout(r, 10));
    },
  };
  const s = new Service(config(dir), () => engine),
    session = await s.create("cncode", dir);
  const id = s.submit(session.id, "same", "go");
  assert.equal(s.submit(session.id, "same", "go"), id);
  await s.wait(session.id);
  assert(!session.activeRun);
  assert.equal(calls, 1);
  assert.equal(s.records.read(id).at(-1)?.type, "run.end");
  const pending = s.records.begin(session, "model", {});
  const restarted = new Service(config(dir), () => engine);
  assert.equal(restarted.records.get(pending.id).state, "interrupted");
  assert.equal(restarted.submit(session.id, "same", "go"), id);
});
test("HTTP authentication, replay after sequence, idempotent input and memory-off sessions", async (t) => {
  const dir = await fixture(t);
  let calls = 0;
  const service = new Service(config(dir), () => ({
    capabilities: {},
    async run(_i, c) {
      calls++;
      c.emit("assistant.message", { text: "ok" });
    },
    async close() {},
  }));
  const http = server(service);
  http.listen(0, "127.0.0.1");
  await once(http, "listening");
  t.after(async () => {
    await service.close();
    http.closeAllConnections();
    http.close();
  });
  const base = `http://127.0.0.1:${(http.address() as any).port}/api/v1/`;
  assert.equal((await fetch(base + "sessions")).status, 401);
  const headers = {
    authorization: "Bearer test-token",
    "content-type": "application/json",
  };
  const session = (await (
    await fetch(base + "sessions", {
      method: "POST",
      headers,
      body: JSON.stringify({ cwd: dir, engine: "cncode" }),
    })
  ).json()) as any;
  assert.equal(session.memory, false);
  const send = () =>
    fetch(base + `sessions/${session.id}/inputs`, {
      method: "POST",
      headers,
      body: JSON.stringify({ inputId: "once", text: "hi" }),
    });
  const a = (await (await send()).json()) as any;
  const b = (await (await send()).json()) as any;
  assert.equal(a.runId, b.runId);
  await service.wait(session.id);
  assert.equal(calls, 1);
  const controller = new AbortController();
  const stream = await fetch(base + `runs/${a.runId}/events?after=1`, {
    headers,
    signal: controller.signal,
  });
  const reader = stream.body!.getReader();
  const { value } = await reader.read();
  const text = new TextDecoder().decode(value);
  assert(!text.includes("id: 1\n"));
  assert(text.includes("id: 2\n"));
  controller.abort();
});

test("restart recovers truncated event tail before appending interruption", async (t) => {
  const dir = await fixture(t),
    records = new Records(dir),
    run = records.begin(context(dir).session, "fixture", {});
  records.emit(run.id, "run.start", {});
  await appendFile(
    join(records.runDir(run.id), "events.jsonl"),
    '{"seq":2,"at":',
  );
  const reloaded = new Records(dir);
  reloaded.finish(run, "interrupted");
  assert.deepEqual(
    reloaded.read(run.id).map((e) => e.seq),
    [1, 2],
  );
  assert.equal(reloaded.read(run.id).at(-1)?.type, "run.end");
});

test("OpenViking SDK contract scopes query and captured messages; commit is only submitted", async (t) => {
  const dir = await fixture(t),
    seen: any[] = [];
  let sessionExists = false;
  const http = createServer(async (req, res) => {
    let body = "";
    for await (const b of req) body += b;
    const data = body ? JSON.parse(body) : {};
    seen.push({
      url: req.url,
      method: req.method,
      data,
      peer: req.headers["x-openviking-actor-peer"],
    });
    res.setHeader("content-type", "application/json");
    if (
      req.url?.includes("/sessions/") &&
      req.method === "GET" &&
      !sessionExists
    ) {
      res.statusCode = 404;
      res.end(
        JSON.stringify({
          status: "error",
          error: { code: "NOT_FOUND", message: "missing" },
        }),
      );
      return;
    }
    if (req.url === "/api/v1/sessions" && req.method === "POST")
      sessionExists = true;
    res.end(
      JSON.stringify({
        status: "ok",
        result: req.url?.endsWith("/commit")
          ? { task_id: "task-1", status: "pending" }
          : { memories: [] },
      }),
    );
  });
  http.listen(0, "127.0.0.1");
  await once(http, "listening");
  t.after(() => http.close());
  const c = config(dir);
  c.memoryUrl = `http://127.0.0.1:${(http.address() as any).port}`;
  const memory = new Memory(c),
    ctx = context(dir);
  ctx.session.memory = true;
  const other = { ...ctx.session, cwd: join(dir, "other") };
  assert.notEqual(memory.peer(ctx.session), memory.peer(other));
  await memory.recall("port", ctx);
  await memory.capture("port=5328", "saved", ctx);
  const result = await memory.commit(ctx.session);
  assert.equal(result.state, "submitted");
  assert.equal(result.usage, null);
  const query = seen.find((x) => x.data.query === "port");
  assert.equal(query.data.target_uri, memory.scope(ctx.session));
  assert.equal(query.peer, memory.peer(ctx.session));
  const messages = seen.filter((x) => x.url.endsWith("/messages"));
  assert.equal(messages.length, 2);
  assert(messages.every((x) => x.data.peer_id === memory.peer(ctx.session)));
  await assert.rejects(
    memory.remove(
      ctx.session,
      "viking://user/default/peers/other/memories/key",
    ),
    /当前项目/,
  );
  await assert.rejects(
    memory.read(ctx.session, memory.scope(ctx.session) + "/../private"),
    /非法/,
  );
});

test("prototype-like input ID remains idempotent across persistence", async (t) => {
  const dir = await fixture(t);
  let calls = 0;
  const factory = () => ({
    capabilities: {},
    async run() {
      calls++;
    },
    async close() {},
  });
  const service = new Service(config(dir), factory),
    session = await service.create("cncode", dir);
  const first = service.submit(session.id, "__proto__", "hello");
  await service.wait(session.id);
  assert.equal(service.submit(session.id, "__proto__", "hello"), first);
  const restored = new Service(config(dir), factory);
  assert.equal(restored.submit(session.id, "__proto__", "hello"), first);
  assert.equal(calls, 1);
});

test("automated approval waits are recorded separately from human waits", () => {
  const m = summarize([
    event("interaction", { id: "auto" }, 0),
    event("interaction", { id: "human" }, 5),
    event("interaction.answered", { id: "auto", actor: "automation" }, 10),
    event("interaction.answered", { id: "human", actor: "human" }, 20),
  ]);
  assert.equal(m.automatedWaitMs, 10);
  assert.equal(m.humanWaitMs, 15);
  assert.equal(m.interactionWaitMs, 20);
});

test("service propagates automated answer origin into final metrics", async (t) => {
  const dir = await fixture(t);
  const s = new Service(config(dir), () => ({
    capabilities: {},
    async run(_input, c) {
      await c.interact("approval", { tool: "fixture" });
    },
    async close() {},
  }));
  const session = await s.create("cncode", dir);
  const runId = s.submit(session.id, "auto-answer", "go");
  while (!s.pending.size) await new Promise((r) => setTimeout(r, 1));
  await new Promise((r) => setTimeout(r, 15));
  s.answer([...s.pending.keys()][0]!, { allow: true }, "automation");
  await s.wait(session.id);
  assert.equal(s.records.get(runId).metrics.humanWaitMs, 0);
  assert(s.records.get(runId).metrics.automatedWaitMs >= 15);
  assert(
    s.records
      .read(runId)
      .some(
        (e) =>
          e.type === "interaction.answered" && e.data.actor === "automation",
      ),
  );
});
