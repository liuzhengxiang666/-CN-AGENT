import {
  realpath,
  stat,
  readFile,
  writeFile,
  mkdir,
  readdir,
} from "node:fs/promises";
import { resolve, relative, dirname, isAbsolute, join } from "node:path";
import { spawn } from "node:child_process";

import type { EngineContext, Json, ToolCall } from "./types.js";
export async function confined(root: string, path: string) {
  const base = await realpath(root),
    target = resolve(base, path);
  const inside = (p: string) => {
    const r = relative(base, p);
    if (
      r === ".." ||
      r.startsWith(".." + (process.platform === "win32" ? "\\" : "/")) ||
      isAbsolute(r)
    )
      throw new Error("路径越过工作区");
  };
  inside(target);
  let parent = target;
  while (true) {
    try {
      inside(await realpath(parent));
      break;
    } catch (e: any) {
      if (e.code !== "ENOENT") throw e;
      const up = dirname(parent);
      if (up === parent) throw e;
      parent = up;
    }
  }
  return target;
}
export function command(
  command: string,
  args: string[],
  cwd: string,
  signal?: AbortSignal,
  timeout = 120000,
): Promise<{ code: number; output: string }> {
  signal?.throwIfAborted();
  return new Promise((resolvePromise, reject) => {
    const p = spawn(command, args, {
      cwd,
      windowsHide: true,
      detached: process.platform !== "win32",
      env: { ...process.env },
    });
    let output = "",
      failure: Error | undefined,
      killing: Promise<void> | undefined;
    const kill = () =>
      (killing ??= new Promise<void>((done) => {
        if (!p.pid) {
          done();
          return;
        }
        if (process.platform === "win32") {
          const killer = spawn(
            "taskkill",
            ["/pid", String(p.pid), "/T", "/F"],
            { windowsHide: true },
          );
          killer.once("close", () => done());
          killer.once("error", () => {
            p.kill();
            done();
          });
        } else {
          try {
            process.kill(-p.pid, "SIGKILL");
          } catch {}
          done();
        }
      }));
    const cancel = () => {
      failure = new Error("命令已取消");
      void kill();
    };
    signal?.addEventListener("abort", cancel, { once: true });
    if (signal?.aborted) cancel();
    const timer = setTimeout(() => {
      failure = new Error("命令超时");
      void kill();
    }, timeout);
    const add = (v: Buffer) => {
      if (output.length < 64000)
        output += v.toString().slice(0, 64000 - output.length);
    };
    p.stdout.on("data", add);
    p.stderr.on("data", add);
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", cancel);
    };
    p.once("error", (e) => {
      cleanup();
      reject(e);
    });
    p.once("close", async (code) => {
      cleanup();
      await killing;
      failure ? reject(failure) : resolvePromise({ code: code ?? -1, output });
    });
  });
}

export interface Tool {
  schema: Json;
  readonly: boolean;
  run: (a: Json, c: EngineContext) => Promise<unknown>;
}
const schema = (
  name: string,
  description: string,
  properties: Json,
  required: string[] = [],
) => ({
  type: "function",
  function: {
    name,
    description,
    parameters: {
      type: "object",
      properties,
      required,
      additionalProperties: false,
    },
  },
});
const str = { type: "string" };
export function builtins(): Map<string, Tool> {
  return new Map<string, Tool>([
    [
      "read_file",
      {
        readonly: true,
        schema: schema("read_file", "读取工作区内文件", { path: str }, [
          "path",
        ]),
        run: async (a, c) =>
          (await readFile(await confined(c.session.cwd, a.path), "utf8")).slice(
            0,
            40000,
          ),
      },
    ],
    [
      "list_files",
      {
        readonly: true,
        schema: schema("list_files", "列出工作区目录", { path: str }),
        run: async (a, c) =>
          readdir(await confined(c.session.cwd, a.path ?? ".")),
      },
    ],
    [
      "search",
      {
        readonly: true,
        schema: schema(
          "search",
          "在工作区用 rg 检索文本，pattern 是正则表达式",
          { pattern: str, path: str },
          ["pattern"],
        ),
        run: async (a, c) =>
          command(
            "rg",
            [
              "-n",
              "--max-count",
              "30",
              "--",
              a.pattern,
              await confined(c.session.cwd, a.path ?? "."),
            ],
            c.session.cwd,
            c.signal,
          ),
      },
    ],
    [
      "write_file",
      {
        readonly: false,
        schema: schema(
          "write_file",
          "创建或覆盖工作区文件",
          { path: str, content: str },
          ["path", "content"],
        ),
        run: async (a, c) => {
          const p = await confined(c.session.cwd, a.path);
          await mkdir(dirname(p), { recursive: true });
          await writeFile(p, a.content);
          return "written";
        },
      },
    ],
    [
      "edit_file",
      {
        readonly: false,
        schema: schema(
          "edit_file",
          "唯一匹配文本替换，避免意外修改其他位置",
          { path: str, old_text: str, new_text: str },
          ["path", "old_text", "new_text"],
        ),
        run: async (a, c) => {
          const p = await confined(c.session.cwd, a.path),
            text = await readFile(p, "utf8");
          if (!a.old_text || text.split(a.old_text).length !== 2)
            throw new Error("old_text 必须唯一匹配");
          await writeFile(p, text.replace(a.old_text, a.new_text));
          return "edited";
        },
      },
    ],
    [
      "shell",
      {
        readonly: false,
        schema: schema(
          "shell",
          "在工作区执行命令；任意 shell 命令都需要用户批准",
          { command: str },
          ["command"],
        ),
        run: async (a, c) =>
          command(
            process.platform === "win32" ? "powershell.exe" : "/bin/sh",
            process.platform === "win32"
              ? ["-NoProfile", "-NonInteractive", "-Command", a.command]
              : ["-lc", a.command],
            c.session.cwd,
            c.signal,
          ),
      },
    ],
    [
      "ask_user_question",
      {
        readonly: true,
        schema: schema(
          "ask_user_question",
          "向用户澄清具体问题",
          { question: str, options: { type: "array", items: str } },
          ["question"],
        ),
        run: async (a, c) =>
          c.interact("question", {
            question: a.question,
            options: a.options ?? [],
          }),
      },
    ],
  ]);
}
export async function executeTool(
  call: ToolCall,
  tools: Map<string, Tool>,
  ctx: EngineContext,
) {
  const tool = tools.get(call.function.name);
  let start: number | undefined;
  let error: string | undefined;
  try {
    if (!tool) throw new Error("未知工具 " + call.function.name);
    const args = JSON.parse(call.function.arguments || "{}");
    validateArguments(args, tool.schema.function.parameters);
    if (
      ctx.session.allowedTools &&
      !ctx.session.allowedTools.includes(call.function.name) &&
      call.function.name !== "load_skill"
    )
      throw new Error("当前 Skill 不允许该工具");
    if (!tool.readonly && process.env.CNCODE_ALLOW_TOOLS !== "1") {
      const answer = await ctx.interact("approval", {
        callId: call.id,
        tool: call.function.name,
        args,
      });
      if (answer.allow !== true) throw new Error("用户拒绝工具执行");
    }
    ctx.signal.throwIfAborted();
    start = performance.now();
    ctx.emit("tool.start", { callId: call.id, name: call.function.name, args });
    const result = await tool.run(args, ctx);
    if (
      result &&
      typeof result === "object" &&
      "code" in result &&
      result.code !== 0
    )
      error = "command_exit_" + result.code;
    return JSON.stringify(result) ?? "null";
  } catch (e: any) {
    error = e.message;
    return JSON.stringify({ error });
  } finally {
    ctx.emit("tool.end", {
      callId: call.id,
      name: call.function.name,
      executed: start !== undefined,
      durationMs: start === undefined ? null : performance.now() - start,
      success: !error,
      error,
    });
  }
}

// Enforce the builtin/MCP object contracts before approval or side effects.
export function validateArguments(
  value: unknown,
  schema: Json,
  path = "arguments",
): void {
  if (!schema) return;
  if (schema.type === "object") {
    if (!value || typeof value !== "object" || Array.isArray(value))
      throw new Error(path + " 必须是对象");
    const v = value as Json;
    for (const key of schema.required ?? [])
      if (!Object.hasOwn(v, key)) throw new Error(path + "." + key + " 缺失");
    for (const [key, item] of Object.entries(v)) {
      const rule = schema.properties?.[key];
      if (rule) validateArguments(item, rule, path + "." + key);
      else if (schema.additionalProperties === false)
        throw new Error(path + "." + key + " 未定义");
    }
  } else if (schema.type === "array") {
    if (!Array.isArray(value)) throw new Error(path + " 必须是数组");
    for (const item of value)
      validateArguments(item, schema.items ?? {}, path + "[]");
  } else if (
    schema.type === "integer"
      ? typeof value !== "number" || !Number.isInteger(value)
      : schema.type &&
        !(Array.isArray(schema.type)
          ? schema.type.includes(value === null ? "null" : typeof value)
          : schema.type === (value === null ? "null" : typeof value))
  )
    throw new Error(path + " 类型错误");
  if (schema.enum && !schema.enum.includes(value))
    throw new Error(path + " 超出允许值");
}
