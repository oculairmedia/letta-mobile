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
// It cannot remove V8's ICU-backed \p{...} regex support, so property-escape coverage still needs
// a device run (the shipped transform-unicode-property patch covers the bundle's literals).
'use strict';
Object.defineProperty(process, 'platform', { value: 'android', configurable: true });
try { delete globalThis.Intl; } catch { /* non-configurable on some builds: keep going */ }
for (const key of ['icu', 'unicode', 'cldr', 'tz']) delete process.versions[key];

const noIcu = (what) => Object.assign(new TypeError(`${what} is not supported on Node.js compiled without ICU`), { code: 'ERR_NO_ICU' });
const notSupported = (label) => Object.assign(new RangeError(`The "${label}" encoding is not supported`), { code: 'ERR_ENCODING_NOT_SUPPORTED' });

const IcuTextDecoder = globalThis.TextDecoder;
class TextDecoder extends IcuTextDecoder {
  constructor(label = 'utf-8', options = undefined) {
    // Resolve the label the WHATWG way first (bad labels throw the same error either way).
    const probe = new IcuTextDecoder(label);
    if (probe.encoding !== 'utf-8' && probe.encoding !== 'utf-16le') throw notSupported(`${label}`);
    if (options && options.fatal) throw noIcu('"fatal" option');
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
