// Test-only preload for the embedded-runtime-node18 CI job: makes a desktop Node 18 look like
// nodejs-mobile 18.20.4 to JS code. Never shipped in the APK. Loaded FIRST, before the shipped
// preloads, because it models the platform they run on.
//
// nodejs-mobile is built --with-intl=none. A stock Node 18 has full ICU, so without this the job
// boots a runtime the phone cannot: the first 0.34.14 device crash (`TypeError [ERR_NO_ICU]:
// "fatal" option is not supported on Node.js compiled without ICU` from `new TextDecoder(...)` in
// letta.js) passed CI. Each emulation below matches what the device's own node prints (probe:
// bin/node on a Pixel 9 Pro, 2026-10-16) and what Node 18.20.4 does when hasIntl is false:
// - process.platform is "android" (sharp / node-pty / ripgrep resolve android-<arch>, miss, and
//   take the fallbacks the device takes);
// - Intl is gone and process.versions has no icu/unicode/cldr/tz;
// - TextDecoder is lib/internal/encoding.js makeTextDecoderJS: only utf-8 / utf-16le, and
//   { fatal: true } throws ERR_NO_ICU (the util export is the same class);
// - buffer.transcode does not exist;
// - V8 without i18n: String#normalize returns the string unchanged, localeCompare compares UTF-16
//   code units, toLocaleUpperCase/LowerCase ignore the locale, Number/Date toLocale*String are
//   toString/toDateString/toTimeString.
// - Android app sandbox, not Node: SELinux denies `link` on app data files, so every fs.link*
//   fails with EACCES; toybox `ps` has no `-o lstart=` and forking the app process to run it
//   outlives letta-code's 250 ms probe. Together these made the first App Server connection on the
//   phone fail with "Timed out acquiring durable filesystem lock".
//   App processes also inherit zygote's AT_SECURE, so Node's safeGetenv() refuses every variable:
//   os.tmpdir() is /tmp whatever TMPDIR says, and /tmp is not writable by apps (the Bash tool's
//   mkdtemp failed with EACCES).
//   The App Server runs IN the app process on libnode, so process.execPath is the app's own
//   executable, /system/bin/app_process64 (ART), not a node: letta-code's
//   spawn(process.execPath, [image-resize-worker.js, …]) started ART with node arguments and it
//   aborted (image sends failed with "Image worker exited from signal SIGABRT"). execPath here is
//   a stub that dies of SIGABRT the same way, silently (ART writes to logcat, not stderr).
// It cannot remove V8's ICU-backed \p{...} regex support, so property-escape coverage still needs
// a device run (the shipped transform-unicode-property patch covers the bundle's literals).
'use strict';
Object.defineProperty(process, 'platform', { value: 'android', configurable: true });
try { delete globalThis.Intl; } catch { /* non-configurable on some builds: keep going */ }
for (const key of ['icu', 'unicode', 'cldr', 'tz']) delete process.versions[key];

// Node's internal error shape: `.code`, and a stack that starts `TypeError [CODE]: message`.
function nodeError(Type, code, message) {
  const error = new Type(message);
  error.code = code;
  error.stack = error.stack.replace(Type.name, `${Type.name} [${code}]`);
  return error;
}

const IcuTextDecoder = globalThis.TextDecoder;
class TextDecoder extends IcuTextDecoder {
  constructor(label = 'utf-8', options = undefined) {
    // Resolve the label the WHATWG way first (bad labels throw the same error either way).
    const probe = new IcuTextDecoder(label);
    if (probe.encoding !== 'utf-8' && probe.encoding !== 'utf-16le') throw nodeError(RangeError, 'ERR_ENCODING_NOT_SUPPORTED', `The "${label}" encoding is not supported`);
    if (options && options.fatal) throw nodeError(TypeError, 'ERR_NO_ICU', '"fatal" option is not supported on Node.js compiled without ICU');
    super(label, options);
  }
}
Object.defineProperty(globalThis, 'TextDecoder', { value: TextDecoder, writable: true, configurable: true, enumerable: false });
require('node:util').TextDecoder = TextDecoder;

delete require('node:buffer').transcode;

const SP = String.prototype;
const FORMS = new Set(['NFC', 'NFD', 'NFKC', 'NFKD']);
const define = (target, key, fn) => Object.defineProperty(target, key, { value: fn, writable: true, configurable: true, enumerable: false });
define(SP, 'normalize', function normalize(form = 'NFC') {
  if (!FORMS.has(String(form))) throw new RangeError(`The normalization form should be one of NFC, NFD, NFKC, NFKD.`);
  return String(this);
});
define(SP, 'localeCompare', function localeCompare(that) {
  const a = String(this); const b = String(that);
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) { const d = a.charCodeAt(i) - b.charCodeAt(i); if (d !== 0) return d; }
  return a.length - b.length;
});
define(SP, 'toLocaleUpperCase', function toLocaleUpperCase() { return SP.toUpperCase.call(this); });
define(SP, 'toLocaleLowerCase', function toLocaleLowerCase() { return SP.toLowerCase.call(this); });
define(Number.prototype, 'toLocaleString', function toLocaleString() { return Number.prototype.toString.call(this); });
define(BigInt.prototype, 'toLocaleString', function toLocaleString() { return BigInt.prototype.toString.call(this); });
define(Date.prototype, 'toLocaleString', function toLocaleString() { return Date.prototype.toString.call(this); });
define(Date.prototype, 'toLocaleDateString', function toLocaleDateString() { return Date.prototype.toDateString.call(this); });
define(Date.prototype, 'toLocaleTimeString', function toLocaleTimeString() { return Date.prototype.toTimeString.call(this); });

// --- Android app sandbox (see the header).
const fs = require('node:fs');
const childProcess = require('node:child_process');
const { promisify } = require('node:util');
const linkDenied = (existing, target) => Object.assign(new Error(`EACCES: permission denied, link '${existing}' -> '${target}'`), { code: 'EACCES' });
fs.linkSync = function linkSync(existing, target) { throw Object.assign(linkDenied(existing, target), { errno: -13, syscall: 'link' }); };
fs.link = function link(existing, target, callback) { process.nextTick(callback, Object.assign(linkDenied(existing, target), { errno: -13, syscall: 'link' })); };
fs.promises.link = async function link(existing, target) { throw Object.assign(linkDenied(existing, target), { errno: -13, syscall: 'link' }); };

const isStartQuery = (file, args) => file === 'ps' && Array.isArray(args) && args.includes('lstart=');
const PS_PROBE_MS = 250; // the device's fork outlives letta-code's PROCESS_START_PROBE_TIMEOUT_MS
const toyboxPs = () => Object.assign(new Error("Command failed: ps -o lstart=\nps: bad -o 'lstart='"), { status: 1, stderr: "ps: bad -o 'lstart='\n" });
const nativeExecFileSync = childProcess.execFileSync;
childProcess.execFileSync = function execFileSync(file, args, options) {
  if (!isStartQuery(file, args)) return nativeExecFileSync.apply(this, arguments);
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, PS_PROBE_MS);
  throw toyboxPs();
};
const nativeExecFile = childProcess.execFile;
const nativePromised = nativeExecFile[promisify.custom];
function execFile(...args) { return nativeExecFile.apply(this, args); }
Object.defineProperty(execFile, promisify.custom, {
  value: (file, args, options) => (isStartQuery(file, args)
    ? new Promise((_, reject) => setTimeout(() => reject(toyboxPs()), PS_PROBE_MS))
    : nativePromised(file, args, options)),
});
childProcess.execFile = execFile;

const os = require('node:os');
const path = require('node:path');
// The ART stand-in (see the header). Written once per runner (rename is atomic, so a process that
// execs it never sees a half-written file) under the runner's own temp dir.
const appProcess = path.join(os.tmpdir(), `android-emulation-${process.getuid()}`, 'app_process64');
if (!fs.existsSync(appProcess)) {
  fs.mkdirSync(path.dirname(appProcess), { recursive: true });
  const staging = `${appProcess}.${process.pid}`;
  fs.writeFileSync(staging, '#!/bin/sh\n# ART started with node arguments: Runtime aborting\nkill -ABRT $$\n', { mode: 0o755 });
  fs.renameSync(staging, appProcess);
}
process.execPath = appProcess;

os.tmpdir = function tmpdir() { return '/tmp'; }; // safeGetenv('TMPDIR') is refused under AT_SECURE
// The device's /tmp; a runner whose own TMPDIR lives under /tmp keeps that.
const sandboxTmp = process.env.TMPDIR;
const underTmp = (prefix) => typeof prefix === 'string' && (prefix === '/tmp' || prefix.startsWith('/tmp/')) &&
  !(sandboxTmp && prefix.startsWith(`${sandboxTmp}/`));
const tmpDenied = (prefix) => Object.assign(new Error(`EACCES: permission denied, mkdtemp '${prefix}XXXXXX'`), { code: 'EACCES', errno: -13, syscall: 'mkdtemp' });
const nativeMkdtempSync = fs.mkdtempSync;
fs.mkdtempSync = function mkdtempSync(prefix, options) { if (underTmp(prefix)) throw tmpDenied(prefix); return nativeMkdtempSync(prefix, options); };
const nativeMkdtemp = fs.mkdtemp;
fs.mkdtemp = function mkdtemp(prefix, options, callback) {
  const cb = typeof options === 'function' ? options : callback;
  if (underTmp(prefix)) { process.nextTick(cb, tmpDenied(prefix)); return; }
  nativeMkdtemp.apply(this, arguments);
};
const nativeMkdtempPromise = fs.promises.mkdtemp;
fs.promises.mkdtemp = async function mkdtemp(prefix, options) { if (underTmp(prefix)) throw tmpDenied(prefix); return nativeMkdtempPromise(prefix, options); };
require('node:module').syncBuiltinESMExports();
