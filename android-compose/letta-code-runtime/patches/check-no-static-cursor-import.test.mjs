// letta-mobile-m0zpv: the guard must flag every top-level static form that would load the excluded
// Cursor SDK at startup, and must leave dynamic imports and unrelated modules alone (letta-code
// 0.34.39 reaches the SDK only through the separate cursor-worker.js).
// Run: node --test android-compose/letta-code-runtime/patches/check-no-static-cursor-import.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isCursorSdk, staticCursorImports } from './check-no-static-cursor-import.mjs';

/** Babel-shaped top-level module declarations: `[nodeType, specifier]` pairs (null = no source). */
function program(...declarations) {
  const body = declarations.map(([type, specifier]) => ({
    type,
    source: specifier === null ? null : { type: 'StringLiteral', value: specifier },
  }));
  return { program: { body } };
}

const cases = [
  {
    name: 'flags a top-level import of the SDK and of a subpath',
    ast: program(['ImportDeclaration', '@cursor/sdk'], ['ImportDeclaration', '@cursor/sdk/agent']),
    expected: ['@cursor/sdk', '@cursor/sdk/agent'],
  },
  {
    name: 'flags named and star re-exports of the SDK',
    ast: program(['ExportNamedDeclaration', '@cursor/sdk'], ['ExportAllDeclaration', '@cursor/sdk']),
    expected: ['@cursor/sdk', '@cursor/sdk'],
  },
  {
    name: 'ignores unrelated modules, local exports and look-alike names',
    ast: program(
      ['ImportDeclaration', 'node:fs'],
      ['ImportDeclaration', '@cursor/sdk-linux-arm64'],
      ['ImportDeclaration', 'cli-cursor'],
      ['ExportNamedDeclaration', null],
    ),
    expected: [],
  },
  {
    name: 'ignores a dynamic import, which is not a top-level module declaration',
    ast: program(['ExpressionStatement', '@cursor/sdk']),
    expected: [],
  },
];

for (const { name, ast, expected } of cases) {
  test(name, () => assert.deepEqual(staticCursorImports(ast), expected));
}

test('isCursorSdk matches the package and subpaths only', () => {
  assert.equal(isCursorSdk('@cursor/sdk'), true);
  assert.equal(isCursorSdk('@cursor/sdk/dist/esm/index.js'), true);
  assert.equal(isCursorSdk('@cursor/sdk-darwin-arm64'), false);
  assert.equal(isCursorSdk('@cursor/other'), false);
});
