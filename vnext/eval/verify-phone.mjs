import {
  readFileSync,
  writeFileSync,
  cpSync,
  mkdirSync,
  existsSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
mkdirSync("/tmp/check", { recursive: true });
cpSync("/app/phone-number.js", "/tmp/check/phone-number.js");
const original = readFileSync("/trusted/phone-number.spec.js", "utf8");
const enabled = original.replace(/\bxtest\s*\(/g, "test(");
if (/\b(?:xtest|xdescribe|test\.skip|describe\.skip)\s*\(/.test(enabled))
  throw new Error("Skipped tests remain");
writeFileSync("/tmp/check/phone-number.spec.js", enabled);
cpSync("/trusted/babel.config.js", "/tmp/check/babel.config.js");
writeFileSync("/tmp/check/package.json", "{}");
writeFileSync(
  "/tmp/jest.config.cjs",
  "module.exports = " +
    JSON.stringify({
      rootDir: "/tmp/check",
      testEnvironment: "node",
      transform: {
        [String.raw`^.+\.js$`]: "/verifier-deps/node_modules/babel-jest",
      },
    }),
);
const result = spawnSync(
  "/verifier-deps/node_modules/.bin/jest",
  [
    "--runInBand",
    "--config=/tmp/jest.config.cjs",
    "--json",
    "--outputFile=/tmp/jest.json",
  ],
  {
    env: { ...process.env, NODE_PATH: "/verifier-deps/node_modules" },
    encoding: "utf8",
    timeout: 60000,
  },
);
console.log(result.stdout);
console.error(result.stderr);
if (!existsSync("/tmp/jest.json")) {
  console.error("VERIFIER_INFRA_ERROR: Jest did not produce a report");
  process.exit(2);
}
const report = JSON.parse(readFileSync("/tmp/jest.json", "utf8"));
const expected = (enabled.match(/\btest\s*\(/g) ?? []).length;
const ok =
  result.status === 0 &&
  report.numTotalTests === expected &&
  report.numPassedTests === expected &&
  report.numPendingTests === 0 &&
  report.numTodoTests === 0;
console.log(
  JSON.stringify({
    expected,
    passed: report.numPassedTests,
    skipped: report.numPendingTests,
    outcome: ok ? "pass" : "fail",
  }),
);
process.exit(ok ? 0 : 1);
