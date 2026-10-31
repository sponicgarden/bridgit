// Feature-use tallies from the Android app (app/src/main/java/cc/bridgit/app/core/Usage.kt).
// A batch is {batch, app_version, counts}: a one-off nonce, the version code and how often each feature was used.
// Nothing in it identifies a phone or person; the server keeps only per-day totals and never the network address.
import { json, privateHash, limitedText } from './email.mjs';
const day = 86400000;
export const MAX_FEATURES = 300;
export const MAX_COUNT = 100000;
export const BATCHES_PER_ADDRESS = 200;
// Same rule as Usage.KEY in the app: a kind, then a feature name built from fixed identifiers.
const featurePattern = /^[a-z]+:[A-Za-z0-9 /_{}.:-]{1,120}$/;
const batchPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export async function cleanupUsage(env, now = Date.now()) {
 await env.SIGNUPS.batch([
  env.SIGNUPS.prepare('DELETE FROM usage_rate_limits WHERE created_at < ?').bind(now - day),
  env.SIGNUPS.prepare('DELETE FROM usage_batches WHERE created_at < ?').bind(now - 7 * day)
 ]);
}

/** The batch if it is exactly the shape the app sends, else null. Unknown fields are refused, not ignored. */
export function parseBatch(data) {
 if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
 if (Object.keys(data).sort().join() !== 'app_version,batch,counts') return null;
 if (typeof data.batch !== 'string' || !batchPattern.test(data.batch)) return null;
 if (!Number.isInteger(data.app_version) || data.app_version < 1 || data.app_version > 1e9) return null;
 const c = data.counts;
 if (!c || typeof c !== 'object' || Array.isArray(c)) return null;
 const entries = Object.entries(c);
 if (!entries.length || entries.length > MAX_FEATURES) return null;
 for (const [feature, n] of entries) if (!featurePattern.test(feature) || !Number.isInteger(n) || n < 1 || n > MAX_COUNT) return null;
 return { batch: data.batch, appVersion: data.app_version, counts: entries };
}

export async function handleUsage(request, env, dependencies = {}) {
 const url = new URL(request.url), now = dependencies.now?.() ?? Date.now();
 if (url.pathname !== '/api/usage') return null;
 if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
 if (!env.SIGNUPS || !env.RATE_LIMIT_SALT) return json({ error: 'Temporarily unavailable.', code: 'unavailable' }, 503);
 if (!request.headers.get('Content-Type')?.startsWith('application/json')) return json({ error: 'JSON required.' }, 415);
 let tally;
 try { tally = parseBatch(JSON.parse(await limitedText(request, 32768))); }
 catch (e) { return e.message === 'body_too_large' ? json({ error: 'Request too large.' }, 413) : json({ error: 'Invalid request.' }, 400); }
 if (!tally) return json({ error: 'Invalid tally batch.', code: 'bad_batch' }, 400);

 const today = Math.floor(now / day);
 const ipHash = await privateHash(env.RATE_LIMIT_SALT, `usage:${today}:${request.headers.get('CF-Connecting-IP') || 'unknown'}`);
 const reserved = await env.SIGNUPS.prepare(`INSERT INTO usage_rate_limits (id,ip_hash,created_at) SELECT ?,?,?
  WHERE (SELECT COUNT(*) FROM usage_rate_limits WHERE ip_hash = ? AND created_at > ?) < ?`)
  .bind(crypto.randomUUID(), ipHash, now, ipHash, now - day, BATCHES_PER_ADDRESS).run();
 if (!reserved.meta.changes) return json({ error: 'Too many batches.', code: 'rate_limited' }, 429);
 // A retried batch (same nonce) is acknowledged but not counted again.
 const fresh = await env.SIGNUPS.prepare('INSERT OR IGNORE INTO usage_batches (id,created_at) VALUES (?,?)').bind(tally.batch, now).run();
 if (!fresh.meta.changes) return json({ accepted: true, duplicate: true });
 await env.SIGNUPS.batch(tally.counts.map(([feature, n]) => env.SIGNUPS.prepare(`INSERT INTO usage_counts (day,app_version,feature,count) VALUES (?,?,?,?)
  ON CONFLICT(day,app_version,feature) DO UPDATE SET count = count + excluded.count`).bind(today, tally.appVersion, feature, n)));
 return json({ accepted: true });
}
