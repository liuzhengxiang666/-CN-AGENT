import { OpenVikingClient, isOpenVikingError } from "@openviking/sdk";
import { fingerprint } from "./context.js";
import type { AppConfig, EngineContext, Session } from "./types.js";
import { existsSync, readFileSync, realpathSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { atomic } from "./records.js";
import { usage } from "./provider.js";
export class Memory {
  private provisioning = new Map<string, Promise<void>>();
  private observed = new WeakMap<EngineContext, Set<string>>();
  constructor(private config: AppConfig) {}
  private projects(): Record<
    string,
    { account: string; user: string; apiKey: string }
  > {
    const file = this.config.memoryProjectsFile;
    return file && existsSync(file)
      ? JSON.parse(readFileSync(file, "utf8"))
      : {};
  }
  secrets() {
    return [
      this.config.memoryAdminKey ?? "",
      ...Object.values(this.projects()).map((p) => p.apiKey),
    ];
  }
  async prepare(s: Session) {
    if (!this.config.memoryProjectsFile) return;
    const peer = this.peer(s);
    if (this.projects()[peer]) return;
    if (!this.config.memoryAdminKey)
      throw new Error("项目记忆凭据不存在；需要本机管理密钥完成首次创建");
    const existing = this.provisioning.get(peer);
    if (existing) return existing;
    const done = (async () => {
      const admin = new OpenVikingClient({
        baseUrl: this.config.memoryUrl!,
        apiKey: this.config.memoryAdminKey,
        timeout: 30000,
      });
      const result = await admin.adminCreateAccount(peer, "cncode");
      if (typeof result.user_key !== "string")
        throw new Error("OpenViking 未返回项目密钥");
      const projects = this.projects();
      projects[peer] = {
        account: peer,
        user: "cncode",
        apiKey: result.user_key,
      };
      mkdirSync(dirname(this.config.memoryProjectsFile!), { recursive: true });
      atomic(this.config.memoryProjectsFile!, projects);
    })();
    this.provisioning.set(peer, done);
    try {
      await done;
    } finally {
      this.provisioning.delete(peer);
    }
  }
  identity(s: Session) {
    const project = this.projects()[this.peer(s)];
    if (this.config.memoryProjectsFile && !project)
      throw new Error("当前项目尚未创建记忆凭据");
    return project ?? { apiKey: this.config.memoryKey };
  }
  observations() {
    const file = this.config.memoryObservationsFile;
    if (!file || !existsSync(file)) return [] as any[];
    const text = readFileSync(file, "utf8");
    return text
      .slice(0, text.lastIndexOf("\n") + 1)
      .split("\n")
      .filter(Boolean)
      .map((line) => JSON.parse(line));
  }
  recordObservations(
    c: EngineContext,
    matches: (e: any) => boolean,
    purpose: string,
  ) {
    let seen = this.observed.get(c);
    if (!seen) this.observed.set(c, (seen = new Set()));
    const observations = this.observations();
    const matched = observations.filter(matches);
    const telemetryIds = new Set(matched.map((e) => e.telemetryId));
    for (const e of observations.filter(
      (e) =>
        matches(e) ||
        (e.type === "embedding.end" && telemetryIds.has(e.telemetryId)),
    )) {
      const key = e.requestId ? e.type + "/" + e.requestId : JSON.stringify(e);
      if (seen.has(key)) continue;
      seen.add(key);
      if (e.type === "model.start")
        c.emit("model.start", {
          requestId: e.requestId,
          purpose,
          executedAt: e.at,
        });
      else if (e.type === "model.response" || e.type === "model.error") {
        c.emit("usage", {
          requestId: e.requestId,
          purpose,
          usage: usage(e.raw ?? {}),
          source: "openviking-provider-response",
          taskId: e.taskId,
          executedAt: e.at,
        });
        c.emit("model.end", {
          requestId: e.requestId,
          purpose,
          durationMs: e.durationMs,
          executedAt: e.at,
        });
      } else if (e.type === "embedding.end") c.emit("memory.embedding.end", e);
    }
  }
  peer(s: Session) {
    const cwd = realpathSync(s.cwd);
    return (
      "cncode-" +
      fingerprint(
        process.platform === "win32"
          ? cwd.replaceAll("\\", "/").toLowerCase()
          : cwd,
      ).slice(0, 24)
    );
  }
  client(s: Session, c?: EngineContext, purpose = "memory_recall") {
    if (!this.config.memoryUrl)
      throw new Error("OpenViking 未配置；设置 OPENVIKING_URL");
    return new OpenVikingClient({
      baseUrl: this.config.memoryUrl,
      ...this.identity(s),
      actorPeerId: this.peer(s),
      timeout: 120000,
      fetch: async (url, init) => {
        const start = performance.now();
        const response = await fetch(url, {
          ...init,
          signal: c
            ? AbortSignal.any([
                c.signal,
                init?.signal ?? AbortSignal.timeout(120000),
              ])
            : init?.signal,
        });
        const envelope = (await response
          .clone()
          .json()
          .catch(() => ({}))) as any;
        c?.emit("memory.operation", {
          purpose,
          path: new URL(String(url)).pathname,
          durationMs: performance.now() - start,
          status: response.status,
          telemetry: envelope.telemetry ?? null,
        });
        if (c && envelope.telemetry?.id)
          this.recordObservations(
            c,
            (e) => e.telemetryId === envelope.telemetry.id,
            purpose,
          );
        return response;
      },
    });
  }
  async recall(query: string, c: EngineContext) {
    const start = performance.now();
    try {
      const result = await this.client(c.session, c).search(query, {
        targetUri: this.scope(c.session),
        limit: 5,
        telemetry: true,
        contextType: "memory",
        level: [2],
      });
      const text = JSON.stringify(result).slice(0, 6000);
      c.emit("memory.recall", {
        peer: this.peer(c.session),
        query,
        result: text,
        budgetCharacters: 6000,
        usage: null,
      });
      return text;
    } finally {
      c.emit("memory.recall.end", { durationMs: performance.now() - start });
    }
  }
  async capture(input: string, output: string, c: EngineContext) {
    const client = this.client(c.session, c, "memory_capture"),
      id = this.sessionId(c.session);
    await this.ensureSession(client, id);
    await client.addMessage(id, {
      role: "user",
      content: input,
      peerId: this.peer(c.session),
    });
    await client.addMessage(id, {
      role: "assistant",
      content: output,
      peerId: this.peer(c.session),
    });
    c.emit("memory.captured", { sessionId: id, committed: false });
  }
  private async ensureSession(client: OpenVikingClient, id: string) {
    try {
      await client.getSession(id);
    } catch (e: any) {
      if (!isOpenVikingError(e, "NOT_FOUND") && e.statusCode !== 404) throw e;
      await client.createSession({ sessionId: id });
    }
  }
  sessionId(s: Session) {
    return s.engine === "dsh"
      ? "dsh-" + (s.dshSessionId ?? s.id)
      : "cncode-" + s.id;
  }
  async commit(s: Session, c?: EngineContext) {
    if (!s.memory) throw new Error("该会话未启用记忆");
    const start = performance.now();
    const client = this.client(s, c, "memory_extraction");
    await this.ensureSession(client, this.sessionId(s));
    const result = await client.commitSession(this.sessionId(s), 0, true);
    c?.emit("memory.commit.submitted", { result, extractionComplete: false });
    return {
      state: "submitted",
      result,
      durationMs: performance.now() - start,
      usage: null,
    };
  }
  async waitForTask(s: Session, id: string, c: EngineContext) {
    const started = performance.now();
    const signal = AbortSignal.any([c.signal, AbortSignal.timeout(300000)]);
    try {
      for (;;) {
        signal.throwIfAborted();
        const task = (await this.client(
          s,
          { ...c, signal },
          "memory_extraction",
        ).getTask(id)) as any;
        this.recordObservations(c, (e) => e.taskId === id, "memory_extraction");
        if (!task) throw new Error("记忆提取任务不存在");
        if (["failed", "cancelled"].includes(task.status)) {
          c.emit("memory.extraction.failed", { task });
          throw new Error("记忆提取失败：" + JSON.stringify(task.error));
        }
        if (task.status === "completed") {
          const queues = task.result?.queue_status;
          if (
            queues &&
            Object.values(queues).some((q: any) => q.error_count > 0)
          )
            throw new Error("记忆提取完成但索引队列存在错误");
          c.emit("memory.extraction.completed", {
            task,
            extractionComplete: true,
            usageSource:
              "provider observations; task token_usage is cumulative server metadata",
          });
          c.emit("memory.extraction.end", {
            durationMs: performance.now() - started,
          });
          return task;
        }
        await new Promise<void>((resolve, reject) => {
          const abort = () => {
            clearTimeout(timer);
            reject(signal.reason);
          };
          const timer = setTimeout(() => {
            signal.removeEventListener("abort", abort);
            resolve();
          }, 500);
          signal.addEventListener("abort", abort, { once: true });
        });
      }
    } catch (error) {
      if (signal.aborted) {
        const response = await fetch(
          this.config.memoryUrl +
            "/api/v1/tasks/" +
            encodeURIComponent(id) +
            "/cancel",
          {
            method: "POST",
            headers: { "X-API-Key": this.identity(s).apiKey ?? "" },
            signal: AbortSignal.timeout(10000),
          },
        ).catch(() => undefined);
        c.emit("memory.cancel.requested", {
          taskId: id,
          accepted: response?.ok ?? false,
        });
        const deadline = Date.now() + 10000;
        while (Date.now() < deadline) {
          const task = (await this.client(
            s,
            { ...c, signal: AbortSignal.timeout(1000) },
            "memory_extraction",
          )
            .getTask(id)
            .catch(() => null)) as any;
          this.recordObservations(
            c,
            (e) => e.taskId === id,
            "memory_extraction",
          );
          if (
            task &&
            ["completed", "failed", "cancelled"].includes(task.status)
          ) {
            c.emit("memory.cancel.settled", {
              taskId: id,
              status: task.status,
            });
            throw error;
          }
          await new Promise((r) => setTimeout(r, 250));
        }
        c.emit("memory.cancel.pending", {
          taskId: id,
          message: "服务端任务尚未确认结束，后续消耗仍可能产生",
        });
      }
      throw error;
    }
  }
  async task(s: Session, id: string) {
    return this.client(s).getTask(id);
  }
  async list(s: Session) {
    const listed = (await this.client(s).list(this.scope(s), {
      recursive: true,
    })) as any[];
    return this.config.memoryProjectsFile
      ? listed.filter((e) => /\/memories(?:\/|$)/.test(e.uri ?? ""))
      : listed;
  }
  async read(s: Session, uri: string) {
    this.check(s, uri);
    return this.client(s).read(uri);
  }
  async remove(s: Session, uri: string) {
    this.check(s, uri);
    return this.client(s).remove(uri, { recursive: false });
  }
  scope(s: Session) {
    // The server may write user-level memories. Each local project owns a whole
    // authenticated account, so recalling that account is both complete and isolated.
    if (this.config.memoryProjectsFile) return "viking://~";
    return "viking://~/peers/" + this.peer(s) + "/memories";
  }
  private check(s: Session, uri: string) {
    if (
      typeof uri !== "string" ||
      uri.includes("..") ||
      uri.includes("%") ||
      uri.includes("\\")
    )
      throw new Error("非法记忆 URI");
    const suffix = "/peers/" + this.peer(s) + "/memories/";
    if (this.config.memoryProjectsFile) {
      if (
        !/^viking:\/\/(?:~|user\/cncode)\/(?:memories\/|peers\/[^/]+\/memories\/)/.test(
          uri,
        )
      )
        throw new Error("仅允许管理当前项目账户的记忆 URI");
      return;
    }
    if (
      !uri.startsWith(this.scope(s) + "/") &&
      !new RegExp("^viking://user/[^/]+" + suffix).test(uri)
    )
      throw new Error("仅允许管理当前项目记忆 URI");
  }
}
