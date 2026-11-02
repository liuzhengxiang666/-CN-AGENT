import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { randomUUID } from "node:crypto";
import { Records } from "../records.js";
import { loadConfig } from "../config.js";
import { prepare, tasks, hashTree } from "./fixtures.js";
const exec = promisify(execFile);
const project = fileURLToPath(new URL("../../../", import.meta.url));
const root = resolve(
  process.env.CNCODE_EVAL_DIR ?? join(project, ".cncode/vnext/eval"),
);
const fixtures = join(root, "fixtures"),
  image = "cncode-eval:0.2.0";
const docker = async (args: string[], timeout = 120000) =>
  exec("docker", args, {
    cwd: project,
    timeout,
    maxBuffer: 8 * 1024 * 1024,
    windowsHide: true,
  });
const action = process.argv[2] ?? "help";
if (action === "prepare") {
  await prepare(fixtures);
  console.log("Pinned task fixtures prepared at " + fixtures);
} else if (action === "build") {
  const r = await docker(
    ["build", "-f", "vnext/eval/Dockerfile", "-t", image, "."],
    1200000,
  );
  console.log(r.stdout + r.stderr);
} else if (action === "run") {
  const config = loadConfig();
  if (!config.apiKey) throw new Error("Missing model credentials");
  await mkdir(root, { recursive: true });
  let failed = false;
  for (const task of tasks)
    for (const engine of ["cncode", "dsh"] as const) {
      const id = task.id + "-" + engine + "-" + randomUUID(),
        dir = join(root, id),
        work = join(dir, "workspace"),
        data = join(dir, "data"),
        trusted = join(fixtures, task.id),
        container = "cncode-" + randomUUID(),
        network = container + "-net";
      await mkdir(work, { recursive: true });
      await mkdir(data, { recursive: true });
      let report: any = {
        id,
        task: task.id,
        engine,
        fixtureCommit: task.commit,
        model: config.model,
        memory: false,
        learning: false,
        state: "not_started",
        outcome: "infra_error",
        startedAt: new Date().toISOString(),
      };
      const start = performance.now();
      let created = false,
        networkCreated = false;
      try {
        report.image = (
          await docker(["image", "inspect", image, "--format", "{{.Id}}"])
        ).stdout.trim();
        report.environment = {
          toolPolicy: "coding-only-v1",
          node: "24.16.0",
          python: "Debian 3.11 venv",
          grpcio: "1.73.0",
          cpus: 1,
          memory: "2g",
          maxTokens: 8192,
          deadlineSeconds: task.seconds,
        };
        const manifest = JSON.parse(
          await readFile(join(fixtures, task.id + ".manifest.json"), "utf8"),
        );
        const hashes = await hashTree(trusted);
        if (JSON.stringify(hashes) !== JSON.stringify(manifest.hashes))
          throw new Error("Trusted fixtures changed after prepare");
        if (task.id === "phone-number")
          await cp(
            join(trusted, "phone-number.js"),
            join(work, "phone-number.js"),
          );
        const prompt =
          task.id === "phone-number"
            ? (await readFile(join(trusted, ".docs/instructions.md"), "utf8")) +
              "\nImplement clean in /app/phone-number.js. Throw the precise errors required by the exercise. The following tests define the required errors and behavior. Trusted copies are kept outside the Agent container.\n" +
              (await readFile(join(trusted, "phone-number.spec.js"), "utf8")) +
              ""
            : await readFile(join(trusted, "instruction.md"), "utf8");
        await writeFile(join(data, "prompt.txt"), prompt);
        await docker(["network", "create", network]);
        networkCreated = true;
        await docker([
          "run",
          "-d",
          "--name",
          container,
          "--network",
          network,
          "--network-alias",
          "agent",
          "--cap-drop=ALL",
          "--security-opt=no-new-privileges",
          "--cpus=1",
          "--memory=2g",
          "--pids-limit=256",
          "--mount",
          `type=bind,source=${work},target=/app`,
          "--mount",
          `type=bind,source=${data},target=/data`,
          image,
        ]);
        created = true;
        const env = {
          CNCODE_MODEL: config.model,
          CNCODE_BASE_URL: config.baseUrl,
          CNCODE_API_KEY: config.apiKey,
          CNCODE_PROTOCOL: config.protocol,
          CNCODE_ENGINE: engine,
          CNCODE_EVAL_TOOLS: "1",
          CNCODE_TASK: task.id,
          CNCODE_FIXTURE_COMMIT: task.commit,
          CNCODE_DEADLINE_SECONDS: String(task.seconds),
          TEMP: "/tmp",
          TMP: "/tmp",
          HOME: "/data/home",
          CNCODE_CONFIG: "/data/no-config",
          DSH_HOME: "/data/dsh",
        };
        // Values travel via the child environment, never shell quoting or command output.
        const args = [
          "exec",
          ...Object.keys(env).flatMap((k) => ["-e", k]),
          container,
          "node",
          "/harness/vnext/dist/eval/worker.js",
        ];
        const execution = await exec("docker", args, {
          env: { ...process.env, ...env },
          timeout: (task.seconds + 45) * 1000,
          maxBuffer: 8 * 1024 * 1024,
          windowsHide: true,
        });
        await writeFile(
          join(dir, "execution.log"),
          execution.stdout + execution.stderr,
        );
        const run = JSON.parse(
          await readFile(join(data, "result.json"), "utf8"),
        );
        report = {
          ...report,
          runId: run.id,
          state: run.state,
          metrics: run.metrics,
          error: run.error,
        };
        const infra =
          /\b401\b|\b403\b|\b429\b|HTTP 5\d\d|authentication|api key|fetch failed|ECONN|ENOTFOUND|握手|插件未加载/i.test(
            run.error ?? "",
          );
        const timedOut = run.state === "cancelled";
        report.outcome = infra ? "infra_error" : "fail";
        if (!infra && !timedOut) {
          const args = [
            "run",
            "--rm",
            "--network",
            network,
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--read-only",
            "--tmpfs",
            "/tmp:rw,exec,size=128m",
            "--mount",
            `type=bind,source=${work},target=/app,readonly`,
            "--mount",
            `type=bind,source=${trusted},target=/trusted,readonly`,
            "--mount",
            `type=bind,source=${join(project, "vnext/eval")},target=/verifier,readonly`,
            image,
            ...(task.id === "phone-number"
              ? ["node", "/verifier/verify-phone.mjs"]
              : ["python", "/verifier/verify-kv.py"]),
          ];
          try {
            const check = await docker(args, 90000);
            await writeFile(
              join(dir, "verification.log"),
              check.stdout + check.stderr,
            );
            report.outcome = "pass";
          } catch (e: any) {
            await writeFile(
              join(dir, "verification.log"),
              (e.stdout ?? "") + (e.stderr ?? ""),
            );
            report.outcome = e.killed || e.code === 2 ? "infra_error" : "fail";
          }
        }
        const after = await hashTree(trusted);
        if (JSON.stringify(after) !== JSON.stringify(hashes))
          throw new Error("Trusted fixtures mutated");
        await writeFile(
          join(dir, "artifacts.manifest.json"),
          JSON.stringify(await hashTree(work), null, 2),
        );
        const empty = join(dir, "initial");
        await mkdir(empty, { recursive: true });
        const diff = await exec(
          "git",
          [
            "diff",
            "--no-index",
            "--",
            task.id === "phone-number"
              ? join(trusted, "phone-number.js")
              : empty,
            task.id === "phone-number" ? join(work, "phone-number.js") : work,
          ],
          { maxBuffer: 2 * 1024 * 1024 },
        ).catch((e: any) => ({
          stdout: e.stdout ?? "",
          stderr: e.stderr ?? "",
        }));
        await writeFile(join(dir, "code.diff"), diff.stdout);
      } catch (e: any) {
        report.outcome = "infra_error";
        report.error = String(e.message)
          .split(config.apiKey)
          .join("[REDACTED]");
      } finally {
        if (report.runId)
          try {
            const records = new Records(join(data, "records"), [
              config.apiKey,
              config.memoryKey ?? "",
            ]);
            const run = records.get(report.runId);
            run.outcome = report.outcome;
            records.save(run);
            records.emit(run.id, "delivery.verdict", {
              outcome: report.outcome,
              source: "trusted-external-verifier",
              image: report.image,
            });
          } catch (e) {
            report.recordingError = String(e);
            report.outcome = "infra_error";
          }
        if (created) await docker(["rm", "-f", container]).catch(() => {});
        if (networkCreated)
          await docker(["network", "rm", network]).catch(() => {});
        report.wallMs = performance.now() - start;
        report.endedAt = new Date().toISOString();
        await writeFile(
          join(dir, "report.json"),
          JSON.stringify(report, null, 2),
        );
        failed ||= report.outcome !== "pass";
        console.log(JSON.stringify(report));
      }
    }
  if (failed) process.exitCode = 1;
} else
  console.log(
    "Usage: pnpm eval prepare | build | run (four serial runs; memory and learning off)",
  );
