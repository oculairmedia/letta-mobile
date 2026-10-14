// pack.sh: the same sources give the same bytes, and the package holds exactly what it should.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { PLUGIN_DIR } from './fake-host.mjs';

const hasZip = (() => {
  try {
    execFileSync('zip', ['-v'], { stdio: 'ignore' });
    execFileSync('unzip', ['-v'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();

const pack = () => {
  const out = mkdtempSync(join(tmpdir(), 'letta-chart-'));
  const [path, sha] = execFileSync('bash', [join(PLUGIN_DIR, 'pack.sh'), out], { encoding: 'utf8' }).trim().split('\n');
  return { path, sha };
};

test('pack.sh is deterministic and packs the manifest, runtime files and page only', { skip: !hasZip && 'zip/unzip not installed' }, () => {
  const first = pack();
  const second = pack();
  assert.match(first.path, /letta\.chart-1\.0\.0\.lcp$/);
  assert.match(first.sha, /^sha256:[0-9a-f]{64}$/);
  assert.equal(first.sha, second.sha);
  assert.deepEqual(readFileSync(first.path), readFileSync(second.path));
  const entries = execFileSync('unzip', ['-Z1', first.path], { encoding: 'utf8' }).trim().split('\n');
  assert.deepEqual(entries, ['letta-plugin.json', 'pages/chart.html', 'png.mjs', 'server.mjs']);
});
