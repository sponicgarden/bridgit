// Polls the live /version.json until it reports this checkout's commit.
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const expected = process.env.EXPECTED_SHA || execSync('git rev-parse HEAD', { cwd: root, encoding: 'utf8' }).trim();
const url = process.env.VERIFY_URL || 'https://bridgit.cc/version.json';
let live;
for (let attempt = 1; attempt <= 12; attempt++) {
  try {
    const response = await fetch(`${url}?t=${Date.now()}`, { headers: { 'cache-control': 'no-cache' } });
    live = await response.json();
    if (live.fullSha === expected) {
      console.log(`Live ${url}: ${live.version} ${live.sha} matches ${expected.slice(0, 7)}.`);
      process.exit(0);
    }
  } catch (error) { live = { error: error.message }; }
  await new Promise(resolve => setTimeout(resolve, 5000));
}
console.error(`Live ${url} does not match ${expected.slice(0, 7)}: ${JSON.stringify(live)}`);
process.exit(1);
