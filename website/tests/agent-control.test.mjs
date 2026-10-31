import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateControlCatalog,renderControlCatalog} from '../scripts/agent-control.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url)).replace(/\/$/,'');
const catalog=JSON.parse(await readFile(new URL('../content/agent-control.json',import.meta.url),'utf8'));
test('all twenty control families have implementation evidence and distinct local availability',()=>{
 validateControlCatalog(catalog,root);const html=renderControlCatalog(catalog);
 assert.match(html,/not included in the published 1\.6\.0/);assert.match(html,/Each|each action/);assert.match(html,/Continuous|continuous audio\/video/);
 assert.equal((html.match(/Development build<\/span>/g)||[]).length,20);
});
test('unimplemented or published-status control claims fail closed',()=>{
 const missing=structuredClone(catalog);missing.features[0].implementation='missing.kt';assert.throws(()=>validateControlCatalog(missing,root),/Missing implementation/);
 const unsafe=structuredClone(catalog);unsafe.features[0].status='current';assert.throws(()=>validateControlCatalog(unsafe,root),/published availability/);
});
