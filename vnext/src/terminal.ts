import { createInterface } from "node:readline";
import { stdin, stdout } from "node:process";
import { randomUUID } from "node:crypto";
import type { Json } from "./types.js";
const base = process.env.CNCODE_SERVER ?? "http://127.0.0.1:3040",
  token = process.env.CNCODE_TOKEN ?? "";
const api = async (path: string, body?: Json) => {
  const r = await fetch(base + "/api/v1/" + path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const b = (await r.json()) as Json;
  if (!r.ok) throw new Error(b.error);
  return b;
};
const rl = createInterface({
  input: stdin,
  output: stdout,
  terminal: stdout.isTTY,
});
let sessionId = process.env.CNCODE_SESSION ?? "",
  runId = "",
  seq = 0,
  pending: Json | undefined,
  streamed = false,
  polling = false,
  handling = false,
  quitting = false;
let retry: { text: string; inputId: string } | undefined;
const prompt = () => {
  if (!quitting) {
    rl.setPrompt(pending ? "回答 › " : "你 › ");
    rl.prompt();
  }
};
async function open(id: string) {
  const s = await api("sessions/" + id);
  sessionId = s.id;
  const runs = (await api("runs")) as any;
  const latest = runs.filter((r: Json) => r.sessionId === id).at(0);
  runId = s.activeRun ?? latest?.id ?? "";
  seq = 0;
  pending = undefined;
  streamed = false;
  console.log("会话 " + sessionId + " · " + s.engine);
}
async function create(engine: string) {
  const c = await api("config");
  await open(
    (
      await api("sessions", {
        engine,
        cwd: process.env.CNCODE_WORKSPACE ?? c.workspace,
        memory: false,
      })
    ).id,
  );
}
async function poll() {
  if (!runId || polling || quitting) return;
  polling = true;
  try {
    const info = await api("runs/" + runId);
    const live = (await api("interactions")) as any;
    for (const e of info.events.filter((e: Json) => e.seq > seq)) {
      seq = e.seq;
      if (
        e.type === "assistant.delta" &&
        (e.data.purpose ?? "conversation") === "conversation"
      ) {
        stdout.write(e.data.text);
        streamed = true;
      }
      if (e.type === "assistant.message" && !streamed) console.log(e.data.text);
      if (
        e.type === "interaction" &&
        live.some((i: Json) => i.id === e.data.id)
      ) {
        pending = e.data;
        console.log(
          "\n" +
            (pending!.kind === "approval"
              ? "批准工具？ [y/N] " + JSON.stringify(pending!.payload)
              : JSON.stringify(pending!.payload)),
        );
        prompt();
      }
      if (
        ["interaction.answered", "interaction.cancelled"].includes(e.type) &&
        pending?.id === e.data.id
      )
        pending = undefined;
      if (e.type === "run.end") {
        console.log(
          "\n[执行 " + e.data.state + " · 验收 " + e.data.outcome + "]",
        );
        if (e.data.error) console.log(e.data.error);
        runId = "";
        pending = undefined;
        prompt();
      }
    }
  } catch (e: any) {
    console.error(e.message);
  } finally {
    polling = false;
  }
}
async function line(text: string) {
  if (text === "/quit") {
    quitting = true;
    if (runId) await api(`sessions/${sessionId}/stop`, {});
    rl.close();
    stdin.destroy();
    return;
  }
  if (text === "/stop") {
    await api(`sessions/${sessionId}/stop`, {});
    await poll();
    return;
  }
  if (handling) {
    console.log("请求处理中");
    return;
  }
  handling = true;
  try {
    if (text === "/sessions") {
      console.log(JSON.stringify(await api("sessions"), null, 2));
      return;
    }
    if (text.startsWith("/session ")) {
      await open(text.slice(9).trim());
      await poll();
      return;
    }
    if (text.startsWith("/engine ")) {
      await create(text.slice(8).trim());
      return;
    }
    if (pending) {
      const i = pending;
      let response: Json =
        i.kind === "approval"
          ? { allow: text.toLowerCase() === "y" }
          : { text };
      if (i.payload.native)
        response = {
          answers: i.payload.native.questions.map((q: Json) => ({
            id: q.id,
            selected: [],
            custom: text,
          })),
        };
      await api("interactions/" + i.id, response);
      pending = undefined;
      return;
    }
    if (runId) {
      console.log("任务运行中；输入 /stop 或 Ctrl+C 停止");
      return;
    }
    if (!text.trim()) return;
    retry = retry?.text === text ? retry : { text, inputId: randomUUID() };
    const result = await api(`sessions/${sessionId}/inputs`, retry);
    runId = result.runId;
    retry = undefined;
    seq = 0;
    streamed = false;
    await poll();
  } catch (e: any) {
    console.error(e.message);
  } finally {
    handling = false;
    prompt();
  }
}
try {
  if (sessionId) await open(sessionId);
  else await create(process.argv[2] ?? "cncode");
  console.log("CN Code · /quit /stop /sessions /session ID /engine cncode|dsh");
  const timer = setInterval(() => void poll(), 200);
  rl.on("line", (text) => void line(text));
  rl.on("SIGINT", () => void line("/stop"));
  rl.on("close", () => clearInterval(timer));
  prompt();
} catch (e: any) {
  console.error(e.message);
  process.exitCode = 1;
  rl.close();
}
