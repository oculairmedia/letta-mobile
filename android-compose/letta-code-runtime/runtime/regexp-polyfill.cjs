// Shipped next to letta.js and preloaded with `node --require`. Runtime guard for ICU-less V8:
// letta.js also builds regexes at RUNTIME (e.g. the oniguruma-to-js highlighter calls new RegExp
// with \p{...} patterns), which the static babel pass (patches/transform-unicode-property.mjs)
// cannot reach. Wraps RegExp to retry failed compilations through regexpu-core with property
// escapes transformed, and stubs the Intl surface letta.js touches.
'use strict';
// nodejs-mobile libnode has no ICU: /\p{...}/u throws "Invalid
// property name". Retry failed patterns through regexpu-core.
const rewritePattern = require('regexpu-core');
const NativeRegExp = RegExp;
function rewriteOptions(flags) {
  return {
    unicodePropertyEscapes: 'transform',
    unicodeSetsFlag: flags.includes('v') ? 'transform' : false,
  };
}
function PatchedRegExp(pattern, flags) {
  try {
    return flags === undefined
      ? new NativeRegExp(pattern)
      : new NativeRegExp(pattern, flags);
  } catch (originalError) {
    if (pattern instanceof NativeRegExp && flags === undefined) throw originalError;
    try {
      const sourceFlags = flags !== undefined
        ? String(flags)
        : (pattern instanceof NativeRegExp ? pattern.flags : '');
      const sourcePattern = pattern instanceof NativeRegExp ? pattern.source : String(pattern);
      const rewritten = rewritePattern(sourcePattern, sourceFlags, rewriteOptions(sourceFlags));
      const outFlags = sourceFlags.replace('v', 'u');
      return new NativeRegExp(rewritten, outFlags);
    } catch (_) {
      throw originalError;
    }
  }
}
PatchedRegExp.prototype = NativeRegExp.prototype;
Object.setPrototypeOf(PatchedRegExp, NativeRegExp);
globalThis.RegExp = PatchedRegExp;

// ICU-less node also lacks the entire Intl namespace. Provide the
// minimal surface letta.js touches. Segmenter: code-point graphemes
// (good enough for width/wrapping on device). Formatters: passthrough.
if (typeof globalThis.Intl === 'undefined') {
  class Segmenter {
    constructor(_locale, options) { this.granularity = (options && options.granularity) || 'grapheme'; }
    segment(input) {
      const str = String(input);
      const segments = [];
      if (this.granularity === 'word') {
        const re = /\S+|\s+/g; let m;
        while ((m = re.exec(str)) !== null) {
          segments.push({ segment: m[0], index: m.index, input: str, isWordLike: /\S/.test(m[0]) });
        }
      } else {
        let index = 0;
        for (const ch of str) { segments.push({ segment: ch, index, input: str }); index += ch.length; }
      }
      segments[Symbol.iterator] = Array.prototype[Symbol.iterator];
      segments.containing = (i) => segments.find(s => s.index <= i && i < s.index + s.segment.length);
      return segments;
    }
  }
  class NumberFormat {
    constructor() {}
    format(n) { return String(n); }
    formatToParts(n) { return [{ type: 'integer', value: String(n) }]; }
    resolvedOptions() { return { locale: 'en-US' }; }
  }
  class DateTimeFormat {
    constructor() {}
    format(d) { return new Date(d ?? Date.now()).toISOString(); }
    formatToParts(d) { return [{ type: 'literal', value: this.format(d) }]; }
    resolvedOptions() { return { locale: 'en-US', timeZone: 'UTC' }; }
  }
  class Collator {
    constructor() {}
    compare(a, b) { return a < b ? -1 : a > b ? 1 : 0; }
    resolvedOptions() { return { locale: 'en-US' }; }
  }
  class PluralRules {
    constructor() {}
    select(n) { return n === 1 ? 'one' : 'other'; }
    resolvedOptions() { return { locale: 'en-US' }; }
  }
  class RelativeTimeFormat {
    constructor() {}
    format(value, unit) { return value + ' ' + unit + (Math.abs(value) === 1 ? '' : 's'); }
    formatToParts(value, unit) { return [{ type: 'literal', value: this.format(value, unit) }]; }
    resolvedOptions() { return { locale: 'en-US' }; }
  }
  class ListFormat {
    constructor() {}
    format(list) { return Array.from(list).join(', '); }
    resolvedOptions() { return { locale: 'en-US' }; }
  }
  const supported = (cls) => { cls.supportedLocalesOf = () => ['en-US']; return cls; };
  globalThis.Intl = {
    Segmenter: supported(Segmenter),
    NumberFormat: supported(NumberFormat),
    DateTimeFormat: supported(DateTimeFormat),
    Collator: supported(Collator),
    PluralRules: supported(PluralRules),
    RelativeTimeFormat: supported(RelativeTimeFormat),
    ListFormat: supported(ListFormat),
    getCanonicalLocales: (l) => (Array.isArray(l) ? l : l ? [l] : []),
  };
}
