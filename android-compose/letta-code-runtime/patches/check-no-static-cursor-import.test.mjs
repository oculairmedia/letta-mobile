// letta-mobile-m0zpv: the guard must flag every top-level static form that would load the excluded
// Cursor SDK at startup, and must leave dynamic imports and unrelated modules alone (letta-code
// 0.34.39 reaches the SDK only through the separate cursor-worker.js).
// Run: node --test android-compose/letta-code-runtime/patches/check-no-static-cursor-import.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isCursorSdk, staticCursorImports } from './check-no-static-cursor-import.mjs';

/** A minimal Babel-shaped program: one top-level node per entry. */
function program(...body) {
  return { program: { body } };
}

const source = (value) => ({ type: 'StringLiteral', value });

test('flags a top-level import of the SDK and of a subpath', () => {
  const ast = program(
    { type: 'ImportDeclaration', source: source('@cursor/sdk') },
    { type: 'ImportDeclaration', source: source('@cursor/sdk/agent') },
  );
  assert.deepEqual(staticCursorImports(ast), ['@cursor/sdk', '@cursor/sdk/agent']);
});

test('flags named and star re-exports of the SDK', () => {
  const ast = program(
    { type: 'ExportNamedDeclaration', source: source('@cursor/sdk') },
    { type: 'ExportAllDeclaration', source: source('@cursor/sdk') },
  );
  assert.deepEqual(staticCursorImports(ast), ['@cursor/sdk', '@cursor/sdk']);
});

test('ignores unrelated modules, local exports and look-alike names', () => {
  const ast = program(
    { type: 'ImportDeclaration', source: source('node:fs') },
    { type: 'ImportDeclaration', source: source('@cursor/sdk-linux-arm64') },
    { type: 'ImportDeclaration', source: source('cli-cursor') },
    { type: 'ExportNamedDeclaration', source: null },
  );
  assert.deepEqual(staticCursorImports(ast), []);
});

test('ignores a dynamic import, which is not a top-level module declaration', () => {
  const dynamicImport = {
    type: 'ExpressionStatement',
    expression: { type: 'CallExpression', callee: { type: 'Import' }, arguments: [source('@cursor/sdk')] },
  };
  assert.deepEqual(staticCursorImports(program(dynamicImport)), []);
});

test('isCursorSdk matches the package and subpaths only', () => {
  assert.equal(isCursorSdk('@cursor/sdk'), true);
  assert.equal(isCursorSdk('@cursor/sdk/dist/esm/index.js'), true);
  assert.equal(isCursorSdk('@cursor/sdk-darwin-arm64'), false);
  assert.equal(isCursorSdk('@cursor/other'), false);
});
