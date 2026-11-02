import { DeepSeekHarness } from "@deepseek-ai/dsh-sdk-client";
import type { HarnessNotification } from "@deepseek-ai/dsh-sdk-client";
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { stringify } from "yaml";
import type { Memory } from "../memory.js";
import type { Usage } from "../types.js";
import { requireModel } from "../config.js";
import type {
  AppConfig,
  EngineAdapter,
  EngineContext,
  Json,
} from "../types.js";
export class Dsh implements EngineAdapter {
  readonly capabilities = {
    approval: true,
    questions: true,
    resume: true,
    stop: true,
    tools: true,
    usage: true,
    memory: true,
    toolTiming: "dsh-tools/execute",
  };
  private harness?: DeepSeekHarness;
  private current?: EngineContext;
  private closing?: Promise<void>;
  constructor(
    private config: AppConfig,
    private memory?: Memory,
  ) {}
  async close() {
    if (this.closing) return this.closing;
    const h = this.harness;
    this.harness = undefined;
    if (h) this.closing = h.close();
    await this.closing;
  }
  async run(input: string, ctx: EngineContext) {
    requireModel(this.config);
    if (ctx.session.memory && !this.memory)
      throw new Error("DSH 项目记忆未配置");
    ctx.signal.throwIfAborted();
    this.current = ctx;
    const started = performance.now();
    const home = join(this.config.dataDir, "dsh", ctx.session.id);
    mkdirSync(home, { recursive: true });
    const memoryIdentity = ctx.session.memory
      ? this.memory!.identity(ctx.session)
      : undefined;
    const memoryPeer = ctx.session.memory
      ? this.memory!.peer(ctx.session)
      : undefined;
    const memoryConfig = join(home, "ovcli.conf");
    const memoryHome = join(home, "openviking");
    mkdirSync(memoryHome, { recursive: true });
    writeFileSync(
      memoryConfig,
      JSON.stringify({
        url: this.config.memoryUrl,
        api_key: memoryIdentity?.apiKey,
        account: (memoryIdentity as any)?.account,
        user: "cncode",
        actor_peer_id: memoryPeer,
        plugin: {
          autoRecall: true,
          autoCapture: false,
          mcpEnabled: false,
          skillCatalog: false,
          skillExperience: false,
          recallPeerScope: "actor",
          recallQueryExpansion: "off",
          recallRewrite: "off",
          recallLimit: 5,
          recallMaxTokens: 1500,
          profileTokenBudget: 1500,
          timeoutMs: 120000,
        },
      }),
    );
    const observations = join(home, "observations.jsonl");
    writeFileSync(observations, "");
    let consumed = 0;
    const flush = () => {
      if (!existsSync(observations)) return;
      const raw = readFileSync(observations, "utf8");
      const lines = raw
        .slice(0, raw.lastIndexOf("\n") + 1)
        .split("\n")
        .filter(Boolean);
      while (consumed < lines.length) {
        const event = JSON.parse(lines[consumed]!);
        consumed++;
        if (event.type === "memory.operation" && event.data.telemetry?.id)
          this.memory?.recordObservations(
            ctx,
            (e) => e.telemetryId === event.data.telemetry.id,
            "memory_recall",
          );
        ctx.emit(
          event.type === "dsh.usage" ? "usage" : event.type,
          event.type === "dsh.usage"
            ? {
                requestId: event.data.requestId,
                purpose: event.data.purpose,
                usage: dshUsage(event.data.raw ?? {}),
              }
            : { ...event.data, executedAt: event.at },
        );
      }
    };
    const stop = () => {
      void this.close().catch((e) =>
        ctx.emit("cleanup.error", { message: String(e) }),
      );
    };
    ctx.signal.addEventListener("abort", stop, { once: true });
    const poll = setInterval(() => {
      try {
        flush();
      } catch (e) {
        ctx.emit("observer.error", { message: String(e) });
      }
    }, 100);
    try {
      if (!this.harness) {
        const patch = join(home, "cncode.patch.yml");
        writeFileSync(
          patch,
          stringify([
            {
              id: "llm-pi-ai",
              config: {
                providers: {
                  "cncode-route": {
                    apiKeyEnv: "CNCODE_DSH_KEY",
                    api: "openai-completions",
                    baseURL: this.config.baseUrl.replace(
                      /\/chat\/completions\/?$/,
                      "",
                    ),
                    compat: { thinkingFormat: "deepseek" },
                    models: [
                      {
                        id: this.config.model,
                        name: this.config.model,
                        contextWindow: 131072,
                        maxTokens: 8192,
                      },
                    ],
                    retryPolicy: { mode: "normal", maxRetries: 0 },
                  },
                },
              },
            },
            {
              insert: [
                ...(ctx.session.memory
                  ? [
                      {
                        id: "openviking-memory",
                        name: fileURLToPath(
                          import.meta.resolve("@openviking/dsh-memory-plugin"),
                        ),
                      },
                    ]
                  : []),
                {
                  id: "cncode-observer",
                  name: fileURLToPath(
                    new URL("../../runtime/dsh-observer.mjs", import.meta.url),
                  ).replaceAll("\\", "/"),
                },
              ],
            },
            { id: "session-telemetry-otel", disabled: true },
            { id: "tool-subagent", disabled: true },
          ]),
        );
        this.harness = new DeepSeekHarness({
          dshHome: home,
          cwd: ctx.session.cwd,
          processCwd: ctx.session.cwd,
          profile: "sdk",
          patches: [patch],
          provider: "cncode-route",
          model: this.config.model,
          maxTokens: 8192,
          initializeTimeoutMs: 60000,
          approvalRequests: true,
          env: {
            ...process.env,
            CNCODE_DSH_OBSERVATIONS: observations,
            CNCODE_DSH_KEY: this.config.apiKey,
            DSH_TELEMETRY_MODE: "OFF",
            OPENVIKING_RECALL_PEER_SCOPE: "actor",
            OPENVIKING_CLI_CONFIG_FILE: memoryConfig,
            OPENVIKING_CONFIG_FILE: join(memoryHome, "unused-server.conf"),
            OPENVIKING_CREDENTIAL_SOURCE: "ovcli",
            OPENVIKING_STATE_DIR: join(memoryHome, "state"),
            OPENVIKING_PENDING_DIR: join(memoryHome, "pending"),
            OPENVIKING_HOME: memoryHome,
            CNCODE_MEMORY_URL: ctx.session.memory
              ? this.config.memoryUrl
              : undefined,
          },
          onUserQuestion: async (request, { signal }) => {
            const answer = await this.current!.interact(
              "question",
              {
                native: request,
                question: request.questions
                  .map((q) => JSON.stringify(q))
                  .join("\n"),
              },
              signal,
            );
            return answer as any;
          },
        });
        this.harness.client.start();
        this.harness.client.onRequest(async (method, params) => {
          if (method !== "approval/request")
            throw new Error("不支持 SDK 请求 " + method);
          const answer = await this.current!.interact("approval", params);
          return { outcome: answer.allow ? "allowed-once" : "rejected" };
        });
        await this.harness.start();
        flush();
        if (
          !existsSync(observations) ||
          !readFileSync(observations, "utf8").includes("observer.ready")
        )
          throw new Error("DSH 执行观测插件未加载，拒绝无计时运行");
        if (ctx.session.dshSessionId)
          await this.harness.resumeSession(ctx.session.dshSessionId);
      }
      const session = this.harness.session(
        ctx.session.dshSessionId ?? ctx.session.id,
      );
      ctx.session.dshSessionId = session.id;
      ctx.save();
      ctx.emit("startup.end", { durationMs: performance.now() - started });
      ctx.signal.throwIfAborted();
      try {
        const result = await session.run(input, {
          onNotification: (n) => this.observe(n, ctx),
        });
        ctx.signal.throwIfAborted();
        const failure = result.events.findLast(
          (e) =>
            e.type === "turn/end" && (e.data as Json).reason?.kind === "error",
        );
        if (failure)
          throw new Error(
            (failure.data as Json).reason.error?.message ?? "DSH turn error",
          );
        ctx.emit("assistant.message", { text: result.finalResponse });
        if (!result.finalResponse)
          throw new Error("DSH 已空闲，但没有交付回答；检查原始事件");
        if (ctx.session.memory)
          await this.memory!.capture(input, result.finalResponse, ctx);
      } finally {
        ctx.signal.removeEventListener("abort", stop);
      }
    } finally {
      ctx.signal.removeEventListener("abort", stop);
      clearInterval(poll);
      flush();
      this.current = undefined;
    }
  }
  observe(n: HarnessNotification, c: EngineContext) {
    c.emit("dsh.notification", n);
    const e = n.params.event as Json | undefined;
    if (!e) return;
    const d = e.data ?? {};
    if (e.type === "tool/call")
      c.emit("tool.request", {
        callId: d.callId,
        name: d.name,
        arguments: d.arguments,
      });
    if (e.type === "tool/result")
      c.emit("tool.result", {
        callId:
          d.callId ??
          d.message?.source?.callId ??
          d.message?.content?.[0]?.toolCallId,
        result: d.message,
        meta: d.meta,
      });
  }
}

/** DSH counts uncached input separately. Prefer its authoritative full-call total. */
export function dshUsage(raw: Json): Usage {
  const count = (v: unknown) =>
    typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : null;
  const output = count(raw.outputTokens),
    uncached = count(raw.inputTokens),
    read = count(raw.cacheReadTokens),
    write = count(raw.cacheWriteTokens),
    total = count(raw.totalTokens);
  const input =
    total !== null && output !== null && total >= output
      ? total - output
      : uncached !== null && read !== null && write !== null
        ? uncached + read + write
        : null;
  return {
    input,
    output,
    cacheRead: read,
    cacheWrite: write,
    raw: { ...raw, inputSemantics: "dsh-disjoint", uncachedInput: uncached },
  };
}
