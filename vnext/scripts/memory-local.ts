// Local deployment only. Credentials and all writable mounts stay in dataDir on D:.
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import { loadConfig, requireModel } from "../src/config.js";

const config = loadConfig(),
  root = resolve(config.dataDir, "openviking");
if (process.platform === "win32" && !/^D:\\/i.test(root))
  throw new Error("OpenViking dataDir must be on D:");
const action = process.argv[2] ?? "status";
function docker(args: string[]) {
  const r = spawnSync("docker", args, { stdio: "inherit" });
  if (r.status !== 0) throw new Error("Docker command failed");
}
if (action === "build") {
  docker([
    "build",
    "-t",
    "cncode-openviking:0.5.3",
    "-f",
    "runtime/openviking.Dockerfile",
    "runtime",
  ]);
} else if (action === "up") {
  requireModel(config);
  for (const d of ["home", "cache", "tmp", "data"])
    await mkdir(join(root, d), { recursive: true });
  const connectionPath = join(root, "connection.json");
  const connection = existsSync(connectionPath)
    ? JSON.parse(await readFile(connectionPath, "utf8"))
    : {
        url: "http://127.0.0.1:1933",
        rootKey: randomBytes(32).toString("hex"),
      };
  await writeFile(connectionPath, JSON.stringify(connection, null, 2), {
    mode: 0o600,
  });
  await writeFile(
    join(root, "ov.conf"),
    JSON.stringify(
      {
        server: {
          host: "0.0.0.0",
          port: 1933,
          auth_mode: "api_key",
          root_api_key: connection.rootKey,
        },
        storage: {
          workspace: "/state/data",
          vectordb: { backend: "local" },
          agfs: { backend: "local" },
        },
        embedding: {
          max_concurrent: 1,
          dense: {
            provider: "local",
            model: "bge-small-zh-v1.5-f16",
            dimension: 512,
            cache_dir: "/state/cache/models",
          },
        },
        vlm: {
          provider: "openai",
          api_key: config.apiKey,
          api_base: config.baseUrl.replace(/\/chat\/completions\/?$/, ""),
          model: config.model,
          max_concurrent: 2,
          max_retries: 0,
        },
        log: { level: "WARNING" },
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  const existing = spawnSync(
    "docker",
    ["inspect", "--format", "{{.Image}}", "cncode-openviking"],
    { encoding: "utf8", windowsHide: true },
  );
  const image = spawnSync(
    "docker",
    ["image", "inspect", "--format", "{{.Id}}", "cncode-openviking:0.5.3"],
    { encoding: "utf8", windowsHide: true },
  );
  if (image.status !== 0) throw new Error("Run pnpm memory build first");
  const recreate =
    existing.status !== 0 || existing.stdout.trim() !== image.stdout.trim();
  if (recreate && existing.status === 0) {
    docker(["stop", "cncode-openviking"]);
    docker(["rm", "cncode-openviking"]);
  }
  if (!recreate) docker(["restart", "cncode-openviking"]);
  else
    docker([
      "run",
      "-d",
      "--name",
      "cncode-openviking",
      "--cpus",
      "2",
      "--memory",
      "2g",
      "-p",
      "127.0.0.1:1933:1933",
      "--mount",
      `type=bind,source=${root},target=/state`,
      "cncode-openviking:0.5.3",
    ]);
  console.log(
    "OpenViking starting at http://127.0.0.1:1933; data/cache/config: " + root,
  );
} else if (action === "down") docker(["stop", "cncode-openviking"]);
else if (action === "status")
  docker([
    "inspect",
    "--format",
    "{{.State.Status}} {{.Image}}",
    "cncode-openviking",
  ]);
else throw new Error("Usage: tsx scripts/memory-local.ts build|up|down|status");
