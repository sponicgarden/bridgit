import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { handleUsage, cleanupUsage, parseBatch, BATCHES_PER_ADDRESS } from '../usage.mjs';
const schema = readFileSync(new URL('../migrations/0003_usage_tallies.sql', import.meta.url), 'utf8');
function setup() {
 const sql = new DatabaseSync(':memory:'); sql.exec(schema);
 const DB = { prepare(query) { let values=[]; return { bind(...v) { values=v; return this; }, async first() { return sql.prepare(query).get(...values) || null; }, async run() { const result=sql.prepare(query).run(...values); return {meta:{changes:Number(result.changes)}}; } }; }, async batch(statements) { return Promise.all(statements.map(s=>s.run())); } };
 return { sql, env: { SIGNUPS: DB, RATE_LIMIT_SALT: 'private-test-salt' } };
}
const batchId = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const post = (body, ip='192.0.2.9') => new Request('https://bridgit.cc/api/usage', { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip }, body: JSON.stringify(body) });
const good = (n=1, counts={ 'api:GET /v1/status:ok': 3, 'screen:home': 2 }) => ({ batch: batchId(n), app_version: 42, counts });

test('a batch adds to per-day totals; a retried batch is not counted twice', async () => {
 const s = setup(), now = Date.now();
 assert.equal((await handleUsage(post(good(1)), s.env, { now: () => now })).status, 200);
 assert.equal((await (await handleUsage(post(good(1)), s.env, { now: () => now })).json()).duplicate, true);
 assert.equal((await handleUsage(post(good(2, { 'api:GET /v1/status:ok': 4 })), s.env, { now: () => now })).status, 200);
 const rows = s.sql.prepare('SELECT feature,count,app_version,day FROM usage_counts ORDER BY feature').all();
 assert.deepEqual(rows.map(r => [r.feature, r.count]), [['api:GET /v1/status:ok', 7], ['screen:home', 2]]);
 assert.equal(rows[0].day, Math.floor(now / 86400000));
});

test('nothing that could identify a phone or person is stored', async () => {
 const s = setup(), now = Date.now();
 await handleUsage(post(good(1), '198.51.100.77'), s.env, { now: () => now });
 const dump = JSON.stringify([...['usage_counts', 'usage_batches', 'usage_rate_limits'].map(t => s.sql.prepare(`SELECT * FROM ${t}`).all())]);
 assert.doesNotMatch(dump, /198\.51\.100\.77/);
 assert.deepEqual(Object.keys(s.sql.prepare('SELECT * FROM usage_counts').get()).sort(), ['app_version', 'count', 'day', 'feature']);
 await cleanupUsage(s.env, now + 8 * 86400000);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM usage_batches').get().n, 0);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM usage_rate_limits').get().n, 0);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM usage_counts').get().n, 2, 'totals are kept');
});

test('anything but the exact tally shape is refused', async () => {
 const bad = [
  { ...good(), install: 'a'.repeat(64) },
  { ...good(), email: 'owner@example.com' },
  { batch: 'not-a-uuid', app_version: 42, counts: { 'screen:home': 1 } },
  good(1, {}),
  good(1, { 'api:GET /v1/files?path=/sdcard/me.jpg:ok': 1 }),
  good(1, { 'screen:home\nowner@example.com': 1 }),
  good(1, { 'screen:home': 1.5 }),
  good(1, { 'screen:home': -1 }),
  good(1, { 'screen:home': { nested: true } }),
  good(1, Object.fromEntries(Array.from({ length: 301 }, (_, i) => [`screen:s${i}`, 1]))),
 ];
 for (const b of bad) assert.equal(parseBatch(b), null, JSON.stringify(b).slice(0, 80));
 const s = setup();
 assert.equal((await handleUsage(post(bad[0]), s.env)).status, 400);
 assert.equal((await handleUsage(new Request('https://bridgit.cc/api/usage'), s.env)).status, 405);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM usage_counts').get().n, 0);
});

test('one network address is capped per day', async () => {
 const s = setup(), now = Date.now();
 for (let i = 0; i < BATCHES_PER_ADDRESS; i++) assert.equal((await handleUsage(post(good(i + 1)), s.env, { now: () => now })).status, 200);
 assert.equal((await handleUsage(post(good(9999)), s.env, { now: () => now })).status, 429);
 assert.equal((await handleUsage(post(good(9999), '192.0.2.10'), s.env, { now: () => now })).status, 200);
});
