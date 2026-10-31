#!/usr/bin/env node
import fs from "node:fs/promises";
import { parse } from "jsonc-parser";
const file = process.argv[2] || "wrangler.jsonc";
const parseErrors = [];
const c = parse(await fs.readFile(file, "utf8"), parseErrors, {
    allowTrailingComma: true,
  }),
  v = c.vars || {};
const errors = parseErrors.length ? ["Invalid JSONC configuration"] : [];
const requireValue = (ok, message) => {
  if (!ok) errors.push(message);
};
requireValue(
  c.account_id === "3d2c7c0c8130fa935e40001315fab2d7",
  "Use the Bridgit Cloudflare account",
);
let origin;
try {
  const u = new URL(v.PUBLIC_ORIGIN);
  if (
    u.protocol === "https:" &&
    u.origin === v.PUBLIC_ORIGIN &&
    !u.hostname.endsWith(".invalid")
  )
    origin = u.origin;
} catch {}
requireValue(origin, "Set a reviewed canonical HTTPS PUBLIC_ORIGIN");
requireValue(
  /^[a-z0-9-]+$/.test(v.ACCESS_TEAM || ""),
  "Set the owner Cloudflare Access team slug",
);
requireValue(
  /^[a-f0-9]{64}$/.test(v.ACCESS_AUD || ""),
  "Set the existing owner-only Access application audience",
);
requireValue(
  c.workers_dev === false && c.preview_urls === false,
  "Disable alternate workers.dev and preview hostnames",
);
try {
  for (const [id, client] of Object.entries(
    JSON.parse(v.OAUTH_CLIENTS || "{}"),
  )) {
    requireValue(
      id.length > 0 && /^[A-Za-z0-9_-]{8,128}$/.test(client.agent_id),
      "Bind every OAuth client to one phone agent",
    );
    requireValue(
      Array.isArray(client.redirect_uris) && client.redirect_uris.length > 0,
      "Register exact hosted-client redirect URIs",
    );
    for (const raw of client.redirect_uris || []) {
      const u = new URL(raw);
      requireValue(
        u.protocol === "https:" && !u.hash && !u.username && !u.password,
        "Redirects must be exact HTTPS URLs with no credentials or fragment",
      );
    }
  }
} catch {
  errors.push("OAUTH_CLIENTS must be a JSON map of preregistered clients");
}
try {
  for (const raw of JSON.parse(v.ALLOWED_ORIGINS || "[]")) {
    const u = new URL(raw);
    requireValue(
      u.protocol === "https:" && u.origin === raw,
      "ALLOWED_ORIGINS must contain exact HTTPS origins",
    );
  }
} catch {
  errors.push("ALLOWED_ORIGINS must be a JSON list");
}
requireValue(
  Array.isArray(c.routes) &&
    c.routes.some(
      (r) => r.custom_domain === true && "https://" + r.pattern === origin,
    ),
  "Configure the reviewed custom hostname route",
);
if (errors.length) {
  console.error(errors.map((x) => "- " + x).join("\n"));
  process.exitCode = 1;
} else console.log("Production configuration validated; nothing deployed.");
