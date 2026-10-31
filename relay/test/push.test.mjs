import test from "node:test";
import assert from "node:assert/strict";
import { decodeJwt, decodeProtectedHeader } from "jose";
import * as push from "../src/push.mjs";

async function account() {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const der = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const pem = `-----BEGIN PRIVATE KEY-----\n${der.match(/.{1,64}/g).join("\n")}\n-----END PRIVATE KEY-----\n`;
  return { project_id: "bridgit-test", client_email: "relay@bridgit-test.iam.gserviceaccount.com", private_key: pem };
}

test("FCM wake: content-free high-priority message, cached OAuth token, unregistered tokens dropped", async () => {
  const a = await account(), calls = [];
  let status = 200;
  const fetcher = async (url, init) => {
    calls.push({ url, init });
    if (url.startsWith("https://oauth2.googleapis.com/")) {
      const assertion = new URLSearchParams(init.body).get("assertion");
      assert.equal(decodeProtectedHeader(assertion).alg, "RS256");
      const claims = decodeJwt(assertion);
      assert.equal(claims.iss, a.client_email);
      assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
      return Response.json({ access_token: "ya29.test", expires_in: 3600 });
    }
    return new Response("{}", { status });
  };
  const cache = {}, token = "fcm-token_ABCDEFGHIJ:1234567890";
  assert.equal(await push.wake(a, cache, token, fetcher), "sent");
  assert.equal(await push.wake(a, cache, token, fetcher), "sent");
  assert.equal(calls.filter((c) => c.url.includes("oauth2")).length, 1, "access token is reused");
  const send = calls.find((c) => c.url.includes("fcm.googleapis.com"));
  assert.equal(send.url, "https://fcm.googleapis.com/v1/projects/bridgit-test/messages:send");
  assert.equal(send.init.headers.Authorization, "Bearer ya29.test");
  assert.deepEqual(JSON.parse(send.init.body), {
    message: { token, data: { type: "relay_wake" }, android: { priority: "HIGH", ttl: "30s" } },
  });
  status = 404;
  assert.equal(await push.wake(a, cache, token, fetcher), "unregistered");
  status = 500;
  assert.equal(await push.wake(a, cache, token, fetcher), "failed");
});

test("FCM config and token validation", () => {
  assert.equal(push.serviceAccount(undefined), null);
  assert.throws(() => push.serviceAccount('{"project_id":"x"}'));
  assert.ok(push.validToken("abcDEF123_-:abcdefghij"));
  assert.ok(!push.validToken("short"));
  assert.ok(!push.validToken("has space in it 1234567"));
  assert.ok(!push.validToken(42));
});
