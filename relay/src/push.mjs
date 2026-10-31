import { SignJWT, importPKCS8 } from "jose";

// FCM HTTP v1. The message is a content-free wake-up: Google sees the push
// token, the time and the fixed type, never job content or the agent.
export const WAKE = { type: "relay_wake" };
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

export function serviceAccount(raw) {
  if (!raw) return null;
  const a = JSON.parse(raw);
  if (!a.project_id || !a.client_email || !a.private_key)
    throw new Error("FCM service account is incomplete");
  return a;
}

export async function accessToken(account, cache, fetcher = fetch) {
  const now = Math.floor(Date.now() / 1000);
  if (cache.token && cache.expires - 60 > now) return cache.token;
  const assertion = await new SignJWT({ scope: SCOPE })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(account.client_email)
    .setAudience(account.token_uri || "https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(await importPKCS8(account.private_key, "RS256"));
  const r = await fetcher(account.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!r.ok) throw new Error("FCM token exchange failed: " + r.status);
  const b = await r.json();
  cache.token = b.access_token;
  cache.expires = now + (b.expires_in || 3600);
  return cache.token;
}

/** Returns "sent", "unregistered" (drop the token) or "failed". */
export async function wake(account, cache, token, fetcher = fetch) {
  const r = await fetcher(
    `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: "Bearer " + (await accessToken(account, cache, fetcher)),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data: WAKE,
          android: { priority: "HIGH", ttl: "30s" },
        },
      }),
    },
  );
  if (r.ok) return "sent";
  return r.status === 404 ? "unregistered" : "failed";
}

export const validToken = (t) =>
  typeof t === "string" && t.length <= 4096 && /^[A-Za-z0-9:_-]{16,}$/.test(t);
