import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import { loadConfig } from "./config.js";
import { Service } from "./service.js";
import { server } from "./server.js";
const config = loadConfig();
if (!["127.0.0.1", "localhost", "::1"].includes(config.host) && !config.token)
  throw new Error("远程监听需要显式设置 CNCODE_TOKEN");
config.token ||= randomBytes(24).toString("hex");
const service = new Service(config),
  http = server(
    service,
    fileURLToPath(new URL("../web-dist", import.meta.url)),
  );
// src/ and dist/ entry points share their parent vnext directory.
http.listen(config.port, config.host, () => {
  const address = http.address();
  const port = typeof address === "object" ? address?.port : config.port;
  const url = `http://${config.host}:${port}`;
  process.send?.({ type: "ready", url, token: config.token });
  if (!process.send) console.log("CN Code " + url + "/#token=" + config.token);
});
async function stop() {
  await service.close();
  http.closeAllConnections();
  http.close(() => process.exit());
}
process.on("SIGTERM", () => void stop());
process.on("SIGINT", () => void stop());
process.on("message", (m) => {
  if ((m as any)?.type === "shutdown") void stop();
});
