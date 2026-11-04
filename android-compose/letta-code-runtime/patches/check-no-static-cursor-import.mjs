// letta-mobile-m0zpv: the Android embedded runtime excludes @cursor/sdk and cursor-worker.js
// (HOST_ONLY_NODE_MODULES in PrepareEmbeddedLettaCodeAssetsTask). letta-code 0.34.39 only reaches
// the SDK from that separate worker, so startup is unaffected. If a future letta.js imports or
// re-exports it at the top level, the phone would crash on boot instead: fail the build here.
//
// Usage: node check-no-static-cursor-import.mjs <path/to/letta.js>
// Runs from the npm work dir so @babel/parser resolves (the patch pipeline installs it there).
import { readFileSync } from 'fs';
import { pathToFileURL } from 'url';

const STATIC_MODULE_NODES = new Set(['ImportDeclaration', 'ExportNamedDeclaration', 'ExportAllDeclaration']);

/** True when `specifier` names the Cursor SDK or one of its subpaths. */
export function isCursorSdk(specifier) {
  return specifier === '@cursor/sdk' || specifier.startsWith('@cursor/sdk/');
}

/** The top-level static import/export specifiers of `ast` that name the Cursor SDK. */
export function staticCursorImports(ast) {
  return ast.program.body
    .filter((node) => STATIC_MODULE_NODES.has(node.type) && node.source)
    .map((node) => node.source.value)
    .filter(isCursorSdk);
}

async function main(path) {
  if (!path) throw new Error('usage: node check-no-static-cursor-import.mjs <path/to/letta.js>');
  const { parse } = await import('@babel/parser');
  const found = staticCursorImports(parse(readFileSync(path, 'utf8'), { sourceType: 'module' }));
  if (found.length > 0) {
    throw new Error(`staged letta.js statically imports ${found.join(', ')}, which is excluded on Android`);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  await main(process.argv[2]);
}
