import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { handleAlerts, cleanupAlerts, ALERT_DAILY_CAP } from '../alerts.mjs';
const schema = readFileSync(new URL('../migrations/0002_alert_subscriptions.sql', import.meta.url), 'utf8');
const install = 'a'.repeat(64), other = 'b'.repeat(64);
function setup() {
 const sql = new DatabaseSync(':memory:'); sql.exec(schema);
 const DB = { prepare(query) { let values=[]; return { bind(...v) { values=v; return this; }, async first() { return sql.prepare(query).get(...values) || null; }, async run() { const result=sql.prepare(query).run(...values); return {meta:{changes:Number(result.changes)}}; } }; }, async batch(statements) { return Promise.all(statements.map(s=>s.run())); } };
 const messages=[];
 const env = { SIGNUPS:DB, EMAIL:{ async send(m) { messages.push(m); return {messageId:'sent'}; } }, RATE_LIMIT_SALT:'private-test-salt' };
 const fetch = async (url, init) => { assert.fail('mail must go through the EMAIL binding, not fetch'); };
 return {sql,env,messages,fetch};
}
const app = (path, token, method='GET', body) => new Request('https://bridgit.cc'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{}),'CF-Connecting-IP':'192.0.2.9'},body:body&&JSON.stringify(body)});
const form = (path, token) => new Request('https://bridgit.cc'+path,{method:'POST',headers:{Origin:'https://bridgit.cc','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({token})});
async function subscribe(s, now, email='owner@example.com', token=install) {
 const r = await handleAlerts(app('/api/alerts/subscribe',token,'POST',{email}),s.env,{fetch:s.fetch,now:()=>now});
 return r;
}
const confirmToken = m => /alerts\/confirm\/\?token=([a-f0-9]+)/.exec(m.text)[1];

test('subscribe mails a confirm link; send is refused until the address confirms', async()=>{
 const s=setup(),now=Date.now();
 assert.equal((await subscribe(s,now)).status,200);
 assert.equal(s.messages[0].to,'owner@example.com');
 assert.equal((await (await handleAlerts(app('/api/alerts/status',install),s.env,{now:()=>now})).json()).state,'pending');
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y'}),s.env,{fetch:s.fetch,now:()=>now})).status,409);
 const token=confirmToken(s.messages[0]);
 assert.notEqual(s.sql.prepare('SELECT confirm_hash FROM alert_subscriptions').get().confirm_hash,token);
 assert.equal((await handleAlerts(new Request('https://bridgit.cc/alerts/confirm/?token='+token),s.env,{now:()=>now})).status,200);
 assert.equal(s.sql.prepare('SELECT confirmed_at FROM alert_subscriptions').get().confirmed_at,null, 'GET alone never confirms');
 assert.equal((await handleAlerts(form('/api/alerts/confirm',token),s.env,{now:()=>now})).status,200);
 assert.equal((await (await handleAlerts(app('/api/alerts/status',install),s.env,{now:()=>now})).json()).state,'confirmed');
 const sent=await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'Bridgit: Claude used your phone',text:'3 calls',idempotency:'k1'}),s.env,{fetch:s.fetch,now:()=>now});
 assert.equal(sent.status,200);
 const mail=s.messages[1]; assert.equal(mail.to,'owner@example.com'); assert.match(mail.text,/^3 calls/); assert.match(mail.text,/alerts\/unsubscribe\/\?token=[a-f0-9]{64}/);
});

test('app endpoints need a well-formed Bearer token and another install cannot send', async()=>{
 const s=setup(),now=Date.now();
 assert.equal((await handleAlerts(app('/api/alerts/status',null),s.env,{now:()=>now})).status,401);
 assert.equal((await handleAlerts(app('/api/alerts/status','short'),s.env,{now:()=>now})).status,401);
 await subscribe(s,now); await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[0])),s.env,{now:()=>now});
 assert.equal((await handleAlerts(app('/api/alerts/send',other,'POST',{subject:'x',text:'y'}),s.env,{fetch:s.fetch,now:()=>now})).status,410);
 assert.equal(s.messages.length,1);
});

test('confirm and unsubscribe form posts must come from bridgit.cc', async()=>{
 const s=setup(),now=Date.now(); await subscribe(s,now);
 const cross=new Request('https://bridgit.cc/api/alerts/confirm',{method:'POST',headers:{Origin:'https://evil.example','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({token:confirmToken(s.messages[0])})});
 assert.equal((await handleAlerts(cross,s.env,{now:()=>now})).status,403);
});

test('changing the address resets confirmation; expired confirm links fail', async()=>{
 const s=setup(),now=Date.now(); await subscribe(s,now);
 await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[0])),s.env,{now:()=>now});
 await subscribe(s,now+600000,'new@example.com');
 const row=s.sql.prepare('SELECT * FROM alert_subscriptions').get(); assert.equal(row.email,'new@example.com'); assert.equal(row.confirmed_at,null);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM alert_subscriptions').get().n,1);
 assert.equal((await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[1])),s.env,{now:()=>now+600000+2*86400000})).status,410);
});

test('confirmation emails are rate limited per address', async()=>{
 const s=setup(),now=Date.now();
 assert.equal((await subscribe(s,now)).status,200);
 assert.equal((await subscribe(s,now+1000)).status,429);
 assert.equal(s.messages.length,1);
});

test('daily send cap, unsubscribe link and app delete stop alerts', async()=>{
 const s=setup(),now=Date.UTC(2026,9,30,12); await subscribe(s,now);
 await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[0])),s.env,{now:()=>now});
 s.sql.prepare('UPDATE alert_subscriptions SET sent_day=?, sent_count=?').run(Math.floor(now/86400000),ALERT_DAILY_CAP);
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y'}),s.env,{fetch:s.fetch,now:()=>now})).status,429);
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y'}),s.env,{fetch:s.fetch,now:()=>now+86400000})).status,200);
 const unsub=/alerts\/unsubscribe\/\?token=([a-f0-9]+)/.exec(s.messages.at(-1).text)[1];
 assert.equal((await handleAlerts(form('/api/alerts/unsubscribe',unsub),s.env,{now:()=>now})).status,200);
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y'}),s.env,{fetch:s.fetch,now:()=>now+86400000})).status,410);
 await subscribe(s,now+86400000*2,'owner@example.com');
 assert.equal((await handleAlerts(app('/api/alerts/subscription',install,'DELETE'),s.env,{now:()=>now})).status,200);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM alert_subscriptions').get().n,0);
});

test('oversized and malformed messages are refused; cleanup drops stale pending rows', async()=>{
 const s=setup(),now=Date.now(); await subscribe(s,now);
 await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[0])),s.env,{now:()=>now});
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'',text:'y'}),s.env,{fetch:s.fetch,now:()=>now})).status,400);
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y'.repeat(17000)}),s.env,{fetch:s.fetch,now:()=>now})).status,400);
 await subscribe(s,now,'pending@example.com',other);
 await cleanupAlerts(s.env,now+8*86400000);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM alert_subscriptions').get().n,1);
});

test('a retried batch is not mailed twice and a failing Email Sending binding returns 503', async()=>{
 const s=setup(),now=Date.now(); await subscribe(s,now);
 await handleAlerts(form('/api/alerts/confirm',confirmToken(s.messages[0])),s.env,{now:()=>now});
 const send=()=>handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y',idempotency:'hourly-1-9'}),s.env,{now:()=>now});
 assert.equal((await send()).status,200); assert.equal((await send()).status,200);
 assert.equal(s.messages.length,2, 'confirmation + one alert');
 s.env.EMAIL={ async send() { const e=new Error('limit'); e.code='E_DAILY_LIMIT_EXCEEDED'; throw e; } };
 assert.equal((await handleAlerts(app('/api/alerts/send',install,'POST',{subject:'x',text:'y',idempotency:'hourly-10-12'}),s.env,{now:()=>now})).status,503);
 delete s.env.EMAIL;
 assert.equal((await send()).status,503, 'no binding means unavailable');
});
