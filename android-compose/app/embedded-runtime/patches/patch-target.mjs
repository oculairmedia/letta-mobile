// The letta.js a patch script rewrites, from argv[2], confined to the directory the patch scripts
// run from (PrepareEmbeddedLettaCodeAssetsTask copies them into the npm work dir and passes
// <work dir>/node_modules/@letta-ai/letta-code/letta.js). A path outside it is refused, so a
// mistyped or crafted argument cannot make a build-time patch rewrite an arbitrary file.
import { realpathSync } from 'fs';
import { dirname, relative, resolve, isAbsolute } from 'path';
import { fileURLToPath } from 'url';

function isInside(root, target) {
  const rel = relative(root, target);
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel);
}

export function patchTarget(argv = process.argv) {
  const arg = argv[2];
  if (!arg) throw new Error('usage: node <patch>.mjs <path/to/letta.js>');
  const root = realpathSync(dirname(fileURLToPath(import.meta.url)));
  const target = realpathSync(resolve(arg));
  if (!isInside(root, target)) {
    throw new Error(`refusing to patch ${target}: outside ${root}`);
  }
  return target;
}
