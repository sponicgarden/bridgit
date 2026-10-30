const origin = 'https://bridgit.cc';
const day = 86400000;
const consentVersion = '2026-10-28';
const tokenPattern = /^[a-f0-9]{64}$/;
const replyHeaders = { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex, nofollow' };
export const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const json = (data, status = 200) => Response.json(data, { status, headers: replyHeaders });
export const randomToken = () => Array.from(crypto.getRandomValues(new Uint8Array(32)), n => n.toString(16).padStart(2, '0')).join('');
export async function hash(value) { return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))), n => n.toString(16).padStart(2, '0')).join(''); }
export async function privateHash(secret, value) {
 const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
 return Array.from(new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))), n => n.toString(16).padStart(2, '0')).join('');
}
export function page(title, body, status = 200) {
 return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>${escape(title)} — bridgit</title><link rel="stylesheet" href="/assets/style.css"></head><body><div class="wrap"><nav><a class="wordmark" href="/" aria-label="Bridgit home"><img class="brand-icon" src="/assets/logo-mark.svg" alt=""><img class="brand-name" src="/assets/logo-wordmark.svg" alt=""></a></nav><main class="article"><h1>${escape(title)}</h1>${body}</main><footer><a href="/privacy/">Privacy</a><a href="/terms/">Terms</a></footer></div></body></html>`, { status, headers: { ...replyHeaders, 'Referrer-Policy': 'strict-origin', 'Content-Type': 'text/html; charset=utf-8' } });
}
// All mail goes out through Cloudflare Email Sending (the EMAIL send_email binding in wrangler.jsonc).
export async function sendMail(env, to, subject, text) {
 try { await env.EMAIL.send({ from: { email: 'support@bridgit.cc', name: 'bridgit' }, to, replyTo: 'support@bridgit.cc', subject, text }); return true; }
 catch (e) { console.error('email send failed', e?.code || e?.name || 'error'); return false; }
}
export async function cleanupSubscribers(env, now = Date.now()) {
 await env.SIGNUPS.batch([
  env.SIGNUPS.prepare('DELETE FROM signup_rate_limits WHERE created_at < ?').bind(now - day),
  env.SIGNUPS.prepare('DELETE FROM download_requests WHERE wants_updates = 0 AND expires_at < ?').bind(now - 6 * day)
 ]);
}
export async function limitedText(request, limit) {
 const reader = request.body?.getReader();
 if (!reader) return '';
 const chunks = []; let size = 0;
 while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  size += value.byteLength;
  if (size > limit) { await reader.cancel(); throw new Error('body_too_large'); }
  chunks.push(value);
 }
 const bytes = new Uint8Array(size); let offset = 0;
 for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
 return new TextDecoder().decode(bytes);
}
export async function handleDownloads(request, env, dependencies = {}) {
 const url = new URL(request.url), fetcher = dependencies.fetch || fetch, now = dependencies.now?.() ?? Date.now();
 if (!['/api/download-request', '/confirm-download/', '/api/confirm-download', '/unsubscribe/', '/api/unsubscribe'].includes(url.pathname)) return null;
 if (!env.SIGNUPS) return json({ error: 'Email downloads are temporarily unavailable. Please contact support@bridgit.cc.' }, 503);
 if (request.method === 'POST' && request.headers.get('Origin') !== origin) return json({ error: 'Please submit the form on bridgit.cc.' }, 403);
 if (Number(request.headers.get('Content-Length') || 0) > 4096) return json({ error: 'Request too large.' }, 413);
 if (url.pathname === '/api/download-request') {
  if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) return json({ error: 'JSON required.' }, 415);
  if (!env.EMAIL || !env.TURNSTILE_SECRET || !env.RATE_LIMIT_SALT) return json({ error: 'Email downloads are temporarily unavailable. Please try later.' }, 503);
  let data;
  try { const raw = await limitedText(request, 4096); data = JSON.parse(raw); } catch (e) { return json({ error: e.message === 'body_too_large' ? 'Request too large.' : 'Invalid request.' }, e.message === 'body_too_large' ? 413 : 400); }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return json({ error: 'Invalid request.' }, 400);
  const email = typeof data.email === 'string' ? data.email.trim().toLowerCase() : '';
  if (!email || email.length > 254 || !/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/.test(email)) return json({ error: 'Enter a valid email address.' }, 400);
  if (data.website) return json({ message: 'Check your inbox for your download email.' });
  if (typeof data.turnstileToken !== 'string' || data.turnstileToken.length > 2048 || !data.turnstileToken) return json({ error: 'Please complete the security check.' }, 400);
  let check;
  try {
   const verification = await fetcher('https://challenges.cloudflare.com/turnstile/v0/siteverify', { method: 'POST', body: new URLSearchParams({ secret: env.TURNSTILE_SECRET, response: data.turnstileToken }), signal: AbortSignal.timeout(8000) });
   check = await verification.json();
  } catch { return json({ error: 'Security verification is temporarily unavailable. Please try again.' }, 503); }
  if (!check.success || check.hostname !== 'bridgit.cc' || check.action !== 'download_signup') return json({ error: 'Please retry the security check.' }, 400);
  const emailHash = await privateHash(env.RATE_LIMIT_SALT, email);
  const ipHash = await privateHash(env.RATE_LIMIT_SALT, `${Math.floor(now / day)}:${request.headers.get('CF-Connecting-IP') || 'unknown'}`);
  const rateId = crypto.randomUUID();
  const reservation = await env.SIGNUPS.prepare(`INSERT INTO signup_rate_limits (id,ip_hash,email_hash,created_at)
   SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM signup_rate_limits WHERE created_at > ?) < 20
   AND (SELECT COUNT(*) FROM signup_rate_limits WHERE ip_hash = ? AND created_at > ?) < 3
   AND NOT EXISTS (SELECT 1 FROM signup_rate_limits WHERE email_hash = ? AND created_at > ?)`)
   .bind(rateId, ipHash, emailHash, now, now - day, ipHash, now - 3600000, emailHash, now - 900000).run();
  if (!reservation.meta.changes) return json({ error: 'Please wait before requesting another email. You can also contact support@bridgit.cc.' }, 429);
  const token = randomToken(), unsubscribe = await privateHash(env.RATE_LIMIT_SALT, 'unsubscribe:' + email);
  const tokenHash = await hash(token), unsubscribeHash = await hash(unsubscribe);
  await env.SIGNUPS.prepare(`INSERT INTO download_requests (id,email,token_hash,unsubscribe_hash,created_at,expires_at,pending_updates,consent_version)
   VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(email) DO UPDATE SET token_hash=excluded.token_hash, unsubscribe_hash=excluded.unsubscribe_hash, expires_at=excluded.expires_at,
   pending_updates=excluded.pending_updates, token_used=0, consent_version=excluded.consent_version`).bind(crypto.randomUUID(), email, tokenHash, unsubscribeHash, now, now + day, data.updates === true ? 1 : 0, consentVersion).run();
  const confirm = `${origin}/confirm-download/?token=${token}`;
  const cancel = `${origin}/unsubscribe/?token=${unsubscribe}`;
  const sent = await sendMail(env, email, 'Your bridgit Android download', `You requested the bridgit Android APK. Confirm your email and open the download page:\n\n${confirm}\n\nThis link expires in 24 hours. Android 14+ and Tailscale are required. This is a sideloaded development build, not a Google Play release.\n\n${data.updates === true ? 'You also asked for future release updates. The confirmation button confirms that preference.\n\n' : ''}If you did not request this, ignore this message or delete this signup: ${cancel}\n\nSponic Garden, Inc. · https://bridgit.cc/privacy/`);
  if (!sent) return json({ error: 'We could not send the email just now. Please try later or contact support@bridgit.cc.' }, 503);
  return json({ message: 'Check your inbox. Confirm your email there to open the APK download page.' });
 }
 if (url.pathname === '/confirm-download/' || url.pathname === '/unsubscribe/') {
  if (request.method !== 'GET') return json({ error: 'Method not allowed.' }, 405);
  const token = url.searchParams.get('token') || '';
  if (!tokenPattern.test(token)) return page('Link unavailable', '<p>Please <a href="/download/">request a fresh download email</a>.</p>', 400);
  const unsubscribe = url.pathname === '/unsubscribe/';
  const record = await env.SIGNUPS.prepare(`SELECT id,expires_at,token_used,pending_updates FROM download_requests WHERE ${unsubscribe ? 'unsubscribe_hash' : 'token_hash'} = ?`).bind(await hash(token)).first();
  if (!record || (!unsubscribe && record.expires_at < now)) return page('Link expired', '<p>Please <a href="/download/">request a fresh download email</a>.</p>', 410);
  if (!unsubscribe && record.token_used) return page('Email confirmed', '<p><a class="button" href="/download/?confirmed=1">Open APK download</a></p>');
  return page(unsubscribe ? 'Delete your signup' : 'Your Android APK is ready', `<p>${unsubscribe ? 'Stop release updates and delete this email signup.' : (record.pending_updates ? 'Confirm your download request and subscribe to emails about future bridgit releases. You can unsubscribe at any time.' : 'Confirm your one-time download request. This request does not add a new release-update subscription.')}</p><form method="post" action="${unsubscribe ? '/api/unsubscribe' : '/api/confirm-download'}"><input type="hidden" name="token" value="${token}"><button class="button" type="submit">${unsubscribe ? 'Delete signup & stop updates' : (record.pending_updates ? 'Confirm download and release updates' : 'Confirm download only')}</button></form>`);
 }
 if (request.method !== 'POST') return json({ error: 'Method not allowed.' }, 405);
 let token;
 try { const raw = await limitedText(request, 500); token = new URLSearchParams(raw).get('token') || ''; } catch { return json({ error: 'Invalid request.' }, 400); }
 if (!tokenPattern.test(token)) return page('Link unavailable', '<p>Request another email from the <a href="/download/">download page</a>.</p>', 400);
 if (url.pathname === '/api/unsubscribe') {
  await env.SIGNUPS.prepare('DELETE FROM download_requests WHERE unsubscribe_hash = ?').bind(await hash(token)).run();
  return page('Signup deleted', '<p>You will receive no further release updates from this signup. This does not delete app-local data or messages already in your inbox.</p><p><a href="/">Back to bridgit</a></p>');
 }
 const result = await env.SIGNUPS.prepare('UPDATE download_requests SET confirmed_at = COALESCE(confirmed_at,?), token_used=1, wants_updates=MAX(wants_updates,pending_updates), consented_at=CASE WHEN pending_updates=1 THEN ? ELSE consented_at END WHERE token_hash = ? AND expires_at >= ? AND token_used=0 RETURNING id').bind(now, now, await hash(token), now).first();
 if (!result) return page('Link expired', '<p>Please <a href="/download/">request a fresh download email</a>.</p>', 410);
 return new Response(null, { status: 303, headers: { ...replyHeaders, Location: `${origin}/download/?confirmed=1` } });
}
