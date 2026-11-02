// Trusted positive/negative controls. These results never count as Agent solutions.
import { mkdir, cp, writeFile, readFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { randomUUID } from "node:crypto";
const exec = promisify(execFile);
const project = resolve(".."),
  root = resolve("../.cncode/vnext/verifier-check-" + Date.now()),
  fixtures = resolve("../.cncode/vnext/eval/fixtures"),
  work = join(root, "workspace");
await mkdir(work, { recursive: true });
const image = "cncode-eval:0.2.0",
  name = "cncode-verify-" + randomUUID(),
  network = name + "-net";
const docker = (args: string[]) =>
  exec("docker", args, {
    timeout: 120000,
    maxBuffer: 4 * 1024 * 1024,
    windowsHide: true,
  });
const reports: any[] = [];
async function check(task: string, expectPass: boolean, label: string) {
  const args = [
    "run",
    "--rm",
    "--network",
    task === "phone-number" ? "none" : network,
    "--read-only",
    "--tmpfs",
    "/tmp:rw,exec,size=128m",
    "--mount",
    `type=bind,source=${work},target=/app,readonly`,
    "--mount",
    `type=bind,source=${join(fixtures, task)},target=/trusted,readonly`,
    "--mount",
    `type=bind,source=${join(project, "vnext/eval")},target=/verifier,readonly`,
    image,
    ...(task === "phone-number"
      ? ["node", "/verifier/verify-phone.mjs"]
      : ["python", "/verifier/verify-kv.py"]),
  ];
  let pass = true,
    log = "";
  try {
    const r = await docker(args);
    log = r.stdout + r.stderr;
  } catch (e: any) {
    pass = false;
    log = (e.stdout ?? "") + (e.stderr ?? "");
  }
  if (
    task === "phone-number" &&
    !expectPass &&
    !log.includes('\"outcome\":\"fail\"')
  )
    throw new Error("Negative control hit infrastructure error: " + log);
  await writeFile(join(root, label + ".log"), log);
  reports.push({ label, pass, expectPass });
  if (pass !== expectPass)
    throw new Error("Verifier control failed: " + label + "\n" + log);
  console.log(label + " accepted=" + pass);
}
try {
  await cp(
    join(fixtures, "phone-number/phone-number.js"),
    join(work, "phone-number.js"),
  );
  await check("phone-number", false, "phone-skeleton");
  await cp(
    join(fixtures, "phone-number/.meta/proof.ci.js"),
    join(work, "phone-number.js"),
  );
  await check("phone-number", true, "phone-reference");
  await docker(["network", "create", network]);
  await docker([
    "run",
    "-d",
    "--name",
    name,
    "--network",
    network,
    "--network-alias",
    "agent",
    "--mount",
    `type=bind,source=${work},target=/app`,
    "--mount",
    `type=bind,source=${join(fixtures, "kv-store-grpc/solution")},target=/solution,readonly`,
    image,
  ]);
  await writeFile(
    join(work, "control-solve.sh"),
    (
      await readFile(join(fixtures, "kv-store-grpc/solution/solve.sh"), "utf8")
    ).replaceAll("\r\n", "\n"),
  );
  await docker(["exec", name, "bash", "/app/control-solve.sh"]);
  await check("kv-store-grpc", true, "kv-reference");
  await docker(["exec", name, "pkill", "-f", "^python server.py$"]);
  await check("kv-store-grpc", false, "kv-no-listener");
} finally {
  await docker(["rm", "-f", name]).catch(() => {});
  await docker(["network", "rm", network]).catch(() => {});
  await writeFile(
    join(root, "summary.json"),
    JSON.stringify(
      { kind: "verifier-controls-not-agent-performance", reports },
      null,
      2,
    ),
  );
}
