// Agent-activity email alerts for the Android app (Settings > Email alerts).
// The app makes a random install token, keeps it in the Android Keystore and sends it as a Bearer token.
// Mail goes out through Cloudflare Email Sending. The server stores only its hash, mails only the address that install confirmed, and caps sends per day.
import { escape, json, randomToken, hash, privateHash, page, limitedText, sendMail } from './email.mjs';
const origin = 'https://bridgit.cc';
const day = 86400000;
const tokenPattern = /^[a-f0-9]{64}$/;
const emailPattern = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/;
export const ALERT_DAILY_CAP = 200;
export const MAX_SUBJECT = 150;
export const MAX_TEXT = 16000;
const paths = ['/api/alerts/subscribe', '/api/alerts/status', '/api/alerts/send', '/api/alerts/subscription', '/alerts/confirm/', '/api/alerts/confirm', '/alerts/unsubscribe/', '/api/alerts/unsubscribe', '/api/alerts/one-click'];

export async function cleanupAlerts(env, now = Date.now()) {
 await env.SIGNUPS.batch([
  env.SIGNUPS.prepare('DELETE FROM alert_rate_limits WHERE created_at < ?').bind(now - day),
  env.SIGNUPS.prepare('DELETE FROM alert_subscriptions WHERE confirmed_at IS NULL AND confirm_expires_at < ?').bind(now - 6 * day)
 ]);
}
const bearer = request => { const m = /^Bearer ([a-f0-9]{64})$/.exec(request.headers.get('Authorization') || ''); return m ? m[1] : null; };
async function readJson(request, limit) {
 if (!request.headers.get('Content-Type')?.startsWith('application/json')) return { error: json({ error: 'JSON required.' }, 415) };
 try { const data = JSON.parse(await limitedText(request, limit)); if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('shape'); return { data }; }
 catch (e) { return { error: e.message === 'body_too_large' ? json({ error: 'Request too large.' }, 413) : json({ error: 'Invalid request.' }, 400) }; }
}
const unsubscribeToken = (env, id) => privateHash(env.RATE_LIMIT_SALT, 'alerts-unsubscribe:' + id);

export async function handleAlerts(request, env, dependencies = {}) {
 const url = new URL(request.url), now = dependencies.now?.() ?? Date.now();
 if (!paths.includes(url.pathname)) return null;
 if (!env.SIGNUPS || !env.EMAIL || !env.RATE_LIMIT_SALT) return json({ error: 'Email alerts are temporarily unavailable.', code: 'unavailable' }, 503);
 if (Number(request.headers.get('Content-Length') || 0) > MAX_TEXT + 4096) return json({ error: 'Request too large.' }, 413);

 // Pages and form posts reached from links in the emails.
 if (url.pathname === '/alerts/confirm/' || url.pathname === '/alerts/unsubscribe/') {
  if (request.method !== 'GET') return json({ error: 'Method not allowed.' }, 405);
  const token = url.searchParams.get('token') || '', unsubscribe = url.pathname === '/alerts/unsubscribe/';
  if (!tokenPattern.test(token)) return page('Link unavailable', '<p>Ask for a new confirmation email from Bridgit on your phone.</p>', 400);
  const row = await env.SIGNUPS.prepare(`SELECT email,confirm_expires_at FROM alert_subscriptions WHERE ${unsubscribe ? 'unsubscribe_hash' : 'confirm_hash'} = ?`).bind(await hash(token)).first();
  if (!row || (!unsubscribe && row.confirm_expires_at < now)) return page('Link expired', '<p>Open Bridgit on your phone and send the confirmation email again (Settings › Email alerts).</p>', 410);
  return page(unsubscribe ? 'Stop agent alerts' : 'Confirm agent alerts', `<p>${unsubscribe ? `Stop Bridgit agent-activity emails to ${escape(row.email)} and delete this alert subscription.` : `Send Bridgit agent-activity emails from your phone to ${escape(row.email)}. You can stop them from any alert email or in the app.`}</p><form method="post" action="${unsubscribe ? '/api/alerts/unsubscribe' : '/api/alerts/confirm'}"><input type="hidden" name="token" value="${token}"><button class="button" type="submit">${unsubscribe ? 'Stop alerts' : 'Confirm alerts'}</button></form>`);
 }
 // One-click unsubscribe (RFC 8058): the mail provider POSTs here with no Origin; the token in the URL is the secret.
 if (url.pathname === '/api/alerts/one-click') {
  if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
  const token = url.searchParams.get('token') || '';
  if (tokenPattern.test(token)) await env.SIGNUPS.prepare('DELETE FROM alert_subscriptions WHERE unsubscribe_hash = ?').bind(await hash(token)).run();
  return json({ unsubscribed: true });
 }
 if (url.pathname === '/api/alerts/confirm' || url.pathname === '/api/alerts/unsubscribe') {
  if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
  if (request.headers.get('Origin') !== origin) return json({ error: 'Please use the button on bridgit.cc.' }, 403);
  let token;
  try { token = new URLSearchParams(await limitedText(request, 500)).get('token') || ''; } catch { return json({ error: 'Invalid request.' }, 400); }
  if (!tokenPattern.test(token)) return page('Link unavailable', '<p>Ask for a new email from Bridgit on your phone.</p>', 400);
  if (url.pathname === '/api/alerts/unsubscribe') {
   await env.SIGNUPS.prepare('DELETE FROM alert_subscriptions WHERE unsubscribe_hash = ?').bind(await hash(token)).run();
   return page('Alerts stopped', '<p>Bridgit will send no more agent-activity emails to this address. The app shows the alerts as off the next time it tries to send.</p>');
  }
  const done = await env.SIGNUPS.prepare('UPDATE alert_subscriptions SET confirmed_at = ?, confirm_hash = NULL WHERE confirm_hash = ? AND confirm_expires_at >= ? RETURNING id').bind(now, await hash(token), now).first();
  if (!done) return page('Link expired', '<p>Open Bridgit on your phone and send the confirmation email again.</p>', 410);
  return page('Alerts confirmed', '<p>Your phone can now email you when agents use it. Change how often in Bridgit › Settings › Email alerts.</p>');
 }

 // App endpoints: Bearer install token. Browsers can't send these cross-site without a CORS preflight, which we never grant.
 const install = bearer(request);
 if (!install) return json({ error: 'Install token required.', code: 'unauthorized' }, 401);
 const installHash = await hash(install);

 if (url.pathname === '/api/alerts/subscribe') {
  if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
  const { data, error } = await readJson(request, 1024); if (error) return error;
  const email = typeof data.email === 'string' ? data.email.trim().toLowerCase() : '';
  if (!email || email.length > 254 || !emailPattern.test(email)) return json({ error: 'Enter a valid email address.', code: 'bad_email' }, 400);
  const emailHash = await privateHash(env.RATE_LIMIT_SALT, email);
  const ipHash = await privateHash(env.RATE_LIMIT_SALT, `${Math.floor(now / day)}:${request.headers.get('CF-Connecting-IP') || 'unknown'}`);
  const rateId = crypto.randomUUID();
  const reservation = await env.SIGNUPS.prepare(`INSERT INTO alert_rate_limits (id,ip_hash,email_hash,created_at)
   SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM alert_rate_limits WHERE created_at > ?) < 50
   AND (SELECT COUNT(*) FROM alert_rate_limits WHERE ip_hash = ? AND created_at > ?) < 5
   AND NOT EXISTS (SELECT 1 FROM alert_rate_limits WHERE email_hash = ? AND created_at > ?)`)
   .bind(rateId, ipHash, emailHash, now, now - day, ipHash, now - 3600000, emailHash, now - 300000).run();
  if (!reservation.meta.changes) return json({ error: 'Please wait a few minutes before asking for another confirmation email.', code: 'rate_limited' }, 429);
  const existing = await env.SIGNUPS.prepare('SELECT id FROM alert_subscriptions WHERE install_hash = ?').bind(installHash).first();
  const id = existing?.id || crypto.randomUUID(), confirm = randomToken(), unsubscribe = await unsubscribeToken(env, id);
  await env.SIGNUPS.prepare(`INSERT INTO alert_subscriptions (id,email,install_hash,confirm_hash,unsubscribe_hash,created_at,confirm_expires_at)
   VALUES (?,?,?,?,?,?,?) ON CONFLICT(install_hash) DO UPDATE SET email=excluded.email, confirm_hash=excluded.confirm_hash,
   confirm_expires_at=excluded.confirm_expires_at, confirmed_at=NULL`).bind(id, email, installHash, await hash(confirm), await hash(unsubscribe), now, now + day).run();
  // Written to read like an ordinary account email: one link, no urgency, who sent it and why (Gmail filed the first version as spam).
  const link = `${origin}/alerts/confirm/?token=${confirm}`;
  const ok = await sendMail(env, email, 'Confirm your Bridgit activity emails',
   `Hello,\n\nThis address was added in the Bridgit app on an Android phone to receive activity summaries from that phone.\n\nTo start receiving them, open this page and press Confirm:\n${link}\n\nThe page stays available for 24 hours. If you didn't add this address, you can ignore this email and you won't hear from us again.\n\nBridgit, by Sponic Garden, Inc.\n${origin}`,
   { html: `<p>Hello,</p><p>This address was added in the Bridgit app on an Android phone to receive activity summaries from that phone.</p><p><a href="${link}">Confirm Bridgit activity emails</a></p><p>The page stays available for 24 hours. If you didn't add this address, you can ignore this email and you won't hear from us again.</p><p>Bridgit, by Sponic Garden, Inc.<br><a href="${origin}">bridgit.cc</a></p>` });
  if (!ok) return json({ error: 'We could not send the confirmation email. Try again later.', code: 'send_failed' }, 503);
  return json({ state: 'pending', email });
 }
 const row = await env.SIGNUPS.prepare('SELECT id,email,confirmed_at,sent_day,sent_count,last_key FROM alert_subscriptions WHERE install_hash = ?').bind(installHash).first();
 if (url.pathname === '/api/alerts/status') {
  if (request.method !== 'GET') return json({ error: 'Method not allowed.' }, 405);
  return json(row ? { state: row.confirmed_at ? 'confirmed' : 'pending', email: row.email } : { state: 'none' });
 }
 if (url.pathname === '/api/alerts/subscription') {
  if (request.method !== 'DELETE') return json({ error: 'Method not allowed.' }, 405);
  await env.SIGNUPS.prepare('DELETE FROM alert_subscriptions WHERE install_hash = ?').bind(installHash).run();
  return json({ state: 'none' });
 }
 // /api/alerts/send
 if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
 if (!row) return json({ error: 'No alert subscription. Set the address again in the app.', code: 'not_subscribed' }, 410);
 if (!row.confirmed_at) return json({ error: 'The address has not been confirmed yet.', code: 'not_confirmed' }, 409);
 const { data, error } = await readJson(request, MAX_TEXT + 2048); if (error) return error;
 const subject = typeof data.subject === 'string' ? data.subject.replace(/[\r\n]+/g, ' ').trim() : '';
 const text = typeof data.text === 'string' ? data.text : '';
 const key = typeof data.idempotency === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(data.idempotency) ? data.idempotency : null;
 if (!subject || subject.length > MAX_SUBJECT || !text || text.length > MAX_TEXT) return json({ error: 'Subject or text missing or too long.', code: 'bad_message' }, 400);
 // The app retries a batch whose reply it never got; Email Sending has no idempotency key, so remember the last one sent.
 if (key && row.last_key === key) return json({ sent: true, duplicate: true });
 const today = Math.floor(now / day);
 const charged = await env.SIGNUPS.prepare(`UPDATE alert_subscriptions SET sent_count = CASE WHEN sent_day = ? THEN sent_count + 1 ELSE 1 END, sent_day = ?
  WHERE id = ? AND (sent_day <> ? OR sent_count < ?) RETURNING sent_count`).bind(today, today, row.id, today, ALERT_DAILY_CAP).first();
 if (!charged) return json({ error: `At most ${ALERT_DAILY_CAP} alert emails per day.`, code: 'rate_limited' }, 429);
 const unsub = await unsubscribeToken(env, row.id);
 const footer = `\n\n—\nSent by Bridgit on your phone. Change how often in Bridgit › Settings › Email alerts.\nStop these emails: ${origin}/alerts/unsubscribe/?token=${unsub}\nSponic Garden, Inc. · ${origin}/privacy/`;
 // RFC 8058 one-click unsubscribe, which Gmail and Yahoo expect from senders of recurring mail.
 const ok = await sendMail(env, row.email, subject, text + footer, { headers: {
  'List-Unsubscribe': `<${origin}/api/alerts/one-click?token=${unsub}>`, 'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click' } });
 if (!ok) return json({ error: 'Email delivery failed. The app will retry.', code: 'send_failed' }, 503);
 if (key) await env.SIGNUPS.prepare('UPDATE alert_subscriptions SET last_key = ? WHERE id = ?').bind(key, row.id).run();
 return json({ sent: true });
}
