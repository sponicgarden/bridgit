import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { handleDownloads, cleanupSubscribers } from '../email.mjs';
const schema = readFileSync(new URL('../migrations/0001_download_requests.sql', import.meta.url), 'utf8');
function setup() {
 const sql = new DatabaseSync(':memory:'); sql.exec(schema);
 const DB = { prepare(query) { let values=[]; return { bind(...v) { values=v; return this; }, async first() { return sql.prepare(query).get(...values) || null; }, async run() { const result=sql.prepare(query).run(...values); return {meta:{changes:Number(result.changes)}}; } }; }, async batch(statements) { return Promise.all(statements.map(s=>s.run())); } };
 const env = { SIGNUPS:DB, RESEND_API_KEY:'test', TURNSTILE_SECRET:'test', RATE_LIMIT_SALT:'private-test-salt' };
 const messages=[];
 const fetch = async (url, init) => { if (url.includes('siteverify')) return Response.json({success:true,hostname:'bridgit.cc',action:'download_signup'}); messages.push(JSON.parse(init.body)); return Response.json({id:'sent'}); };
 return {sql,env,messages,fetch};
}
function signup(email='person@example.com', updates=false) { return new Request('https://bridgit.cc/api/download-request',{method:'POST',headers:{Origin:'https://bridgit.cc','Content-Type':'application/json','CF-Connecting-IP':'192.0.2.5'},body:JSON.stringify({email,updates,turnstileToken:'test-token'})}); }
function post(path, token) { return new Request('https://bridgit.cc'+path,{method:'POST',headers:{Origin:'https://bridgit.cc','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({token})}); }
test('download email confirms only on explicit POST, tokens are hashed, replay is rejected', async()=>{
 const s=setup(),now=Date.now();
 assert.equal((await handleDownloads(signup('person@example.com',true),s.env,{fetch:s.fetch,now:()=>now})).status,200);
 const message=s.messages[0]; assert.equal(message.to[0],'person@example.com');
 const token=/confirm-download\/\?token=([a-f0-9]+)/.exec(message.text)[1];
 let row=s.sql.prepare('SELECT * FROM download_requests').get(); assert.notEqual(row.token_hash,token); assert.equal(row.confirmed_at,null); assert.equal(row.wants_updates,0);
 const get=new Request('https://bridgit.cc/confirm-download/?token='+token);
 assert.equal((await handleDownloads(get,s.env,{now:()=>now})).status,200);
 assert.equal(s.sql.prepare('SELECT confirmed_at FROM download_requests').get().confirmed_at,null);
 const confirmed=await handleDownloads(post('/api/confirm-download',token),s.env,{now:()=>now});
 assert.equal(confirmed.status,303); assert.equal(confirmed.headers.get('location'),'https://bridgit.cc/download/?confirmed=1');
 row=s.sql.prepare('SELECT * FROM download_requests').get();assert.equal(row.wants_updates,1);assert.equal(row.token_used,1);
 assert.equal((await handleDownloads(post('/api/confirm-download',token),s.env,{now:()=>now})).status,410);
});
test('cross-site and bot-invalid requests cannot send or store emails',async()=>{
 const s=setup();
 const cross=new Request(signup(),{headers:{Origin:'https://untrusted.example','Content-Type':'application/json'}});
 assert.equal((await handleDownloads(cross,s.env,{fetch:s.fetch})).status,403);
 assert.equal((await handleDownloads(signup(),s.env,{fetch:async()=>Response.json({success:true,hostname:'other.example',action:'download_signup'})})).status,400);
 assert.equal(s.messages.length,0);assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM download_requests').get().n,0);
});
test('email cooldown atomically blocks duplicate requests and request bodies are bounded',async()=>{
 const s=setup();assert.equal((await handleDownloads(signup(),s.env,{fetch:s.fetch})).status,200);
 assert.equal((await handleDownloads(signup(),s.env,{fetch:s.fetch})).status,429);assert.equal(s.messages.length,1);
 const huge=new Request('https://bridgit.cc/api/download-request',{method:'POST',headers:{Origin:'https://bridgit.cc','Content-Type':'application/json'},body:'x'.repeat(5000)});
 assert.equal((await handleDownloads(huge,s.env,{fetch:s.fetch})).status,413);
});
test('download-only signup can be deleted via explicit unsubscribe and pending records are purged',async()=>{
 const s=setup(),now=Date.now();await handleDownloads(signup(),s.env,{fetch:s.fetch,now:()=>now});
 const token=/unsubscribe\/\?token=([a-f0-9]+)/.exec(s.messages[0].text)[1];
 assert.equal((await handleDownloads(new Request('https://bridgit.cc/unsubscribe/?token='+token),s.env)).status,200);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM download_requests').get().n,1);
 assert.equal((await handleDownloads(post('/api/unsubscribe',token),s.env)).status,200);
 assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM download_requests').get().n,0);
 await handleDownloads(signup('other@example.com'),s.env,{fetch:s.fetch,now:()=>now});
 await cleanupSubscribers(s.env,now+8*86400000);assert.equal(s.sql.prepare('SELECT COUNT(*) n FROM download_requests').get().n,0);
});
test('resubscribe does not enroll a previously confirmed download-only address before confirmation',async()=>{
 const s=setup(),now=Date.now();await handleDownloads(signup(),s.env,{fetch:s.fetch,now:()=>now});
 const token=/confirm-download\/\?token=([a-f0-9]+)/.exec(s.messages[0].text)[1];
 await handleDownloads(post('/api/confirm-download',token),s.env,{now:()=>now});
 await handleDownloads(signup('person@example.com',true),s.env,{fetch:s.fetch,now:()=>now+1000000});
 assert.equal(s.sql.prepare('SELECT wants_updates FROM download_requests').get().wants_updates,0);
});

test('JSON null and arrays are rejected without sending',async()=>{
 const s=setup();
 for (const body of ['null','[]']) {
  const request=new Request('https://bridgit.cc/api/download-request',{method:'POST',headers:{Origin:'https://bridgit.cc','Content-Type':'application/json'},body});
  assert.equal((await handleDownloads(request,s.env,{fetch:s.fetch})).status,400);
 }
 assert.equal(s.messages.length,0);
});
