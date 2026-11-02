// DSH 0.2.2-alpha.2 documented dispatch and LLM waterfalls.
// Runs inside the child. Transport arrival timestamps are never execution time.
import { appendFileSync, existsSync, lstatSync } from "node:fs";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
export const name = "cncode-observer";
export const inject = ["tools", "llm"];
export function apply(ctx) {
  const emit = (type, data) =>
    appendFileSync(
      process.env.CNCODE_DSH_OBSERVATIONS,
      JSON.stringify({ type, data, at: new Date().toISOString() }) + "\n",
    );
  emit("observer.ready", { version: "0.2.2-alpha.2" });
  if (process.env.CNCODE_MEMORY_URL) {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, init) => {
      const target = String(url);
      if (!target.startsWith(process.env.CNCODE_MEMORY_URL + "/"))
        return originalFetch(url, init);
      const path = new URL(target).pathname,
        start = performance.now();
      if (init?.body && /\/search\/|\/commit$/.test(path)) {
        init = {
          ...init,
          body: JSON.stringify({ ...JSON.parse(init.body), telemetry: true }),
        };
      }
      const response = await originalFetch(url, init);
      const envelope = await response
        .clone()
        .json()
        .catch(() => ({}));
      emit("memory.operation", {
        path,
        status: response.status,
        durationMs: performance.now() - start,
        telemetry: envelope.telemetry ?? null,
      });
      return response;
    };
  }
  if (
    process.env.CNCODE_EVAL_TOOLS === "1" ||
    process.env.CNCODE_MEMORY_EVAL === "1"
  )
    ctx.on("agent/created", ({ agent }) => {
      const coding = new Set([
        "read",
        "write",
        "edit",
        "glob",
        "grep",
        "bash",
        "pwsh",
        "ask_user_question",
        "load_skill",
      ]);
      if (process.env.CNCODE_MEMORY_EVAL === "1") {
        coding.clear();
        for (const name of ["read", "write", "edit", "ask_user_question"])
          coding.add(name);
      }
      const allow = ctx.tools
        .definitions()
        .map((t) => t.name)
        .filter((name) => coding.has(name));
      agent.ctx.effect(() => agent.ctx.tools.restrict({ allow }));
      emit("tool.policy", {
        scope:
          process.env.CNCODE_MEMORY_EVAL === "1"
            ? "memory-json-only-v1"
            : "coding-only-v1",
        allow,
      });
      return undefined;
    });
  ctx.on("tools/execute", async (exec, next) => {
    const data = {
        callId: exec.callId,
        name: exec.name,
        source: "dsh-tools/execute",
      },
      start = performance.now();
    emit("tool.start", data);
    let success = false,
      dispatched = false;
    try {
      if (
        process.env.CNCODE_MEMORY_EVAL === "1" &&
        ["read", "write", "edit"].includes(exec.name)
      ) {
        const expected = resolve(process.cwd(), "client-config.json");
        const requested = exec.arguments?.file_path;
        if (
          typeof requested !== "string" ||
          resolve(process.cwd(), requested) !== expected ||
          (existsSync(expected) && lstatSync(expected).isSymbolicLink())
        )
          throw new Error(
            "Memory experiment permits only client-config.json in the current workspace; reports and other files are unavailable",
          );
      }
      dispatched = true;
      const result = await next();
      success = result.isError === false;
      return result;
    } finally {
      emit("tool.end", {
        ...data,
        executed: dispatched,
        success,
        durationMs: performance.now() - start,
      });
    }
  });
  ctx.on("llm/stream", async function* (options, next) {
    const requestId = randomUUID(),
      purpose = options.purpose ?? "conversation",
      start = performance.now();
    let usage, failure;
    emit("model.start", { requestId, purpose });
    try {
      for await (const chunk of next()) {
        if (chunk.type === "usage") usage = chunk.usage;
        if (chunk.type === "finish" && chunk.reason?.kind === "error")
          failure = chunk.reason.failure;
        yield chunk;
      }
    } finally {
      emit("dsh.usage", {
        requestId,
        purpose,
        raw: failure ? null : (usage ?? null),
        adapterUsage: usage ?? null,
        failure,
      });
      emit("model.end", {
        requestId,
        purpose,
        durationMs: performance.now() - start,
      });
    }
  });
}
