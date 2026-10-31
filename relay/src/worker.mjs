import { createRemoteJWKSet, jwtVerify } from "jose";
import * as push from "./push.mjs";
export const MAX_BODY = 2 * 1024 * 1024,
  MAX_RESULT = 8 * 1024 * 1024,
  FRAME = 512 * 1024,
  TTL = 1800000;
const enc = new TextEncoder(),
  secret = () => crypto.randomUUID() + crypto.randomUUID();
export const b64 = (b) => btoa(String.fromCharCode(...new Uint8Array(b)));
const un64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const hash = async (s) =>
  Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s))),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
const json = (o, status = 200, headers = {}) =>
  Response.json(o, {
    status,
    headers: { "Cache-Control": "no-store", ...headers },
  });
function fail(code, status = 400) {
  throw Object.assign(new Error(code), { status });
}
export async function body(req, max = MAX_BODY) {
  if (Number(req.headers.get("content-length")) > max)
    fail("payload_too_large", 413);
  const reader = req.body?.getReader();
  if (!reader) return {};
  let n = 0,
    parts = [];
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      n += value.length;
      if (n > max) fail("payload_too_large", 413);
      parts.push(value);
    }
  } finally {
    await reader.cancel();
  }
  const all = new Uint8Array(n);
  let p = 0;
  for (const v of parts) {
    all.set(v, p);
    p += v.length;
  }
  const text = new TextDecoder("utf-8", { fatal: true }).decode(all);
  try {
    return req.headers
      .get("content-type")
      ?.startsWith("application/x-www-form-urlencoded")
      ? Object.fromEntries(new URLSearchParams(text))
      : JSON.parse(text);
  } catch {
    fail("invalid_json");
  }
}
async function owner(req, env) {
  if (!/^[a-z0-9-]+$/.test(env.ACCESS_TEAM || "") || !env.ACCESS_AUD)
    fail("owner_access_not_configured", 503);
  const issuer = `https://${env.ACCESS_TEAM}.cloudflareaccess.com`,
    token = req.headers.get("Cf-Access-Jwt-Assertion");
  if (!token) fail("owner_required", 401);
  await jwtVerify(
    token,
    createRemoteJWKSet(new URL(issuer + "/cdn-cgi/access/certs")),
    { issuer, audience: env.ACCESS_AUD },
  );
}
export default {
  async fetch(req, env) {
    try {
      let url = new URL(req.url);
      const discovery = url.pathname.match(
        /^\/\.well-known\/(oauth-authorization-server|oauth-protected-resource)\/d\/([a-zA-Z0-9_-]{8,128})(?:\/mcp)?$/,
      );
      if (discovery) {
        url.pathname = "/d/" + discovery[2] + "/.well-known/" + discovery[1];
        req = new Request(url, req);
      }
      if (url.origin !== env.PUBLIC_ORIGIN) fail("wrong_audience", 403);
      const origin = req.headers.get("Origin");
      if (
        origin &&
        !(origin === url.origin && url.pathname.includes("/admin/")) &&
        !JSON.parse(env.ALLOWED_ORIGINS || "[]").includes(origin)
      )
        fail("origin_not_allowed", 403);
      const m = url.pathname.match(/^\/d\/([a-zA-Z0-9_-]{8,128})(\/.*)?$/);
      if (!m) fail("not_found", 404);
      if ((m[2] || "").startsWith("/admin/") || m[2] === "/authorize")
        await owner(req, env);
      return await env.PHONES.get(env.PHONES.idFromName(m[1])).fetch(req);
    } catch (e) {
      return json(
        { error: e.status ? e.message : "unauthorized" },
        e.status || 401,
      );
    }
  },
};
export class PhoneRelay {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.memory = new Map();
    this.waiters = new Map();
    this.fcm = {};
    this.lastWake = 0;
  }
  // A sleeping phone has no socket. One FCM wake-up at most every 10 s.
  async wake() {
    const account = push.serviceAccount(this.env.FCM_SERVICE_ACCOUNT);
    const token = await this.ctx.storage.get("push_token");
    if (!account || !token || Date.now() - this.lastWake < 10000) return false;
    this.lastWake = Date.now();
    try {
      const r = await push.wake(account, this.fcm, token);
      if (r === "unregistered") await this.ctx.storage.delete("push_token");
      return r === "sent";
    } catch {
      return false;
    }
  }
  async waitForSocket(ms) {
    const until = Date.now() + ms;
    while (!this.sockets().length && Date.now() < until)
      await new Promise((r) => setTimeout(r, 250));
    return this.sockets().length > 0;
  }
  async save(j) {
    for (const field of ["payload", "result"])
      if (j[field] !== undefined) {
        const text =
          typeof j[field] === "string" ? j[field] : JSON.stringify(j[field]);
        const pages = [];
        for (let i = 0; i < text.length; i += 32768) {
          const key = `b:${j.id}:${field}:${String(pages.length).padStart(4, "0")}`;
          await this.ctx.storage.put(key, {
            data: text.slice(i, i + 32768),
            until: j.until,
          });
          pages.push(key);
        }
        j[field + "_pages"] = pages;
        delete j[field];
      }
    await this.ctx.storage.put("j:" + j.id, j);
    await this.schedule();
  }
  async blob(j, field) {
    if (!j[field + "_pages"]) return undefined;
    let text = "";
    for (const key of j[field + "_pages"]) {
      const page = await this.ctx.storage.get(key);
      if (!page) return undefined;
      text += page.data;
    }
    return field === "payload" ? JSON.parse(text) : text;
  }
  async erase(j) {
    for (const key of (
      await this.ctx.storage.list({ prefix: "b:" + j.id + ":" })
    ).keys())
      await this.ctx.storage.delete(key);
    delete j.payload_pages;
    delete j.result_pages;
  }
  async schedule() {
    const next = Date.now() + 30000,
      current = await this.ctx.storage.getAlarm();
    if (current === null || current > next)
      await this.ctx.storage.setAlarm(next);
  }
  sockets() {
    return this.ctx.getWebSockets("phone");
  }
  async fetch(req) {
    try {
      return await this.route(req);
    } catch (e) {
      return json(
        { error: e.status ? e.message : "invalid_request" },
        e.status || 400,
      );
    }
  }
  async route(req) {
    const url = new URL(req.url),
      [, , device, ...rest] = url.pathname.split("/"),
      path = "/" + rest.join("/"),
      root = url.origin + "/d/" + device;
    if (path === "/admin/" && req.method === "GET")
      return new Response(
        `<!doctype html><meta charset="utf-8"><title>Bridgit relay owner</title><h1>Relay device ${device}</h1><p>Owner access required. Paste public enrolment JSON from this phone. No private keys belong here.</p><form method="post" action="enrollment"><textarea name="public_keys" rows="8" cols="70" required></textarea><button>Enrol phone</button></form><form method="post" action="disable"><button>Disable device</button></form><form method="post" action="enable"><button>Enable enrolled device</button></form>`,
        {
          headers: {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-store",
            "Content-Security-Policy":
              "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
          },
        },
      );
    if (path === "/admin/enable" && req.method === "POST") {
      if (!(await this.ctx.storage.get("identity"))) fail("not_enrolled", 409);
      await this.ctx.storage.put("enabled", true);
      return json({ enabled: true, phone_confirmation_required: true });
    }
    if (path === "/admin/enrollment" && req.method === "POST") {
      if (await this.ctx.storage.get("identity")) fail("already_enrolled", 409);
      let b = await body(req, 8192);
      if (b.public_keys) b = JSON.parse(b.public_keys);
      await crypto.subtle.importKey(
        "spki",
        un64(b.identity_spki),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
      if (un64(b.hpke_public).length !== 65) fail("invalid_hpke_key");
      const token = secret();
      await this.ctx.storage.put("enrollment", {
        hash: await hash(token),
        identity: b.identity_spki,
        hpke: b.hpke_public,
        until: Date.now() + 300000,
      });
      await this.schedule();
      return json({
        device_id: device,
        enrollment_token: token,
        expires_in: 300,
      });
    }
    if (path === "/enroll" && req.method === "POST") {
      const b = await body(req, 8192),
        e = await this.ctx.storage.get("enrollment");
      if (
        !e ||
        e.until < Date.now() ||
        (await hash(b.token || "")) !== e.hash ||
        b.identity_spki !== e.identity ||
        b.hpke_public !== e.hpke
      )
        fail("invalid_enrollment", 401);
      await this.ctx.storage.transaction(async (s) => {
        if (await s.get("identity")) fail("already_enrolled", 409);
        await s.delete("enrollment");
        await s.put("identity", { spki: e.identity, hpke: e.hpke });
        await s.put("enabled", true);
      });
      return json({ enrolled: true });
    }
    if (path === "/admin/disable" && req.method === "POST") {
      await this.ctx.storage.put("enabled", false);
      await this.ctx.storage.delete("push_token");
      for (const ws of this.sockets()) ws.close(4003, "device_disabled");
      await this.cancelAll("device_disabled");
      return json({ disabled: true });
    }
    if (path === "/challenge" && req.method === "POST") {
      if (!(await this.ctx.storage.get("enabled")))
        fail("device_disabled", 403);
      const b = await body(req, 8192),
        identity = await this.ctx.storage.get("identity");
      if (
        !Number.isSafeInteger(b.at) ||
        Math.abs(Date.now() - b.at) > 60000 ||
        !/^[a-f0-9-]{36}$/.test(b.nonce || "")
      )
        fail("unauthorized", 401);
      const pk = await crypto.subtle.importKey(
        "spki",
        un64(identity.spki),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
      if (
        !(await crypto.subtle.verify(
          { name: "ECDSA", hash: "SHA-256" },
          pk,
          un64(b.signature || ""),
          enc.encode(
            `bridgit-relay-challenge-v1\n${device}\n${b.nonce}\n${b.at}`,
          ),
        ))
      )
        fail("unauthorized", 401);
      const proofKey = "proof:" + b.nonce;
      if (await this.ctx.storage.get(proofKey)) fail("replay_rejected", 409);
      await this.ctx.storage.put(proofKey, { until: Date.now() + 120000 });
      const pending = await this.ctx.storage.list({ prefix: "challenge:" });
      if (pending.size >= 32) fail("rate_limited", 429);
      const c = { nonce: secret(), until: Date.now() + 60000 };
      await this.ctx.storage.put("challenge:" + c.nonce, c);
      await this.schedule();
      return json(c);
    }
    if (
      path === "/socket" &&
      req.headers.get("Upgrade")?.toLowerCase() === "websocket"
    ) {
      const c = await this.ctx.storage.get(
          "challenge:" + (req.headers.get("X-Relay-Nonce") || ""),
        ),
        identity = await this.ctx.storage.get("identity");
      if (
        !(await this.ctx.storage.get("enabled")) ||
        !c ||
        c.until < Date.now() ||
        req.headers.get("X-Relay-Nonce") !== c.nonce
      )
        fail("unauthorized", 401);
      const pk = await crypto.subtle.importKey(
        "spki",
        un64(identity.spki),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
      if (
        !(await crypto.subtle.verify(
          { name: "ECDSA", hash: "SHA-256" },
          pk,
          un64(req.headers.get("X-Relay-Signature") || ""),
          enc.encode(`bridgit-relay-v1\n${device}\n${c.nonce}\n${c.until}`),
        ))
      )
        fail("unauthorized", 401);
      await this.ctx.storage.transaction(async (s) => {
        if (!(await s.get("challenge:" + c.nonce))) fail("unauthorized", 401);
        await s.delete("challenge:" + c.nonce);
      });
      for (const ws of this.sockets()) ws.close(4000, "replaced");
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1], ["phone"]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (path === "/.well-known/oauth-protected-resource")
      return json({
        resource: root + "/mcp",
        authorization_servers: [root],
        scopes_supported: ["bridgit"],
      });
    if (path === "/.well-known/oauth-authorization-server")
      return json({
        issuer: root,
        authorization_endpoint: root + "/authorize",
        token_endpoint: root + "/token",
        response_types_supported: ["code"],
        grant_types_supported: ["authorization_code"],
        code_challenge_methods_supported: ["S256"],
        token_endpoint_auth_methods_supported: ["none"],
      });
    if (path === "/authorize" && req.method === "GET") {
      if ((await this.ctx.storage.list({ prefix: "code:" })).size >= 128)
        fail("authorization_limit", 429);
      const q = url.searchParams,
        client = JSON.parse(this.env.OAUTH_CLIENTS || "{}")[q.get("client_id")];
      if (
        !client ||
        !client.redirect_uris.includes(q.get("redirect_uri")) ||
        q.get("resource") !== root + "/mcp" ||
        q.get("response_type") !== "code" ||
        q.get("code_challenge_method") !== "S256" ||
        !/^[A-Za-z0-9_-]{43}$/.test(q.get("code_challenge") || "")
      )
        fail("invalid_oauth_request");
      const a = ((await this.ctx.storage.get("agents")) || []).find(
        (a) => a.id === client.agent_id && a.mode === "connector",
      );
      if (!a) fail("agent_revoked", 403);
      const code = secret();
      await this.ctx.storage.put("code:" + (await hash(code)), {
        agent: a.id,
        generation: a.generation,
        client: q.get("client_id"),
        redirect: q.get("redirect_uri"),
        challenge: q.get("code_challenge"),
        until: Date.now() + 60000,
        resource: root + "/mcp",
      });
      await this.schedule();
      const target = new URL(q.get("redirect_uri"));
      target.searchParams.set("code", code);
      if (q.has("state")) target.searchParams.set("state", q.get("state"));
      return Response.redirect(target, 302);
    }
    if (path === "/token" && req.method === "POST") {
      if ((await this.ctx.storage.list({ prefix: "token:" })).size >= 128)
        fail("token_limit", 429);
      const b = await body(req, 8192),
        key = "code:" + (await hash(b.code || "")),
        c = await this.ctx.storage.get(key);
      if (
        !c ||
        c.until < Date.now() ||
        b.grant_type !== "authorization_code" ||
        b.client_id !== c.client ||
        b.redirect_uri !== c.redirect ||
        b.resource !== c.resource ||
        !/^[A-Za-z0-9._~-]{43,128}$/.test(b.code_verifier || "")
      )
        fail("invalid_grant");
      const challenge = b64(
        await crypto.subtle.digest("SHA-256", enc.encode(b.code_verifier)),
      )
        .replaceAll("+", "-")
        .replaceAll("/", "_")
        .replaceAll("=", "");
      if (challenge !== c.challenge) fail("invalid_grant");
      const token = "rlo_" + secret();
      await this.ctx.storage.transaction(async (s) => {
        if (!(await s.get(key))) fail("invalid_grant");
        await s.delete(key);
        await s.put("token:" + (await hash(token)), {
          agent: c.agent,
          generation: c.generation,
          until: Date.now() + 3600000,
          resource: c.resource,
        });
      });
      await this.schedule();
      return json({
        access_token: token,
        token_type: "Bearer",
        expires_in: 3600,
        scope: "bridgit",
      });
    }
    if (!(await this.ctx.storage.get("enabled"))) fail("device_disabled", 403);
    const token = req.headers.get("Authorization")?.match(/^Bearer (.+)$/)?.[1];
    if (!token)
      return json({ error: "unauthorized" }, 401, {
        "WWW-Authenticate": `Bearer resource_metadata="${root}/.well-known/oauth-protected-resource"`,
      });
    const agents = (await this.ctx.storage.get("agents")) || [],
      th = await hash(token);
    let a = agents.find((a) => a.token_hash === th);
    if (!a) {
      const t = await this.ctx.storage.get("token:" + th);
      if (
        t &&
        t.until > Date.now() &&
        t.resource === root + "/mcp" &&
        (path === "/mcp" || /^\/jobs\/[a-f0-9-]{36}$/.test(path))
      )
        a = agents.find(
          (a) =>
            a.id === t.agent &&
            a.generation === t.generation &&
            a.mode === "connector",
        );
    }
    if (!a) fail("unauthorized", 401);
    if (path === "/mcp" && req.method === "POST") {
      if (a.mode !== "connector") fail("mode_mismatch", 403);
      const b = await body(req),
        p = b.params || {},
        method = b.method;
      if (b.jsonrpc !== "2.0") fail("invalid_request");
      if (method === "initialize") {
        const sessions = [
          ...(await this.ctx.storage.list({ prefix: "session:" })).values(),
        ].filter((s) => s.until > Date.now());
        if (sessions.filter((s) => s.agent === a.id).length >= 8)
          fail("session_limit", 429);
        if (p.protocolVersion !== "2025-11-25") fail("unsupported_protocol");
        const sid = secret();
        await this.ctx.storage.put("session:" + (await hash(sid)), {
          agent: a.id,
          generation: a.generation,
          until: Date.now() + 3600000,
        });
        await this.schedule();
        return json(
          {
            jsonrpc: "2.0",
            id: b.id,
            result: {
              protocolVersion: "2025-11-25",
              capabilities: { tools: { listChanged: false } },
              serverInfo: { name: "bridgit-relay", version: "1" },
            },
          },
          200,
          { "Mcp-Session-Id": sid },
        );
      }
      const s = await this.ctx.storage.get(
        "session:" + (await hash(req.headers.get("Mcp-Session-Id") || "")),
      );
      if (
        !s ||
        s.until < Date.now() ||
        s.agent !== a.id ||
        s.generation !== a.generation
      )
        fail("invalid_session", 404);
      if (req.headers.get("MCP-Protocol-Version") !== "2025-11-25")
        fail("unsupported_protocol");
      if (method === "notifications/initialized")
        return new Response(null, { status: 202 });
      if (method === "ping")
        return json({ jsonrpc: "2.0", id: b.id, result: {} });
      if (!["tools/list", "tools/call"].includes(method))
        return json({
          jsonrpc: "2.0",
          id: b.id,
          error: { code: -32601, message: "Method not found" },
        });
      const j = await this.submit(
          a,
          device,
          {
            request: { method: "POST", path: "/relay/mcp", body: b },
            deadline: Date.now() + 55000,
          },
          false,
        ),
        done = await this.wait(j.id, 55000);
      if (done) {
        const response = await this.reply(done),
          data = await response.json();
        return data.jsonrpc
          ? json(data)
          : json({
              jsonrpc: "2.0",
              id: b.id,
              result: {
                isError: true,
                content: [{ type: "text", text: JSON.stringify(data) }],
              },
            });
      }
      return json({
        jsonrpc: "2.0",
        id: b.id,
        result: {
          isError: true,
          content: [
            {
              type: "text",
              text: JSON.stringify({
                error: "job_pending",
                job_id: j.id,
                poll_url: root + "/jobs/" + j.id,
              }),
            },
          ],
        },
      });
    }
    if (path === "/jobs" && req.method === "POST") {
      const wait = Number(url.searchParams.get("wait") || 0);
      if (!Number.isFinite(wait) || wait < 0 || wait > 55) fail("invalid_wait");
      const b = await body(req),
        j = await this.submit(a, device, b, a.mode === "sealed");
      if (this.sockets().length) {
        const done = await this.wait(j.id, wait * 1000);
        if (done) return this.reply(done);
      }
      return json(
        {
          job_id: j.id,
          status: j.status,
          poll_url: root + "/jobs/" + j.id,
          expires_at: j.until,
        },
        202,
      );
    }
    const match = path.match(/^\/jobs\/([a-f0-9-]{36})$/);
    if (match) {
      const j = await this.ctx.storage.get("j:" + match[1]);
      if (
        !j ||
        j.agent !== a.id ||
        j.generation !== a.generation ||
        (!agents.some((x) => x.token_hash === th) && !j.mcp)
      )
        fail("not_found", 404);
      if (req.method === "DELETE") {
        j.status = "cancelled";
        j.error = "cancelled";
        delete j.payload;
        delete j.result;
        await this.erase(j);
        this.memory.delete(j.id);
        await this.save(j);
        for (const ws of this.sockets())
          ws.send(JSON.stringify({ type: "cancel", id: j.id }));
        return json({ job_id: j.id, status: j.status });
      }
      if (req.method === "GET") {
        if (j.until < Date.now())
          return json({ job_id: j.id, error: "phone_unreachable" }, 504);
        if (j.status === "completed") return this.reply(j);
        if (j.error) return json({ job_id: j.id, error: j.error }, 409);
        return json({ job_id: j.id, status: j.status }, 202);
      }
    }
    fail("not_found", 404);
  }
  async submit(a, device, b, sealed) {
    const prior = this.submission || Promise.resolve();
    let release;
    this.submission = new Promise((r) => {
      release = r;
    });
    await prior;
    try {
      const now = Date.now();
      if (
        [...this.memory.values()].reduce((n, m) => n + (m.size || 0), 0) >=
        16 * 1024 * 1024
      )
        fail("backpressure", 429);
      if (
        !Number.isSafeInteger(b.deadline) ||
        b.deadline <= now ||
        b.deadline > now + TTL
      )
        fail("invalid_deadline");
      if (!this.sockets().length) {
        const woken = await this.wake();
        // Connector content lives only in memory, so it waits for the phone instead of queueing.
        if (!sealed && !(woken && (await this.waitForSocket(10000))))
          fail("phone_unreachable", 503);
      }
      if (sealed ? !b.envelope : !b.request) fail("mode_mismatch");
      const jobs = [
          ...(await this.ctx.storage.list({ prefix: "j:" })).values(),
        ],
        active = jobs.filter(
          (j) =>
            !["completed", "cancelled", "failed"].includes(j.status) &&
            j.until > now,
        );
      if (
        active.length >= 8 ||
        active.filter((j) => j.agent === a.id).length >= 4 ||
        jobs.length >= 3200
      )
        fail("backpressure", 429);
      const day = Math.floor(now / 86400000),
        quota = (await this.ctx.storage.get("quota")) || {},
        q = quota[a.id],
        n = q?.day === day ? q.n : 0;
      if (n >= 200) fail("quota_exceeded", 429);
      quota[a.id] = { day, n: n + 1 };
      await this.ctx.storage.put("quota", quota);
      const seq = ((await this.ctx.storage.get("seq:" + a.id)) || 0) + 1;
      await this.ctx.storage.put("seq:" + a.id, seq);
      const j = {
        mcp: !sealed && b.request?.path === "/relay/mcp",
        seq,
        id: crypto.randomUUID(),
        agent: a.id,
        generation: a.generation,
        mode: a.mode,
        status: "queued",
        until: b.deadline,
        created_at: now,
        device,
      };
      if (sealed) j.payload = b.envelope;
      else this.memory.set(j.id, { request: b.request });
      await this.save(j);
      await this.deliver(j);
      return j;
    } finally {
      release();
    }
  }
  async deliver(j) {
    if (j.status !== "queued" || j.until <= Date.now()) return;
    const ws = this.sockets()[0];
    if (!ws) return;
    const payload =
      j.mode === "sealed"
        ? await this.blob(j, "payload")
        : this.memory.get(j.id)?.request;
    if (!payload) {
      j.status = "failed";
      j.error = "content_lost";
      await this.save(j);
      return;
    }
    j.status = "dispatched";
    delete j.payload;
    await this.erase(j);
    await this.save(j);
    ws.send(
      JSON.stringify({
        type: "job",
        seq: j.seq,
        id: j.id,
        agent_id: j.agent,
        generation: j.generation,
        mode: j.mode,
        device_id: j.device,
        deadline: j.until,
        created_at: j.created_at,
        payload,
      }),
    );
  }
  async webSocketMessage(ws, message) {
    try {
      if (
        typeof message !== "string" ||
        enc.encode(message).length > FRAME + 16384
      )
        fail("frame_too_large");
      const b = JSON.parse(message);
      if (!(await this.ctx.storage.get("enabled"))) {
        ws.close(4003, "disabled");
        return;
      }
      if (b.type === "ping") {
        ws.send(JSON.stringify({ type: "pong", at: Date.now() }));
        return;
      }
      if (b.type === "agents") {
        if (!Array.isArray(b.agents) || b.agents.length > 16)
          fail("invalid_agents");
        const agents = b.agents.map((a) => {
          if (
            !/^[a-zA-Z0-9_-]{8,128}$/.test(a.id) ||
            !["connector", "sealed"].includes(a.mode) ||
            !/^[a-f0-9]{64}$/.test(a.token_hash) ||
            !a.generation
          )
            fail("invalid_agent");
          return {
            id: a.id,
            mode: a.mode,
            token_hash: a.token_hash,
            generation: a.generation,
          };
        });
        await this.ctx.storage.put("agents", agents);
        for (const j of (
          await this.ctx.storage.list({ prefix: "j:" })
        ).values()) {
          if (
            !agents.some(
              (a) => a.id === j.agent && a.generation === j.generation,
            )
          ) {
            j.status = "cancelled";
            j.error = "agent_revoked";
            delete j.payload;
            delete j.result;
            await this.erase(j);
            this.memory.delete(j.id);
            await this.save(j);
            ws.send(JSON.stringify({ type: "cancel", id: j.id }));
          } else await this.deliver(j);
        }
        return;
      }
      if (b.type === "push") {
        if (b.token === null) await this.ctx.storage.delete("push_token");
        else if (push.validToken(b.token))
          await this.ctx.storage.put("push_token", b.token);
        else fail("invalid_push_token");
        return;
      }
      if (b.type === "off") {
        await this.ctx.storage.put("enabled", false);
        await this.ctx.storage.delete("push_token");
        await this.cancelAll("device_disabled");
        ws.close(4003, "disabled");
        return;
      }
      if (!["result", "failure"].includes(b.type)) fail("unknown_frame");
      const j = await this.ctx.storage.get("j:" + b.id);
      if (!j || j.status !== "dispatched" || j.until < Date.now()) return;
      if (b.type === "failure") {
        j.status = "failed";
        j.error = ["backpressure", "invalid_job", "result_too_large"].includes(
          b.error,
        )
          ? b.error
          : "request_failed";
        this.memory.delete(j.id);
        await this.save(j);
        this.waiters.get(j.id)?.(j);
        return;
      }
      const m = this.memory.get(j.id) || {};
      m.chunks ||= [];
      m.size ||= 0;
      m.seq ||= 0;
      if (
        b.seq !== m.seq ||
        typeof b.data !== "string" ||
        enc.encode(b.data).length > FRAME
      )
        fail("invalid_sequence");
      m.size += enc.encode(b.data).length;
      const total =
        [...this.memory.entries()]
          .filter(([id]) => id !== j.id)
          .reduce((n, [, v]) => n + (v.size || 0), 0) + m.size;
      if (total > 16 * 1024 * 1024 || m.size > MAX_RESULT) {
        j.status = "failed";
        j.error =
          total > 16 * 1024 * 1024 ? "result_backpressure" : "result_too_large";
        this.memory.delete(j.id);
        await this.erase(j);
        await this.save(j);
        ws.send(
          JSON.stringify({ type: "ack", id: j.id, seq: b.seq, error: j.error }),
        );
        this.waiters.get(j.id)?.(j);
        return;
      }
      m.chunks.push(b.data);
      m.seq++;
      this.memory.set(j.id, m);
      ws.send(JSON.stringify({ type: "ack", id: j.id, seq: b.seq }));
      if (b.final) {
        const result = m.chunks.join("");
        JSON.parse(result);
        j.status = "completed";
        if (j.mode === "sealed") {
          j.result = result;
          this.memory.delete(j.id);
        } else {
          delete m.chunks;
          delete m.request;
          m.result = result;
        }
        await this.save(j);
        this.waiters.get(j.id)?.(j);
      }
    } catch {
      ws.close(4002, "invalid_frame");
    }
  }
  async reply(j) {
    if (j.error) return json({ job_id: j.id, error: j.error }, 409);
    const text =
      (await this.blob(j, "result")) || this.memory.get(j.id)?.result;
    if (!text) return json({ job_id: j.id, error: "content_lost" }, 410);
    const r = JSON.parse(text);
    return j.mode === "sealed"
      ? json({ job_id: j.id, envelope: r })
      : r.mcp
        ? json(r.mcp, r.status || 200)
        : json({ job_id: j.id, response: r }, r.status >= 400 ? r.status : 200);
  }
  wait(id, ms) {
    if (ms <= 0) return null;
    return new Promise((resolve) => {
      const t = setTimeout(() => {
        this.waiters.delete(id);
        resolve(null);
      }, ms);
      this.waiters.set(id, (j) => {
        clearTimeout(t);
        this.waiters.delete(id);
        resolve(j);
      });
    });
  }
  async cancelAll(reason) {
    for (const j of (await this.ctx.storage.list({ prefix: "j:" })).values()) {
      j.status = "cancelled";
      j.error = reason;
      delete j.payload;
      delete j.result;
      await this.erase(j);
      await this.save(j);
    }
    this.memory.clear();
  }
  async alarm() {
    const now = Date.now();
    let pending = false;
    for (const [k, v] of await this.ctx.storage.list()) {
      if (k.startsWith("b:") && v.until <= now)
        await this.ctx.storage.delete(k);
      else if (k.startsWith("j:") && v.until <= now) {
        await this.ctx.storage.delete(k);
        this.memory.delete(v.id);
      } else if (
        (["code:", "token:", "session:", "challenge:", "proof:"].some((p) =>
          k.startsWith(p),
        ) ||
          ["challenge", "enrollment"].includes(k)) &&
        v.until <= now
      )
        await this.ctx.storage.delete(k);
      else if (
        k.startsWith("b:") ||
        k.startsWith("j:") ||
        ["code:", "token:", "session:", "challenge:", "proof:"].some((p) =>
          k.startsWith(p),
        ) ||
        ["challenge", "enrollment"].includes(k)
      )
        pending = true;
    }
    if (pending) await this.schedule();
  }
  async webSocketClose(ws, code, reason) {
    ws.close(code === 1006 ? 1011 : code, reason);
  }
  async webSocketError(ws) {
    ws.close(1011, "transport_error");
  }
}
