#!/usr/bin/env node
import http from "node:http";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { spawn } from "node:child_process";
const HOST = process.env.WECANDOIT_REA_HOST || "0.0.0.0";
const PORT = Number(process.env.WECANDOIT_REA_PORT || 8765);
const TOKEN = process.env.WECANDOIT_REA_TOKEN || "";
const REA = process.env.WECANDOIT_REA_BIN || "rea";
const MAX = 80 * 1024 * 1024;
const tmp = path.join(os.tmpdir(), "wecandoit-rea");
fs.mkdirSync(tmp, { recursive: true, mode: 0o700 });
function authorized(req) {
  if (!TOKEN) return false;
  const supplied = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const a = Buffer.from(supplied), b = Buffer.from(TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}
function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}
function runRea(args, timeoutMs = 180000) {
  return new Promise(resolve => {
    const child = spawn(REA, [...args, "--json"], { shell: false, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "", settled = false;
    const finish = code => {
      if (settled) return; settled = true; clearTimeout(timer);
      resolve({ exitCode: code, stdout: stdout.slice(0, 2000000), stderr: stderr.slice(0, 12000) });
    };
    const timer = setTimeout(() => { child.kill("SIGTERM"); finish(124); }, timeoutMs);
    child.stdout.on("data", b => { if (stdout.length < 2000000) stdout += b.toString(); });
    child.stderr.on("data", b => { if (stderr.length < 12000) stderr += b.toString(); });
    child.on("error", e => { stderr += e.message; finish(127); });
    child.on("close", code => finish(code ?? 1));
  });
}
const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    const result = await runRea(["--version"], 10000);
    return json(res, result.exitCode === 0 ? 200 : 503, { ok: result.exitCode === 0, rea: result.stdout.trim(), error: result.stderr });
  }
  if (!authorized(req)) return json(res, 401, { ok: false, error: "Bearer token이 필요합니다." });
  if (req.method !== "POST" || !req.url?.startsWith("/analyze")) return json(res, 404, { ok: false, error: "지원하지 않는 경로입니다." });
  if (!/^application\/vnd\.android\.package-archive$/i.test(req.headers["content-type"] || "")) {
    return json(res, 415, { ok: false, error: "APK content-type이 필요합니다." });
  }
  const target = path.join(tmp, "input-" + randomUUID() + ".apk");
  const out = fs.createWriteStream(target, { flags: "wx", mode: 0o600 });
  let bytes = 0, failed = false;
  req.on("data", chunk => { bytes += chunk.length; if (bytes > MAX && !failed) { failed = true; req.destroy(); out.destroy(); } });
  req.pipe(out);
  out.on("error", () => { try { fs.unlinkSync(target); } catch {} });
  out.on("finish", async () => {
    if (failed || bytes === 0) { try { fs.unlinkSync(target); } catch {} return json(res, 413, { ok: false, error: "APK가 비어 있거나 80MiB 제한을 초과했습니다." }); }
    try {
      const result = await runRea(["inspect-android-package", target], 240000);
      let parsed = null;
      try { parsed = JSON.parse(result.stdout); } catch {}
      json(res, result.exitCode === 0 ? 200 : 422, {
        ok: result.exitCode === 0, exitCode: result.exitCode, fileBytes: bytes,
        result: parsed, raw: parsed ? undefined : result.stdout,
        error: result.stderr || undefined,
        note: "정적 APK 분석 결과입니다. APK 실행이나 런타임 동작은 검사하지 않았습니다."
      });
    } finally { try { fs.unlinkSync(target); } catch {} }
  });
});
server.listen(PORT, HOST, () => {
  console.log("Wecandoit REA bridge listening on " + HOST + ":" + PORT);
  if (!TOKEN) console.error("Set WECANDOIT_REA_TOKEN before exposing this server.");
});
