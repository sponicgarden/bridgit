import { test } from "node:test";
import assert from "node:assert/strict";
import { Miniflare } from "miniflare";
import { webcrypto } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { build } from "esbuild";
const origin = "https://relay.test",
  device = "phone0001",
  base = origin + "/d/" + device;
const b64 = (b) => Buffer.from(b).toString("base64"),
  hash = async (s) =>
    Buffer.from(
      await webcrypto.subtle.digest("SHA-256", Buffer.from(s)),
    ).toString("hex");
const key = await webcrypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"],
);
const identity = b64(await webcrypto.subtle.exportKey("spki", key.publicKey));
const { suite, keys, seal, open, aad } = await import("../src/sealed.mjs");
const phone = await keys(),
  agent = await keys();
const result = await build({
  entryPoints: ["test/fixture.mjs"],
  bundle: true,
  format: "esm",
  write: false,
  platform: "browser",
});
function create(persist) {
  return new Miniflare({
    modules: true,
    script: result.outputFiles[0].text,
    compatibilityDate: "2026-07-30",
    durableObjects: { PHONES: { className: "PhoneRelay", useSQLite: true } },
    durableObjectsPersist: persist,
    bindings: {
      PUBLIC_ORIGIN: origin,
      ACCESS_TEAM: "",
      ACCESS_AUD: "",
      ALLOWED_ORIGINS: "[]",
      OAUTH_CLIENTS: JSON.stringify({
        hosted: {
          redirect_uris: ["https://client.test/callback"],
          agent_id: "agent001",
        },
      }),
    },
  });
}
async function req(mf, p, method = "GET", body, token) {
  return mf.dispatchFetch(p.startsWith("http") ? p : base + p, {
    method,
    redirect: "manual",
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token
        ? {
            Authorization:
              token === "fixture-admin" ? token : "Bearer " + token,
          }
        : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
}
async function connect(mf) {
  const nonce = crypto.randomUUID(),
    at = Date.now();
  const proof = {
    nonce,
    at,
    signature: b64(
      await webcrypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        key.privateKey,
        Buffer.from(`bridgit-relay-challenge-v1\n${device}\n${nonce}\n${at}`),
      ),
    ),
  };
  const c = await (await req(mf, "/challenge", "POST", proof)).json();
  const sig = b64(
    await webcrypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key.privateKey,
      Buffer.from(`bridgit-relay-v1\n${device}\n${c.nonce}\n${c.until}`),
    ),
  );
  const r = await mf.dispatchFetch(base + "/socket", {
    headers: {
      Upgrade: "websocket",
      "X-Relay-Nonce": c.nonce,
      "X-Relay-Signature": sig,
    },
  });
  assert.equal(r.status, 101);
  const ws = r.webSocket;
  ws.accept();
  const inbox = [];
  ws.addEventListener("message", (e) => inbox.push(JSON.parse(e.data)));
  return { ws, inbox, c, sig };
}
async function next(inbox, type) {
  const until = Date.now() + 3000;
  while (Date.now() < until) {
    const i = inbox.findIndex((b) => b.type === type);
    if (i >= 0) return inbox.splice(i, 1)[0];
    await new Promise((r) => setTimeout(r, 10));
  }
  throw Error("No " + type);
}
const conf = {
  device_id: device,
  agent_id: "agent001",
  generation: "gen1",
  phone_hpke_public: phone.public,
};
test("HPKE Auth: pinned sender/recipient, response binding, tampering and cross-device isolation", async () => {
  const deadline = Date.now() + 50000,
    e = await seal(
      conf,
      agent,
      { method: "GET", path: "/v1/health" },
      "request01",
      1,
      deadline,
    );
  const p = await suite.createRecipientContext({
    recipientKey: {
      publicKey: await suite.kem.deserializePublicKey(
        Buffer.from(phone.public, "base64"),
      ),
      privateKey: await suite.kem.deserializePrivateKey(
        Buffer.from(phone.private, "base64"),
      ),
    },
    senderPublicKey: await suite.kem.deserializePublicKey(
      Buffer.from(agent.public, "base64"),
    ),
    enc: Buffer.from(e.enc, "base64"),
    info: Buffer.from("bridgit-relay-v1"),
  });
  const plain = await p.open(
    Buffer.from(e.ct, "base64"),
    aad(conf, e.request_id, deadline, 1, "request"),
  );
  assert.equal(
    JSON.parse(new TextDecoder().decode(plain)).request.path,
    "/v1/health",
  );
  await assert.rejects(() =>
    p.open(
      Buffer.from(e.ct, "base64"),
      aad(
        { ...conf, device_id: "wrong" },
        e.request_id,
        deadline,
        1,
        "request",
      ),
    ),
  );
  const sender = await suite.createSenderContext({
    recipientPublicKey: await suite.kem.deserializePublicKey(
      Buffer.from(agent.public, "base64"),
    ),
    senderKey: {
      publicKey: await suite.kem.deserializePublicKey(
        Buffer.from(phone.public, "base64"),
      ),
      privateKey: await suite.kem.deserializePrivateKey(
        Buffer.from(phone.private, "base64"),
      ),
    },
    info: Buffer.from("bridgit-relay-v1"),
  });
  const r = {
    request_id: e.request_id,
    seq: 1,
    deadline,
    enc: b64(sender.enc),
    ct: b64(
      await sender.seal(
        Buffer.from('{"ok":true}'),
        aad(conf, e.request_id, deadline, 1, "response"),
      ),
    ),
  };
  assert.deepEqual(await open(conf, agent, r, e), { ok: true });
  await assert.rejects(() =>
    open({ ...conf, phone_hpke_public: awaitKeys.public }, agent, r, e),
  );
});
const awaitKeys = await keys();
test("real Worker/SQLite/WebSocket: enrolment, scoped jobs, replay, restart, limits and MCP auth", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "bridgit-relay-test-"));
  let mf = create(dir);
  try {
    assert.equal(
      (
        await req(mf, "/admin/enrollment", "POST", {
          identity_spki: identity,
          hpke_public: phone.public,
        })
      ).status,
      503,
    );
    const e = await (
      await req(
        mf,
        "/admin/enrollment",
        "POST",
        { identity_spki: identity, hpke_public: phone.public },
        "fixture-admin",
      )
    ).json();
    const enroll = {
      token: e.enrollment_token,
      identity_spki: identity,
      hpke_public: phone.public,
    };
    assert.equal((await req(mf, "/enroll", "POST", enroll)).status, 200);
    assert.equal((await req(mf, "/enroll", "POST", enroll)).status, 401);
    assert.equal((await req(mf, "/challenge", "POST", {})).status, 401);
    let { ws, inbox, c, sig } = await connect(mf);
    assert.equal(
      (
        await mf.dispatchFetch(base + "/socket", {
          headers: {
            Upgrade: "websocket",
            "X-Relay-Nonce": c.nonce,
            "X-Relay-Signature": sig,
          },
        })
      ).status,
      401,
    );
    const configs = [
      {
        id: "agent001",
        generation: "gen1",
        mode: "connector",
        token_hash: await hash("token1"),
      },
      {
        id: "agent002",
        generation: "gen2",
        mode: "sealed",
        token_hash: await hash("token2"),
      },
    ];
    ws.send(JSON.stringify({ type: "agents", agents: configs }));
    ws.send('{"type":"ping"}');
    await next(inbox, "pong");
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          { deadline: Date.now() + 10000, request: {} },
          "wrong",
        )
      ).status,
      401,
    );
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          { deadline: Date.now() - 1, request: {} },
          "token1",
        )
      ).status,
      400,
    );
    const response = await req(
      mf,
      "/jobs",
      "POST",
      {
        deadline: Date.now() + 20000,
        request: { method: "GET", path: "/v1/health" },
      },
      "token1",
    );
    assert.equal(response.status, 202);
    const j = await response.json(),
      job = await next(inbox, "job");
    assert.equal(job.agent_id, "agent001");
    assert.equal(job.seq, 1);
    assert.equal(
      (await req(mf, "/jobs/" + j.job_id, "GET", undefined, "token2")).status,
      404,
    );
    ws.send(
      JSON.stringify({
        type: "result",
        id: job.id,
        seq: 0,
        data: '{"status":200,"body":{"ok":true}}',
        final: true,
      }),
    );
    await next(inbox, "ack");
    assert.equal(
      (
        await (
          await req(mf, "/jobs/" + j.job_id, "GET", undefined, "token1")
        ).json()
      ).response.body.ok,
      true,
    );
    const init = await mf.dispatchFetch(base + "/mcp", {
      method: "POST",
      headers: {
        Authorization: "Bearer token1",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-11-25" },
      }),
    });
    assert.equal(init.status, 200);
    const sid = init.headers.get("Mcp-Session-Id");
    assert.ok(sid);
    assert.equal(
      (
        await mf.dispatchFetch(base + "/mcp", {
          method: "POST",
          headers: {
            Authorization: "Bearer token2",
            "Mcp-Session-Id": sid,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "ping" }),
        })
      ).status,
      403,
    );
    assert.equal(
      (
        await mf.dispatchFetch(base + "/mcp", {
          method: "POST",
          headers: {
            Origin: "https://evil.test",
            Authorization: "Bearer token1",
            "Content-Type": "application/json",
          },
          body: "{}",
        })
      ).status,
      403,
    );
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          {
            deadline: Date.now() + 10000,
            request: { body: "x".repeat(2 * 1024 * 1024) },
          },
          "token1",
        )
      ).status,
      413,
    );
    const queued = await (
      await req(
        mf,
        "/jobs",
        "POST",
        { deadline: Date.now() + 30000, envelope: { enc: "ciphertext" } },
        "token2",
      )
    ).json();
    await next(inbox, "job");
    ws.send(JSON.stringify({ type: "agents", agents: [configs[0]] }));
    ws.send('{"type":"ping"}');
    await next(inbox, "pong");
    assert.equal(
      (await req(mf, "/jobs/" + queued.job_id, "GET", undefined, "token2"))
        .status,
      401,
    );
    ws.close();
    await mf.dispose();
    mf = create(dir);
    // Connector plaintext results are deliberately lost on eviction; token and completion metadata persist.
    assert.equal(
      (await req(mf, "/jobs/" + j.job_id, "GET", undefined, "token1")).status,
      410,
    );
    ({ ws, inbox } = await connect(mf));
    ws.send(JSON.stringify({ type: "agents", agents: configs }));
    ws.send('{"type":"ping"}');
    await next(inbox, "pong");
    const retry = await req(
      mf,
      "/jobs",
      "POST",
      { deadline: Date.now() + 10000, request: {} },
      "token1",
    );
    assert.equal(retry.status, 202);
    assert.equal((await next(inbox, "job")).seq, 2);

    // PKCE S256, exact client/redirect/resource binding, one-time codes and token audience.
    const verifier = "v".repeat(43),
      challenge = Buffer.from(
        await webcrypto.subtle.digest("SHA-256", Buffer.from(verifier)),
      ).toString("base64url");
    const authorize = new URL(base + "/authorize");
    for (const [k, v] of Object.entries({
      client_id: "hosted",
      redirect_uri: "https://client.test/callback",
      resource: base + "/mcp",
      response_type: "code",
      code_challenge_method: "S256",
      code_challenge: challenge,
      state: "owner-state",
    }))
      authorize.searchParams.set(k, v);
    const auth = await req(
      mf,
      authorize.toString(),
      "GET",
      undefined,
      "fixture-admin",
    );
    assert.equal(auth.status, 302);
    const location = new URL(auth.headers.get("Location"));
    assert.equal(location.searchParams.get("state"), "owner-state");
    const tokenBody = {
      grant_type: "authorization_code",
      code: location.searchParams.get("code"),
      client_id: "hosted",
      redirect_uri: "https://client.test/callback",
      resource: base + "/mcp",
      code_verifier: verifier,
    };
    assert.equal(
      (
        await req(mf, "/token", "POST", {
          ...tokenBody,
          resource: "https://wrong.test/mcp",
        })
      ).status,
      400,
    );
    const tokenResponse = await mf.dispatchFetch(base + "/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(tokenBody).toString(),
    });
    assert.equal(tokenResponse.status, 200);
    const oauth = (await tokenResponse.json()).access_token;
    assert.equal((await req(mf, "/token", "POST", tokenBody)).status, 400);
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          { deadline: Date.now() + 10000, request: {} },
          oauth,
        )
      ).status,
      401,
    );
    const oauthInit = await req(
      mf,
      "/mcp",
      "POST",
      {
        jsonrpc: "2.0",
        id: 11,
        method: "initialize",
        params: { protocolVersion: "2025-11-25" },
      },
      oauth,
    );
    assert.equal(oauthInit.status, 200);
    assert.equal(
      (
        await req(
          mf,
          "https://wrong.test/d/" + device + "/jobs",
          "POST",
          { deadline: Date.now() + 10000, request: {} },
          "token1",
        )
      ).status,
      403,
    );
    const before = await (
      await req(mf, "/__fixture_meta", "GET", undefined, "fixture-admin")
    ).json();
    await req(
      mf,
      "/mcp",
      "POST",
      {
        jsonrpc: "2.0",
        id: 12,
        method: "initialize",
        params: { protocolVersion: "2025-11-25" },
      },
      "token1",
    );
    const after = await (
      await req(mf, "/__fixture_meta", "GET", undefined, "fixture-admin")
    ).json();
    assert.ok(after.alarm <= before.alarm, "traffic must not postpone cleanup");
    // Per-job failure leaves the shared phone socket alive. Cancellation is agent bound.
    ws.send(
      JSON.stringify({
        type: "failure",
        id: (await retry.clone().json()).job_id,
        error: "invalid_job",
      }),
    );
    ws.send('{"type":"ping"}');
    await next(inbox, "pong");
    ws.close();
    await new Promise((r) => setTimeout(r, 30));
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          { deadline: Date.now() + 10000, request: {} },
          "token1",
        )
      ).status,
      503,
    );
    const offline = await (
      await req(
        mf,
        "/jobs",
        "POST",
        { deadline: Date.now() + 10000, envelope: { enc: "x".repeat(150000) } },
        "token2",
      )
    ).json();
    assert.ok(offline.job_id);
    const expired = await (
      await req(
        mf,
        "/jobs",
        "POST",
        { deadline: Date.now() + 50, envelope: { enc: "synthetic" } },
        "token2",
      )
    ).json();
    await new Promise((r) => setTimeout(r, 80));
    assert.equal(
      (await req(mf, "/jobs/" + expired.job_id, "GET", undefined, "token2"))
        .status,
      504,
    );
    await req(mf, "/__fixture_alarm", "POST", {}, "fixture-admin");
    assert.equal(
      (await req(mf, "/jobs/" + expired.job_id, "GET", undefined, "token2"))
        .status,
      404,
    );
    ({ ws, inbox } = await connect(mf));
    ws.send(JSON.stringify({ type: "agents", agents: configs }));
    const delivery = await next(inbox, "job");
    assert.equal(delivery.id, offline.job_id);
    assert.equal(delivery.payload.enc.length, 150000);
    const resultText = JSON.stringify({
      enc: "encrypted-fixture",
      ct: "z".repeat(2300000),
    });
    let seq = 0;
    for (let i = 0; i < resultText.length; i += 65536) {
      ws.send(
        JSON.stringify({
          type: "result",
          id: delivery.id,
          seq,
          data: resultText.slice(i, i + 65536),
          final: i + 65536 >= resultText.length,
        }),
      );
      await next(inbox, "ack");
      seq++;
    }
    assert.equal(
      (
        await (
          await req(mf, "/jobs/" + offline.job_id, "GET", undefined, "token2")
        ).json()
      ).envelope.ct.length,
      2300000,
    );
    assert.equal(
      (await req(mf, "/jobs/" + offline.job_id, "DELETE", undefined, "token1"))
        .status,
      404,
    );
    assert.equal(
      (await req(mf, "/jobs/" + offline.job_id, "DELETE", undefined, "token2"))
        .status,
      200,
    );
    const mcpCall = mf.dispatchFetch(base + "/mcp", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + oauth,
        "Mcp-Session-Id": oauthInit.headers.get("Mcp-Session-Id"),
        "MCP-Protocol-Version": "2025-11-25",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ jsonrpc: "2.0", id: 50, method: "tools/list" }),
    });
    const mcpJob = await next(inbox, "job");
    assert.equal(mcpJob.payload.path, "/relay/mcp");
    ws.send(
      JSON.stringify({
        type: "result",
        id: mcpJob.id,
        seq: 0,
        data: JSON.stringify({
          status: 200,
          mcp: { jsonrpc: "2.0", id: 50, result: { tools: [] } },
        }),
        final: true,
      }),
    );
    await next(inbox, "ack");
    const mcpReply = await mcpCall;
    assert.equal(mcpReply.status, 200);
    assert.deepEqual((await mcpReply.json()).result.tools, []);
    // Hitting retained-result budget must fail one job without disconnecting other agents.
    for (let k = 0; k < 3; k++) {
      const submitted = await req(
        mf,
        "/jobs",
        "POST",
        {
          deadline: Date.now() + 30000,
          request: { method: "GET", path: "/v1/health" },
        },
        "token1",
      );
      assert.equal(submitted.status, 202);
      const id = (await submitted.json()).job_id;
      await next(inbox, "job");
      const data = JSON.stringify({
        status: 200,
        body: { data: "m".repeat(6 * 1024 * 1024) },
      });
      let failed = false,
        seq = 0;
      for (let i = 0; i < data.length; i += 65536) {
        ws.send(
          JSON.stringify({
            type: "result",
            id,
            seq,
            data: data.slice(i, i + 65536),
            final: i + 65536 >= data.length,
          }),
        );
        const ack = await next(inbox, "ack");
        if (ack.error) {
          failed = true;
          break;
        }
        seq++;
      }
      assert.equal(failed, k === 2);
      assert.equal(
        (await req(mf, "/jobs/" + id, "GET", undefined, "token1")).status,
        k === 2 ? 409 : 200,
      );
    }
    ws.send('{"type":"ping"}');
    await next(inbox, "pong");
    // A full per-agent initialize budget does not block existing sessions.
    let capped = false;
    for (let i = 0; i < 10; i++) {
      const r = await req(
        mf,
        "/mcp",
        "POST",
        {
          jsonrpc: "2.0",
          id: 100 + i,
          method: "initialize",
          params: { protocolVersion: "2025-11-25" },
        },
        "token1",
      );
      if (r.status === 429) {
        capped = true;
        break;
      }
      assert.equal(r.status, 200);
    }
    assert.ok(capped);
    assert.equal(
      (
        await mf.dispatchFetch(base + "/mcp", {
          method: "POST",
          headers: {
            Authorization: "Bearer token1",
            "Mcp-Session-Id": sid,
            "MCP-Protocol-Version": "2025-11-25",
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ jsonrpc: "2.0", id: 200, method: "ping" }),
        })
      ).status,
      200,
    );
    assert.equal(
      (await req(mf, "/admin/disable", "POST", {}, "fixture-admin")).status,
      200,
    );
    assert.equal(
      (
        await req(
          mf,
          "/jobs",
          "POST",
          { deadline: Date.now() + 10000, request: {} },
          "token1",
        )
      ).status,
      403,
    );
  } finally {
    await mf.dispose();
    await rm(dir, { recursive: true, force: true });
  }
});
