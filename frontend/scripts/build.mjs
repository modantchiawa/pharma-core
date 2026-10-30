// Builds the PWA stamped with its version: `ng build` with PHARMACORE_BUILD defined (version from
// package.json, short commit, build instant), then writes the same stamp to version.json next to
// index.html. Stations compare it with their own to offer the update (AppUpdateService).
// Extra arguments go to `ng build` (`node scripts/build.mjs --configuration demo`).
//   GIT_SHA / VERCEL_GIT_COMMIT_SHA   commit of the build (default: `git rev-parse --short HEAD`)
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));

function commit() {
  const given = process.env.GIT_SHA ?? process.env.VERCEL_GIT_COMMIT_SHA;
  if (given && given !== 'local') {
    return given.slice(0, 7);
  }
  try {
    return execFileSync('git', ['rev-parse', '--short=7', 'HEAD'], {
      cwd: root,
      encoding: 'utf8',
    }).trim();
  } catch {
    return '';
  }
}

const info = { version: pkg.version, commit: commit(), builtAt: new Date().toISOString() };
const ng = resolve(root, 'node_modules/.bin', process.platform === 'win32' ? 'ng.cmd' : 'ng');
const result = spawnSync(
  ng,
  ['build', '--define', `PHARMACORE_BUILD=${JSON.stringify(info)}`, ...process.argv.slice(2)],
  { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' },
);
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}
writeFileSync(resolve(root, 'dist/frontend/browser/version.json'), JSON.stringify(info) + '\n');
console.log(
  `PharmaCore ${info.version}${info.commit ? ' · ' + info.commit : ''} construit le ${info.builtAt}`,
);
