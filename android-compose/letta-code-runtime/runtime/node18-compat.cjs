// node18-compat.cjs — preload (`node --require`) that backfills the Node 20/22 globals and builtins
// letta-code >= 0.26.2 reaches for, so the stock 0.34.x letta.js runs on nodejs-mobile 18.20.4.
// Every shim is install-if-missing (a no-op on Node >= 22). With LETTA_COMPAT_TRACE=1 each shim
// logs its first use to stderr as "[node18-compat] hit <name>", so a device run reports exactly
// which gaps were exercised.
//
// Deliberately NOT shimmed: globalThis.WebSocket (callers feature-detect and fall back to `ws`),
// globalThis.navigator (libraries use its absence to detect Node), node:sqlite (only lazily imported
// by session importers, which catch the module-missing error), RegExp `v` flag (no literal uses;
// a literal would be a parse-time SyntaxError and the bundle parses on 18).
'use strict';
const trace = process.env.LETTA_COMPAT_TRACE === '1';
const hit = new Set();
function traced(name, fn) {
  if (!trace) return fn;
  return function tracedShim(...args) {
    if (!hit.has(name)) { hit.add(name); process.stderr.write(`[node18-compat] hit ${name}\n`); }
    return fn.apply(this, args);
  };
}
function define(target, key, name, fn) {
  if (target[key] !== undefined) return;
  Object.defineProperty(target, key, { value: traced(name, fn), writable: true, configurable: true, enumerable: false });
}
const installed = [];
function track(target, key) { if (target[key] === undefined) installed.push(key); }

// --- WebCrypto global (Node 19+ default; on 18 only behind --experimental-global-webcrypto).
// First real failure of 0.34.14 `server --listen` on 18.20.4: "ReferenceError: crypto is not defined".
if (globalThis.crypto === undefined) {
  installed.push('crypto');
  const { webcrypto } = require('node:crypto');
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, writable: true, configurable: true, enumerable: false });
}

// --- File global (Node 20). The SDKs' toFile()/multipart paths throw without it on 18.
if (globalThis.File === undefined) {
  const { File } = require('node:buffer');
  if (File) { installed.push('File'); Object.defineProperty(globalThis, 'File', { value: File, writable: true, configurable: true, enumerable: false }); }
}

// --- Promise.withResolvers (Node 22): subagent follower/launch, codex sign-in.
track(Promise, 'withResolvers');
define(Promise, 'withResolvers', 'Promise.withResolvers', function withResolvers() {
  let resolve; let reject;
  const promise = new this((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
});

// --- Change-array-by-copy (Node 20): toSorted is used by the App Server terminal-authority logic,
// cron run log paging and agent sorting.
const AP = Array.prototype;
track(AP, 'toSorted');
define(AP, 'toSorted', 'Array.prototype.toSorted', function toSorted(compare) { return AP.slice.call(this).sort(compare); });
define(AP, 'toReversed', 'Array.prototype.toReversed', function toReversed() { return AP.slice.call(this).reverse(); });
define(AP, 'toSpliced', 'Array.prototype.toSpliced', function toSpliced(...args) { const c = AP.slice.call(this); c.splice(...args); return c; });
define(AP, 'with', 'Array.prototype.with', function withAt(index, value) {
  const len = this.length >>> 0; let i = Math.trunc(index) || 0; if (i < 0) i += len;
  if (i < 0 || i >= len) throw new RangeError('Invalid index : ' + index);
  const c = AP.slice.call(this); c[i] = value; return c;
});
for (const T of [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float32Array, Float64Array, BigInt64Array, BigUint64Array]) {
  define(T.prototype, 'toSorted', T.name + '.prototype.toSorted', function toSorted(compare) { return this.slice().sort(compare); });
  define(T.prototype, 'toReversed', T.name + '.prototype.toReversed', function toReversed() { return this.slice().reverse(); });
}

// --- Array.fromAsync (Node 22).
define(Array, 'fromAsync', 'Array.fromAsync', async function fromAsync(items, mapFn, thisArg) {
  const out = []; let i = 0;
  if (items != null && typeof items[Symbol.asyncIterator] === 'function') {
    for await (const v of items) out.push(mapFn ? await mapFn.call(thisArg, v, i++) : v);
  } else {
    for (const v of Array.from(items)) out.push(mapFn ? await mapFn.call(thisArg, await v, i++) : await v);
  }
  return out;
});

// --- Object.groupBy / Map.groupBy (Node 21).
define(Object, 'groupBy', 'Object.groupBy', function groupBy(items, cb) {
  const out = Object.create(null); let i = 0;
  for (const v of items) { const k = cb(v, i++); (out[k] ||= []).push(v); }
  return out;
});
define(Map, 'groupBy', 'Map.groupBy', function groupBy(items, cb) {
  const out = new Map(); let i = 0;
  for (const v of items) { const k = cb(v, i++); if (!out.has(k)) out.set(k, []); out.get(k).push(v); }
  return out;
});

// --- Set methods (Node 22).
const SP = Set.prototype;
const keysOf = (other) => (typeof other.keys === 'function' ? other.keys() : other[Symbol.iterator]());
const hasIn = (other, v) => other.has(v);
define(SP, 'union', 'Set.prototype.union', function union(o) { const r = new Set(this); for (const v of keysOf(o)) r.add(v); return r; });
define(SP, 'intersection', 'Set.prototype.intersection', function intersection(o) { const r = new Set(); for (const v of this) if (hasIn(o, v)) r.add(v); return r; });
define(SP, 'difference', 'Set.prototype.difference', function difference(o) { const r = new Set(); for (const v of this) if (!hasIn(o, v)) r.add(v); return r; });
define(SP, 'symmetricDifference', 'Set.prototype.symmetricDifference', function symmetricDifference(o) {
  const r = new Set(this); for (const v of keysOf(o)) { if (this.has(v)) r.delete(v); else r.add(v); } return r;
});
define(SP, 'isSubsetOf', 'Set.prototype.isSubsetOf', function isSubsetOf(o) { for (const v of this) if (!hasIn(o, v)) return false; return true; });
define(SP, 'isSupersetOf', 'Set.prototype.isSupersetOf', function isSupersetOf(o) { for (const v of keysOf(o)) if (!this.has(v)) return false; return true; });
define(SP, 'isDisjointFrom', 'Set.prototype.isDisjointFrom', function isDisjointFrom(o) { for (const v of this) if (hasIn(o, v)) return false; return true; });

// --- Well-formed strings (Node 20).
const StrP = String.prototype;
// No \p{...}: nodejs-mobile libnode is built without ICU, where property escapes throw.
const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g;
define(StrP, 'isWellFormed', 'String.prototype.isWellFormed', function isWellFormed() { LONE_SURROGATE.lastIndex = 0; return !LONE_SURROGATE.test(String(this)); });
define(StrP, 'toWellFormed', 'String.prototype.toWellFormed', function toWellFormed() { return String(this).replace(LONE_SURROGATE, '\uFFFD'); });

// --- ArrayBuffer.prototype.transfer (Node 21): web-streams helpers in the bundle.
define(ArrayBuffer.prototype, 'transfer', 'ArrayBuffer.prototype.transfer', function transfer(len) {
  const n = len === undefined ? this.byteLength : len; const c = new ArrayBuffer(n);
  new Uint8Array(c).set(new Uint8Array(this, 0, Math.min(n, this.byteLength))); return c;
});

// --- process.getBuiltinModule (Node 22.3): call sites use `?.`, but provide it so they take the
// fast path instead of the null fallback.
define(process, 'getBuiltinModule', 'process.getBuiltinModule', function getBuiltinModule(id) {
  try { return require(id.startsWith('node:') ? id : 'node:' + id); } catch { return undefined; }
});

// --- CustomEvent (Node 19 global).
if (globalThis.CustomEvent === undefined && typeof Event === 'function') {
  installed.push('CustomEvent');
  globalThis.CustomEvent = class CustomEvent extends Event {
    constructor(type, opts = {}) { super(type, opts); this.detail = opts.detail ?? null; }
  };
}

if (trace) process.stderr.write(`[node18-compat] node ${process.version}; installed: ${installed.join(', ') || '(none)'}\n`);
