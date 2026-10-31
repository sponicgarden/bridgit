import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parseCatalog } from '../scripts/features.mjs';
const source = await readFile(new URL('../../app/src/main/java/cc/bridgit/app/ui/more/WhatCanData.kt', import.meta.url), 'utf8');
test('catalog includes all app entries and preserves example quotes, availability and options', () => {
 const features = parseCatalog(source);
 assert.equal(features.length, 48);
 assert.equal(features.filter(f => f.stage === 'NOW').length, 45);
 assert.match(features.find(f => f.title === 'Questions and notices to you').example, /\$11\.99/);
 assert.ok(features.find(f => f.title === 'Take a photo').options.length > 0);
 assert.equal(features.find(f => f.title === 'Location history and places').stage, 'IDEA');
});
test('source drift cannot silently drop feature options', () => {
 assert.throws(() => parseCatalog(source.replace('val options:', 'val missing:')), /Missing options/);
 assert.throws(() => parseCatalog(source.replace('Feature(Stage.NOW, "Take a photo"', 'Feature(Stage.UNKNOWN, "Take a photo"')), /Unknown feature stage/);
});
