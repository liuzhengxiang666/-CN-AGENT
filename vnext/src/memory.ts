import { OpenVikingClient, isOpenVikingError } from "@openviking/sdk";
import { fingerprint } from "./context.js";
import type { AppConfig, EngineContext, Session } from "./types.js";
export class Memory {
  constructor(private config: AppConfig) {}
  peer(s: Session) {
    return (
      "cncode-" +
      fingerprint(
        process.platform === "win32"
          ? s.cwd.replaceAll("\\", "/").toLowerCase()
          : s.cwd,
      ).slice(0, 24)
    );
  }
  client(s: Session) {
    if (!this.config.memoryUrl)
      throw new Error("OpenViking 未配置；设置 OPENVIKING_URL");
    return new OpenVikingClient({
      baseUrl: this.config.memoryUrl,
      apiKey: this.config.memoryKey,
      actorPeerId: this.peer(s),
      timeout: 15000,
    });
  }
  async recall(query: string, c: EngineContext) {
    const start = performance.now();
    try {
      const result = await this.client(c.session).search(query, {
        targetUri: this.scope(c.session),
        limit: 5,
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
    const client = this.client(c.session),
      id = "cncode-" + c.session.id;
    try {
      await client.getSession(id);
    } catch (e: any) {
      if (!isOpenVikingError(e, "NOT_FOUND") && e.statusCode !== 404) throw e;
      await client.createSession({ sessionId: id });
    }
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
  async commit(s: Session) {
    if (!s.memory) throw new Error("该会话未启用记忆");
    const start = performance.now();
    const result = await this.client(s).commitSession("cncode-" + s.id);
    return {
      state: "submitted",
      result,
      durationMs: performance.now() - start,
      usage: null,
    };
  }
  async task(s: Session, id: string) {
    return this.client(s).getTask(id);
  }
  async list(s: Session) {
    return this.client(s).list(this.scope(s), { recursive: true });
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
    if (
      !uri.startsWith(this.scope(s) + "/") &&
      !new RegExp("^viking://user/[^/]+" + suffix).test(uri)
    )
      throw new Error("仅允许管理当前项目记忆 URI");
  }
}
