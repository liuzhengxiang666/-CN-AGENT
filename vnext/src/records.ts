import {
  mkdirSync,
  writeFileSync,
  renameSync,
  appendFileSync,
  readFileSync,
  readdirSync,
  existsSync,
} from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import type { Session, Run, RunEvent, Json, Usage } from "./types.js";
export function atomic(path: string, value: unknown) {
  const tmp = path + "." + randomUUID() + ".tmp";
  writeFileSync(tmp, JSON.stringify(value, null, 2));
  renameSync(tmp, path);
}
export class Records {
  events = new EventEmitter();
  constructor(
    readonly root: string,
    readonly secrets: string[] = [],
  ) {
    mkdirSync(join(root, "sessions"), { recursive: true });
    mkdirSync(join(root, "runs"), { recursive: true });
  }
  scrub<T>(value: T): T {
    let s = JSON.stringify(value);
    for (const secret of this.secrets
      .filter(Boolean)
      .sort((a, b) => b.length - a.length))
      s = s.split(secret).join("[REDACTED]");
    return JSON.parse(
      s.replace(/(Bearer\s+)[A-Za-z0-9_.\-]+/g, "$1[REDACTED]"),
    );
  }
  session(s: Session) {
    atomic(
      join(this.root, "sessions", this.sessionId(s.id) + ".json"),
      this.scrub(s),
    );
  }
  private sessionId(id: string) {
    if (!/^[a-zA-Z0-9-]+$/.test(id)) throw new Error("非法会话 ID");
    return id;
  }
  sessions(): Session[] {
    return readdirSync(join(this.root, "sessions"))
      .filter((x) => x.endsWith(".json"))
      .map((x) =>
        JSON.parse(readFileSync(join(this.root, "sessions", x), "utf8")),
      );
  }
  runDir(id: string) {
    if (!/^[a-zA-Z0-9-]+$/.test(id)) throw new Error("非法运行 ID");
    return join(this.root, "runs", id);
  }
  runs(): Run[] {
    return readdirSync(join(this.root, "runs"))
      .filter((x) => existsSync(join(this.runDir(x), "run.json")))
      .map((x) => this.get(x));
  }
  get(id: string): Run {
    return JSON.parse(readFileSync(join(this.runDir(id), "run.json"), "utf8"));
  }
  begin(s: Session, model: string, versions: Json): Run {
    const r: Run = {
      id: randomUUID(),
      sessionId: s.id,
      engine: s.engine,
      model,
      startedAt: new Date().toISOString(),
      state: "running",
      outcome: "unverified",
      versions,
      metrics: {},
    };
    mkdirSync(join(this.runDir(r.id), "artifacts"), { recursive: true });
    this.save(r);
    return r;
  }
  save(r: Run) {
    atomic(join(this.runDir(r.id), "run.json"), this.scrub(r));
  }
  read(id: string, after = 0): RunEvent[] {
    const p = join(this.runDir(id), "events.jsonl");
    if (!existsSync(p)) return [];
    return readFileSync(p, "utf8")
      .split("\n")
      .filter(Boolean)
      .map((s, i, a) => {
        try {
          return JSON.parse(s);
        } catch (e) {
          if (i === a.length - 1) return null;
          throw e;
        }
      })
      .filter((x): x is RunEvent => x !== null && x.seq > after);
  }
  emit(id: string, type: string, data: Json = {}) {
    const e: RunEvent = {
      seq: (this.sequence.get(id) ?? this.read(id).at(-1)?.seq ?? 0) + 1,
      at: new Date().toISOString(),
      runId: id,
      type,
      data: this.scrub(data),
    };
    this.sequence.set(id, e.seq);
    const path = join(this.runDir(id), "events.jsonl");
    if (existsSync(path)) {
      const raw = readFileSync(path, "utf8");
      if (raw && !raw.endsWith("\n")) {
        const valid = this.read(id);
        writeFileSync(
          path,
          valid.map((x) => JSON.stringify(x) + "\n").join(""),
        );
      }
    }
    appendFileSync(path, JSON.stringify(e) + "\n");
    this.events.emit(id, e);
  }
  private sequence = new Map<string, number>();
  finish(r: Run, state: Run["state"], error?: string) {
    r.state = state;
    r.endedAt = new Date().toISOString();
    if (error) r.error = error;
    r.metrics = summarize(this.read(r.id));
    r.metrics.wallMs = Date.parse(r.endedAt) - Date.parse(r.startedAt);
    r.metrics.nonHumanWallMs = Math.max(
      0,
      r.metrics.wallMs - r.metrics.humanWaitMs,
    );
    this.save(r);
    this.emit(r.id, "run.end", {
      state,
      outcome: r.outcome,
      metrics: r.metrics,
      error,
    });
  }
}
export function summarize(events: RunEvent[]) {
  const requests = new Map<string, { usage: Usage; purpose: string }>();
  const phases: Json = {};
  const waits = new Map<string, number>();
  const intervals: [number, number][] = [];
  for (const e of events) {
    if (e.type === "model.start" && !requests.has(e.data.requestId))
      requests.set(e.data.requestId, {
        usage: {
          input: null,
          output: null,
          cacheRead: null,
          cacheWrite: null,
          raw: {},
        },
        purpose: e.data.purpose ?? "unknown",
      });
    if (e.type === "usage")
      requests.set(e.data.requestId, {
        usage: e.data.usage,
        purpose: e.data.purpose ?? "unknown",
      });
    if (e.type.endsWith(".end") && typeof e.data.durationMs === "number")
      phases[e.type] = (phases[e.type] ?? 0) + e.data.durationMs;
    if (e.type === "interaction") waits.set(e.data.id, Date.parse(e.at));
    if (
      e.type === "interaction.answered" ||
      e.type === "interaction.cancelled"
    ) {
      const start = waits.get(e.data.id);
      if (start !== undefined) {
        intervals.push([start, Date.parse(e.at)]);
        waits.delete(e.data.id);
      }
    }
  }
  // Concurrent questions may overlap: count the union of their waits.
  for (const start of waits.values())
    intervals.push([
      start,
      Date.parse(events.at(-1)?.at ?? new Date().toISOString()),
    ]);
  intervals.sort((a, b) => a[0] - b[0]);
  let humanWaitMs = 0,
    end = 0;
  for (const [a, b] of intervals) {
    humanWaitMs += Math.max(0, b - Math.max(a, end));
    end = Math.max(end, b);
  }
  const totals = (entries: { usage: Usage }[]) => {
    const sum = (key: "input" | "output" | "cacheRead" | "cacheWrite") => {
      const vals = entries.map((x) => x.usage[key]);
      return vals.length &&
        vals.every((x) => typeof x === "number" && Number.isFinite(x))
        ? (vals as number[]).reduce((a, b) => a + b, 0)
        : null;
    };
    return {
      requests: entries.length,
      inputTokens: sum("input"),
      outputTokens: sum("output"),
      cacheReadTokens: sum("cacheRead"),
      cacheWriteTokens: sum("cacheWrite"),
    };
  };
  const values = [...requests.values()];
  const byPurpose: Json = {};
  for (const purpose of new Set(values.map((x) => x.purpose)))
    byPurpose[purpose] = totals(values.filter((x) => x.purpose === purpose));
  return {
    ...totals(values),
    byPurpose,
    humanWaitMs,
    phaseTotalsMs: phases,
    toolTimeIsWallTime: false,
  };
}
