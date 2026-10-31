// Refuses a production deploy unless this checkout is exactly origin/main with no
// uncommitted changes, so bridgit.cc always matches main and its version stamp.
// BRIDGIT_DEPLOY_UNCHECKED=1 skips the check for a deliberate one-off deploy.
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const git = args => execSync(`git ${args}`, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
if (process.env.BRIDGIT_DEPLOY_UNCHECKED === '1') {
  console.warn('Deploy guard skipped (BRIDGIT_DEPLOY_UNCHECKED=1). bridgit.cc will not match main.');
  process.exit(0);
}
git('fetch --quiet origin main');
const head = git('rev-parse HEAD'), main = git('rev-parse origin/main');
const dirty = git('status --porcelain');
const problems = [];
if (head !== main) problems.push(`HEAD is ${head.slice(0, 7)} (${git('rev-parse --abbrev-ref HEAD')}), origin/main is ${main.slice(0, 7)}.`);
if (dirty) problems.push(`Uncommitted changes:\n${dirty}`);
if (problems.length) {
  console.error(`Refusing to deploy bridgit.cc: production deploys only from origin/main with a clean tree.\n${problems.join('\n')}\nMerge to main (the Deploy website workflow then deploys it), or set BRIDGIT_DEPLOY_UNCHECKED=1 to deploy this tree anyway.`);
  process.exit(1);
}
console.log(`Deploy guard: HEAD is origin/main ${main.slice(0, 7)}, tree clean.`);
