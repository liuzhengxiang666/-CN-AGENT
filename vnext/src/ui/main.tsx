import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Button, Textarea } from "./primitives.js";
import { sse } from "../stream.js";
import type {
  Session,
  Run,
  RunEvent,
  Interaction,
  Json,
  EngineId,
} from "../types.js";
import "./style.css";
const initial = new URLSearchParams(location.hash.slice(1)).get("token");
if (initial) {
  sessionStorage.setItem("cncode-token", initial);
  history.replaceState(null, "", location.pathname + location.search);
}
const stateNames: Json = {
  running: "运行中",
  completed: "已结束",
  failed: "失败",
  cancelled: "已停止",
  interrupted: "已中断",
};
const outcomeNames: Json = {
  unverified: "尚未验收",
  pass: "通过",
  fail: "未通过",
  infra_error: "环境错误",
};
const duration = (n: unknown) =>
  typeof n === "number"
    ? n < 1000
      ? Math.round(n) + " ms"
      : (n / 1000).toFixed(1) + " s"
    : "未知";
const count = (n: unknown) =>
  typeof n === "number" ? n.toLocaleString() : "未知";
function App() {
  const [token, setToken] = useState(
    sessionStorage.getItem("cncode-token") ?? "",
  );
  const [draftToken, setDraftToken] = useState("");
  const [theme, setTheme] = useState(
    localStorage.getItem("cncode-theme") ?? "dark",
  );
  const [config, setConfig] = useState<Json>({});
  const [sessions, setSessions] = useState<Session[]>([]),
    [session, setSession] = useState<Session>(),
    [runs, setRuns] = useState<Run[]>([]),
    [run, setRun] = useState<Run>();
  const [events, setEvents] = useState<RunEvent[]>([]),
    [interactions, setInteractions] = useState<Interaction[]>([]),
    [text, setText] = useState(""),
    [error, setError] = useState("");
  const [creating, setCreating] = useState(false),
    [engine, setEngine] = useState<EngineId>("cncode"),
    [cwd, setCwd] = useState(""),
    [memory, setMemory] = useState(false),
    [busy, setBusy] = useState(false),
    [memoryResult, setMemoryResult] = useState("");
  const pendingInput = useRef<{ text: string; id: string } | undefined>(
    undefined,
  );
  const eventLog = useRef<RunEvent[]>([]);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("cncode-theme", theme);
  }, [theme]);
  async function api(
    path: string,
    body?: unknown,
    method = body === undefined ? "GET" : "POST",
  ) {
    const res = await fetch("/api/v1/" + path, {
      method,
      headers: {
        authorization: "Bearer " + token,
        "content-type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error ?? "请求失败");
    return data;
  }
  async function refresh() {
    const [ss, rr, ii] = await Promise.all([
      api("sessions"),
      api("runs"),
      api("interactions"),
    ]);
    setSessions(ss);
    setRuns(rr);
    setInteractions(ii);
    return { ss: ss as Session[], rr: rr as Run[] };
  }
  useEffect(() => {
    if (!token) return;
    let live = true;
    void api("config")
      .then((c) => {
        if (live) {
          setConfig(c);
          setCwd(c.workspace);
        }
      })
      .catch((e) => setError(e.message));
    void refresh().catch((e) => setError(e.message));
    return () => {
      live = false;
    };
  }, [token]);
  async function select(s: Session) {
    setSession(s);
    setError("");
    setMemoryResult("");
    pendingInput.current = undefined;
    const data = await refresh();
    const latest = data.rr.filter((r) => r.sessionId === s.id).at(-1);
    setRun(latest);
    setEvents([]);
  }
  useEffect(() => {
    if (!run) return;
    const controller = new AbortController();
    const id = run.id;
    let after = 0;
    eventLog.current = [];
    setEvents([]);
    const append = (e: RunEvent) => {
      if (e.seq <= after) return;
      after = e.seq;
      eventLog.current.push(e);
      setEvents([...eventLog.current]);
      if (e.type === "interaction")
        setInteractions((prev) => [
          ...prev.filter((i) => i.id !== e.data.id),
          e.data as Interaction,
        ]);
      if (
        e.type === "interaction.answered" ||
        e.type === "interaction.cancelled"
      )
        setInteractions((prev) => prev.filter((i) => i.id !== e.data.id));
    };
    void (async () => {
      while (!controller.signal.aborted) {
        try {
          const res = await fetch(`/api/v1/runs/${id}/events?after=${after}`, {
            headers: { authorization: "Bearer " + token },
            signal: controller.signal,
          });
          if (!res.ok) throw new Error("运行事件连接失败");
          for await (const e of sse(res.body!)) {
            append(e as RunEvent);
            if (e.type === "run.end") {
              const info = await api("runs/" + id);
              setRun(info.run);
              await refresh();
              return;
            }
          }
        } catch (e: any) {
          if (controller.signal.aborted) return;
          setError(e.message + "；正在重连");
        }
        await new Promise((r) => setTimeout(r, 1000));
      }
    })();
    return () => controller.abort();
  }, [run?.id, token]);
  async function create() {
    setBusy(true);
    setError("");
    try {
      const s = await api("sessions", { engine, cwd, memory });
      setCreating(false);
      await select(s);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function send() {
    if (!session || !text.trim() || busy) return;
    setBusy(true);
    setError("");
    try {
      const request =
        pendingInput.current?.text === text
          ? pendingInput.current
          : { text, id: crypto.randomUUID() };
      pendingInput.current = request;
      const { runId } = await api(`sessions/${session.id}/inputs`, {
        inputId: request.id,
        text,
      });
      const info = await api("runs/" + runId);
      setRun(info.run);
      setText("");
      pendingInput.current = undefined;
      await refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function answer(id: string, response: Json) {
    try {
      await api("interactions/" + id, response);
      setInteractions((prev) => prev.filter((i) => i.id !== id));
    } catch (e: any) {
      setError(e.message);
    }
  }
  async function stop() {
    if (!session) return;
    setBusy(true);
    try {
      await api(`sessions/${session.id}/stop`, {});
      await refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function memoryAction(action: string) {
    if (!session) return;
    try {
      const value = await api(
        `sessions/${session.id}/memory`,
        action === "commit" ? {} : undefined,
      );
      setMemoryResult(JSON.stringify(value, null, 2));
    } catch (e: any) {
      setError(e.message);
    }
  }
  const currentInteractions = interactions.filter((i) => i.runId === run?.id);
  const running = run?.state === "running";
  const metric = run?.metrics ?? {};
  const input = events.find((e) => e.type === "run.start")?.data.input;
  const final = events.filter((e) => e.type === "assistant.message").at(-1)
    ?.data.text;
  const partial = events
    .filter(
      (e) =>
        e.type === "assistant.delta" &&
        (e.data.purpose ?? "conversation") === "conversation",
    )
    .map((e) => e.data.text)
    .join("");
  const trace = events.filter((e) =>
    [
      "model.start",
      "model.end",
      "tool.start",
      "tool.end",
      "preparation.end",
      "startup.end",
      "memory.recall",
      "memory.error",
      "artifact.saved",
      "cleanup.error",
    ].includes(e.type),
  );
  const artifacts = events.filter((e) => e.type === "artifact.saved");
  if (!token)
    return (
      <main className="login">
        <div className="wordmark">CN / CODE</div>
        <h1>连接本地工作台</h1>
        <p>从服务启动输出中复制访问令牌。令牌只保存在当前窗口会话。</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            sessionStorage.setItem("cncode-token", draftToken);
            setToken(draftToken);
          }}
        >
          <label>
            访问令牌
            <input
              type="password"
              required
              value={draftToken}
              onChange={(e) => setDraftToken(e.target.value)}
            />
          </label>
          <Button variant="primary" type="submit">
            连接
          </Button>
        </form>
      </main>
    );
  return (
    <div className="workbench">
      <aside className="sidebar">
        <div className="wordmark">CN / CODE</div>
        <p className="muted tagline">个人编程工作台</p>
        <Button
          variant="primary"
          onPress={() => {
            setCreating(true);
            setError("");
          }}
        >
          ＋ 新建会话
        </Button>
        <div className="section-label">会话</div>
        <nav aria-label="会话列表">
          {sessions.length === 0 ? (
            <p className="muted">还没有会话</p>
          ) : (
            sessions.map((s) => (
              <button
                key={s.id}
                className={
                  "session-item " + (session?.id === s.id ? "selected" : "")
                }
                onClick={() => void select(s).catch((e) => setError(e.message))}
              >
                <strong>
                  {s.messages
                    ?.find((m) => m.role === "user")
                    ?.content?.slice(0, 30) ??
                    s.cwd.split(/[\\/]/).filter(Boolean).at(-1) ??
                    "新会话"}
                </strong>
                <small>
                  {s.engine === "cncode" ? "CN Code" : "DSH"} ·{" "}
                  {s.memory ? "记忆开启" : "记忆关闭"}
                  {s.activeRun ? " · 运行中" : ""}
                </small>
              </button>
            ))
          )}
        </nav>
        <footer>
          <div className="theme-switch" aria-label="主题">
            <button
              aria-pressed={theme === "dark"}
              onClick={() => setTheme("dark")}
            >
              深色
            </button>
            <button
              aria-pressed={theme === "light"}
              onClick={() => setTheme("light")}
            >
              浅色
            </button>
          </div>
          <small className="muted">本地数据 · vNext 0.2</small>
        </footer>
      </aside>
      <main className="workspace">
        <header className="toolbar">
          <span
            className="workspace-path"
            title={session?.cwd ?? config.workspace}
          >
            {session?.cwd.split(/[\\/]/).filter(Boolean).at(-1) ?? "CN Code"}{" "}
            <span className="muted">/ {session ? "编码会话" : "工作台"}</span>
          </span>
          <label className="engine-picker">
            <span className="sr-only">内核</span>
            <select
              value={session?.engine ?? engine}
              onChange={(e) => {
                setEngine(e.target.value as EngineId);
                setMemory(false);
                setCreating(true);
              }}
            >
              <option value="cncode">CN Code</option>
              <option value="dsh">DSH</option>
            </select>
          </label>
          <span className="model muted">{config.model || "模型未配置"}</span>
        </header>
        <div className="workspace-body">
          <section className="conversation">
            <div className="conversation-scroll">
              <h1>实现、检查，再继续。</h1>
              <p className="muted workspace-caption">
                {session ? session.cwd : "选择一个会话，或从新任务开始。"}
              </p>
              {error && (
                <div className="notice" role="alert">
                  {error}
                  <Button onPress={() => setError("")} aria-label="关闭错误">
                    ×
                  </Button>
                </div>
              )}
              {!session && (
                <div className="empty">
                  <p>两种内核，同一个工作台。</p>
                  <p className="muted">
                    保留每次请求、工具执行与等待的记录，检查实际交付结果。
                  </p>
                  <Button onPress={() => setCreating(true)}>
                    开始新会话 ↗
                  </Button>
                </div>
              )}
              {session &&
                runs.filter((r) => r.sessionId === session.id).length > 0 && (
                  <label className="run-picker">
                    运行{" "}
                    <select
                      value={run?.id ?? ""}
                      onChange={(e) =>
                        setRun(runs.find((r) => r.id === e.target.value))
                      }
                    >
                      {runs
                        .filter((r) => r.sessionId === session.id)
                        .map((r, i) => (
                          <option key={r.id} value={r.id}>
                            #{i + 1} · {stateNames[r.state]} ·{" "}
                            {new Date(r.startedAt).toLocaleTimeString()}
                          </option>
                        ))}
                    </select>
                  </label>
                )}
              {input && (
                <article className="user-message">
                  <span className="section-label">你</span>
                  <pre>{input}</pre>
                </article>
              )}
              {(final || partial) && (
                <article className="assistant-message">
                  <span className="section-label mono">
                    {session?.engine === "dsh" ? "DSH" : "CN CODE"}
                  </span>
                  <pre>{final ?? partial}</pre>
                </article>
              )}
              {run?.error && (
                <article className="assistant-message">
                  <span className="section-label">运行已停止</span>
                  <pre className="error-text">{run.error}</pre>
                </article>
              )}
              {trace.length > 0 && (
                <details className="trace" open>
                  <summary>
                    运行轨迹 <span className="muted">{trace.length} 条</span>
                  </summary>
                  {trace.map((e) => (
                    <details className="trace-row" key={e.seq}>
                      <summary>
                        <span className="trace-seq">
                          {String(e.seq).padStart(2, "0")}
                        </span>
                        <span>
                          {e.type.startsWith("tool.")
                            ? (e.data.name ?? "工具") +
                              " · " +
                              (e.type.endsWith("start") ? "开始" : "结束")
                            : e.type}
                        </span>
                        <span className="muted">
                          {duration(e.data.durationMs)}
                        </span>
                      </summary>
                      <pre>{JSON.stringify(e.data, null, 2)}</pre>
                    </details>
                  ))}
                </details>
              )}
              {currentInteractions.map((i) => (
                <InteractionView
                  key={i.id}
                  interaction={i}
                  answer={(response) => answer(i.id, response)}
                />
              ))}
            </div>
            <div className="composer">
              <Textarea
                aria-label="任务输入"
                placeholder={
                  session ? "描述任务，或继续当前会话…" : "创建会话后描述任务…"
                }
                value={text}
                disabled={!session || running}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    void send();
                  }
                }}
              />
              <div className="composer-actions">
                <span className="muted">
                  {session?.engine === "dsh" ? "DSH" : "CN Code"} ·{" "}
                  {session?.memory ? "记忆开启" : "记忆关闭"}
                </span>
                {running ? (
                  <Button onPress={() => void stop()} disabled={busy}>
                    ■ 停止
                  </Button>
                ) : (
                  <Button
                    variant="primary"
                    disabled={!session || !text.trim() || busy}
                    onPress={() => void send()}
                  >
                    发送 ↑
                  </Button>
                )}
              </div>
            </div>
            <p className="composer-hint muted">
              Ctrl / ⌘ + Enter 发送 · 切换内核将创建新会话
            </p>
          </section>
          <aside className="inspector">
            <h2>运行记录</h2>
            <dl>
              <dt>执行</dt>
              <dd>{run ? stateNames[run.state] : "尚未开始"}</dd>
              <dt>验收</dt>
              <dd>{run ? outcomeNames[run.outcome] : "尚未验收"}</dd>
            </dl>
            <p className="muted">执行结束与验收结果分别记录。</p>
            <h3>消耗与耗时</h3>
            <dl>
              {[
                ["输入 token", count(metric.inputTokens)],
                ["输出 token", count(metric.outputTokens)],
                ["缓存读取", count(metric.cacheReadTokens)],
                ["模型请求", count(metric.requests)],
                ["总耗时", duration(metric.wallMs)],
                ["人工等待", duration(metric.humanWaitMs)],
                ["工具执行总和", duration(metric.phaseTotalsMs?.["tool.end"])],
              ].map(([k, v]) => (
                <div className="metric-row" key={k}>
                  <dt>{k}</dt>
                  <dd>{v}</dd>
                </div>
              ))}
            </dl>
            <p className="muted">
              未知用量保留未知。并行工具时间的总和不代表总耗时。
            </p>
            {Object.keys(metric.byPurpose ?? {}).length > 0 && (
              <details>
                <summary>按请求用途查看</summary>
                <pre>{JSON.stringify(metric.byPurpose, null, 2)}</pre>
              </details>
            )}
            <h3>交付物</h3>
            {artifacts.length ? (
              artifacts.map((e) => (
                <p className="mono" key={e.seq}>
                  {e.data.path}
                </p>
              ))
            ) : (
              <p className="muted">尚无已记录交付物</p>
            )}
            <h3>项目记忆</h3>
            <p className="muted">
              {session?.memory
                ? "OpenViking 已配置；提取状态以服务返回为准。"
                : "当前会话关闭记忆。"}
            </p>
            {session?.memory && (
              <>
                <Button onPress={() => void memoryAction("list")}>
                  查看记忆
                </Button>
                <Button onPress={() => void memoryAction("commit")}>
                  提交提取
                </Button>
                <pre>{memoryResult}</pre>
              </>
            )}
            {run && (
              <a
                href={"/api/v1/runs/" + run.id}
                onClick={async (e) => {
                  e.preventDefault();
                  const data = await api("runs/" + run.id);
                  const url = URL.createObjectURL(
                    new Blob([JSON.stringify(data, null, 2)], {
                      type: "application/json",
                    }),
                  );
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = "run-" + run.id + ".json";
                  a.click();
                  URL.revokeObjectURL(url);
                }}
              >
                导出运行记录 ↓
              </a>
            )}
          </aside>
        </div>
      </main>
      {creating && (
        <div className="modal-backdrop">
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-title"
            className="modal"
          >
            <h2 id="create-title">新建编码会话</h2>
            <p className="muted">一个会话固定一个内核。历史会话仍保留。</p>
            <label>
              工作区目录
              <input
                autoFocus
                value={cwd}
                onChange={(e) => setCwd(e.target.value)}
              />
            </label>
            <label>
              执行内核
              <select
                value={engine}
                onChange={(e) => {
                  setEngine(e.target.value as EngineId);
                  setMemory(false);
                }}
              >
                <option value="cncode">CN Code · 自研</option>
                <option value="dsh">DSH · 0.2.2-alpha.2</option>
              </select>
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={memory}
                disabled={!config.memoryConfigured || engine === "dsh"}
                onChange={(e) => setMemory(e.target.checked)}
              />
              启用 OpenViking 项目记忆
            </label>
            <p className="muted">
              {engine === "dsh"
                ? "DSH 记忆插件尚未接入验收。"
                : config.memoryConfigured
                  ? "服务已配置，真实提取与隔离须单独验证。"
                  : "OpenViking 服务未配置；默认关闭记忆。"}
            </p>
            {error && <p role="alert">{error}</p>}
            <div className="modal-actions">
              <Button onPress={() => setCreating(false)}>取消</Button>
              <Button
                variant="primary"
                disabled={busy || !cwd.trim()}
                onPress={() => void create()}
              >
                创建会话
              </Button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
function InteractionView({
  interaction: i,
  answer,
}: {
  interaction: Interaction;
  answer: (v: Json) => Promise<void>;
}) {
  const [text, setText] = useState(""),
    [working, setWorking] = useState(false);
  const [native, setNative] = useState<
    Record<string, { selected: string[]; custom: string }>
  >({});
  const submit = async (v: Json) => {
    setWorking(true);
    try {
      await answer(v);
    } finally {
      setWorking(false);
    }
  };
  return (
    <section
      className="interaction"
      aria-label={i.kind === "approval" ? "工具审批" : "问题回答"}
    >
      <h2>{i.kind === "approval" ? "需要你的批准" : "需要补充信息"}</h2>
      {i.kind === "approval" ? (
        <>
          <pre>{JSON.stringify(i.payload, null, 2)}</pre>
          <div className="modal-actions">
            <Button
              disabled={working}
              onPress={() => void submit({ allow: false })}
            >
              拒绝
            </Button>
            <Button
              variant="primary"
              disabled={working}
              onPress={() => void submit({ allow: true })}
            >
              批准本次
            </Button>
          </div>
        </>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submit(
              i.payload.native
                ? {
                    answers: i.payload.native.questions.map((q: Json) => ({
                      id: q.id,
                      selected: native[q.id]?.selected ?? [],
                      custom: native[q.id]?.custom ?? "",
                    })),
                  }
                : { text },
            );
          }}
        >
          {i.payload.native ? (
            i.payload.native.questions.map((q: Json) => (
              <fieldset key={q.id}>
                <legend>{q.question ?? q.prompt ?? q.header ?? q.id}</legend>
                {q.options?.map((o: Json | string) => {
                  const label =
                    typeof o === "string" ? o : (o.label ?? o.value);
                  return (
                    <label className="checkbox" key={label}>
                      <input
                        type={q.multiSelect ? "checkbox" : "radio"}
                        name={"question-" + q.id}
                        checked={
                          native[q.id]?.selected?.includes(label) ?? false
                        }
                        onChange={(e) =>
                          setNative((prev) => ({
                            ...prev,
                            [q.id]: {
                              custom: q.multiSelect
                                ? (prev[q.id]?.custom ?? "")
                                : "",
                              selected: e.target.checked
                                ? q.multiSelect
                                  ? [...(prev[q.id]?.selected ?? []), label]
                                  : [label]
                                : (prev[q.id]?.selected ?? []).filter(
                                    (x) => x !== label,
                                  ),
                            },
                          }))
                        }
                      />
                      {label}
                    </label>
                  );
                })}
                <input
                  aria-label={"补充 " + q.id}
                  value={native[q.id]?.custom ?? ""}
                  onChange={(e) =>
                    setNative((prev) => ({
                      ...prev,
                      [q.id]: {
                        selected: q.multiSelect
                          ? (prev[q.id]?.selected ?? [])
                          : [],
                        custom: e.target.value,
                      },
                    }))
                  }
                />
              </fieldset>
            ))
          ) : (
            <>
              <p>{i.payload.question}</p>
              {i.payload.options?.map((o: string) => (
                <Button key={o} type="button" onPress={() => setText(o)}>
                  {o}
                </Button>
              ))}
              <input
                aria-label="回答内容"
                required
                value={text}
                onChange={(e) => setText(e.target.value)}
              />
            </>
          )}
          <Button variant="primary" disabled={working} type="submit">
            提交回答
          </Button>
        </form>
      )}
    </section>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
