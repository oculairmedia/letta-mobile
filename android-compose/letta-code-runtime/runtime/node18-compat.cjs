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
function note(name) {
  if (trace && !hit.has(name)) { hit.add(name); process.stderr.write(`[node18-compat] hit ${name}\n`); }
}
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

// --- TextDecoder { fatal: true } without ICU. nodejs-mobile is built --with-intl=none, where
// Node's own TextDecoder throws ERR_NO_ICU for `fatal` (lib/internal/encoding.js
// makeTextDecoderJS). letta.js builds one at module init (src/utils/text-files), so this was the
// first on-device crash of 0.34.14: `TypeError [ERR_NO_ICU]: "fatal" option is not supported`.
// Decoding stays Node's; `fatal` is enforced here with buffer.isUtf8 (simdutf, Node >= 18.14, no
// ICU). Only installed when the platform TextDecoder actually refuses `fatal`.
(function installFatalTextDecoder() {
  const Native = globalThis.TextDecoder;
  if (typeof Native !== 'function') return;
  try { new Native('utf-8', { fatal: true }); return; } catch (e) { if (!e || e.code !== 'ERR_NO_ICU') return; }
  const { isUtf8 } = require('node:buffer');
  installed.push('TextDecoder.fatal');
  const invalid = () => Object.assign(new TypeError('The encoded data was not valid for encoding utf-8'), { code: 'ERR_ENCODING_INVALID_ENCODED_DATA' });
  const bytesOf = (input) => {
    if (input === undefined) return new Uint8Array(0);
    if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    return new Uint8Array(input); // ArrayBuffer / SharedArrayBuffer; anything else: super.decode throws
  };
  // Bytes at the end that start a multi-byte sequence the chunk cuts off (kept for the next chunk).
  const incompleteTail = (b) => {
    for (let back = 1; back <= Math.min(3, b.length); back++) {
      const c = b[b.length - back];
      if ((c & 0xc0) === 0x80) continue; // continuation byte: keep looking for the lead
      const need = c >= 0xf0 ? 4 : c >= 0xe0 ? 3 : c >= 0xc0 ? 2 : 1;
      return need > back ? back : 0;
    }
    return 0;
  };
  class TextDecoder extends Native {
    #fatal;
    #pending = new Uint8Array(0);
    constructor(label = 'utf-8', options = undefined) {
      const fatal = Boolean(options && options.fatal);
      super(label, fatal ? { ...options, fatal: false } : options);
      // Node without ICU decodes utf-8 and utf-16le only; utf-16le fatal degrades to replacement.
      this.#fatal = fatal && this.encoding === 'utf-8';
      if (fatal) note(this.#fatal ? 'TextDecoder.fatal' : `TextDecoder.fatal(${this.encoding}) degraded to replacement`);
    }

    get fatal() { return this.#fatal || super.fatal; }

    decode(input = undefined, options = undefined) {
      if (!this.#fatal) return super.decode(input, options);
      const chunk = bytesOf(input);
      const all = new Uint8Array(this.#pending.length + chunk.length);
      all.set(this.#pending); all.set(chunk, this.#pending.length);
      const keep = options && options.stream ? incompleteTail(all) : 0;
      this.#pending = all.slice(all.length - keep);
      if (!isUtf8(all.subarray(0, all.length - keep))) {
        this.#pending = new Uint8Array(0);
        super.decode(); // drop the inner decoder's partial state, as a fatal error does
        throw invalid();
      }
      return super.decode(input, options);
    }
  }
  Object.defineProperty(globalThis, 'TextDecoder', { value: TextDecoder, writable: true, configurable: true, enumerable: false });
  try { require('node:util').TextDecoder = TextDecoder; } catch { /* util is the same object everywhere */ }
})();

// --- Android: process start time without `ps -o lstart=`. letta-code reads a process's start
// time from /proc/<pid>/stat when process.platform is 'linux' and from `ps -o lstart= -p <pid>`
// otherwise (durable filesystem locks, process liveness). nodejs-mobile reports 'android', where
// toybox ps rejects `lstart` and forking the app process to run it takes seconds, so the first
// App Server connection on the device failed with "Timed out acquiring durable filesystem lock"
// (2 s budget, 250 ms per probe). Answer exactly that query from /proc, as the linux branch does;
// a gone pid throws like a failed ps, which every caller already maps to "unknown".
if (process.platform === 'android') {
  const childProcess = require('node:child_process');
  const { promisify } = require('node:util');
  const { readFileSync } = require('node:fs');
  const isStartQuery = (file, args) =>
    file === 'ps' && Array.isArray(args) && args.length === 4 && args[0] === '-o' && args[1] === 'lstart=' && args[2] === '-p';
  const startOf = (pid) => {
    const stat = readFileSync(`/proc/${Number(pid)}/stat`, 'utf8');
    return `${stat.slice(stat.lastIndexOf(')') + 2).split(' ')[19]}\n`;
  };
  const nativeSync = childProcess.execFileSync;
  const execFileSync = traced('ps -o lstart= (execFileSync)', function execFileSync(file, args, options) {
    const out = startOf(args[3]);
    return options && options.encoding && options.encoding !== 'buffer' ? out : Buffer.from(out);
  });
  childProcess.execFileSync = function execFileSyncCompat(file, args, options) {
    return isStartQuery(file, args) ? execFileSync.call(this, file, args, options) : nativeSync.apply(this, arguments);
  };
  const nativeExecFile = childProcess.execFile;
  const nativePromised = nativeExecFile[promisify.custom];
  const startAsync = traced('ps -o lstart= (execFile)', async (pid) => ({ stdout: startOf(pid), stderr: '' }));
  function execFileCompat(...args) { return nativeExecFile.apply(this, args); }
  Object.defineProperty(execFileCompat, promisify.custom, {
    value: (file, args, options) => (isStartQuery(file, args) ? startAsync(args[3]) : nativePromised(file, args, options)),
  });
  childProcess.execFile = execFileCompat;
  installed.push('android ps lstart');

  // Android: no hard links for apps. SELinux denies `link` on app data files (avc: denied { link }
  // ... app_data_file), so every fs.link fails with EACCES and the durable lock above spun until
  // its timeout even with the start-time fix. letta-code links only to publish a file exclusively
  // (EEXIST when the target exists): durable locks, the remote-settings and listener locks, channel
  // routes. When the kernel refuses the link, publish an exclusive copy instead (COPYFILE_EXCL:
  // EEXIST when the target exists). The lock's dev/ino checks only matter to a reader racing the
  // install, which runs synchronously inside this one process.
  const fs = require('node:fs');
  const { COPYFILE_EXCL } = fs.constants;
  const denied = (error) => Boolean(error) && (error.code === 'EACCES' || error.code === 'EPERM');
  const nativeLinkSync = fs.linkSync;
  fs.linkSync = function linkSync(existing, target) {
    try { return nativeLinkSync(existing, target); } catch (error) {
      if (!denied(error)) throw error;
      note('fs.link -> exclusive copy');
      return fs.copyFileSync(existing, target, COPYFILE_EXCL);
    }
  };
  const nativeLink = fs.link;
  fs.link = function link(existing, target, callback) {
    nativeLink(existing, target, (error) => {
      if (!denied(error)) { callback(error); return; }
      note('fs.link -> exclusive copy');
      fs.copyFile(existing, target, COPYFILE_EXCL, callback);
    });
  };
  const nativeLinkPromise = fs.promises.link;
  fs.promises.link = async function link(existing, target) {
    try { return await nativeLinkPromise(existing, target); } catch (error) {
      if (!denied(error)) throw error;
      note('fs.link -> exclusive copy');
      return fs.promises.copyFile(existing, target, COPYFILE_EXCL);
    }
  };
  installed.push('android fs.link');

  // Android: os.tmpdir() ignores TMPDIR. An app process is forked from zygote and inherits its
  // AT_SECURE auxv flag, so Node's safeGetenv() refuses every variable and os.tmpdir() falls back
  // to /tmp, which apps cannot write: the Bash tool failed with "EACCES: permission denied,
  // mkdtemp '/tmp/letta-background-XXXXXX'". process.env itself is fine, so read TMPDIR/TMP/TEMP
  // from it the way os.tmpdir() would. (NODE_OPTIONS is refused the same way, which is why the
  // launch also puts the heap flags on argv.)
  const os = require('node:os');
  const nativeTmpdir = os.tmpdir;
  os.tmpdir = function tmpdir() {
    const fromEnv = process.env.TMPDIR || process.env.TMP || process.env.TEMP;
    if (!fromEnv) return nativeTmpdir();
    note('os.tmpdir from process.env');
    return fromEnv.length > 1 && fromEnv.endsWith('/') ? fromEnv.slice(0, -1) : fromEnv;
  };
  installed.push('android os.tmpdir');
  // letta.js imports these from node:fs / node:fs/promises / node:child_process / node:os as ESM.
  require('node:module').syncBuiltinESMExports();
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
