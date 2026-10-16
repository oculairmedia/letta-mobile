// Test-only preload for the embedded-runtime-node18 CI job: makes a desktop Node 18 look like
// nodejs-mobile to JS code. process.platform is "android" (so sharp / node-pty / ripgrep resolve
// android-<arch> packages, miss, and take the fallbacks the device takes), and Intl is removed
// (nodejs-mobile is built --with-intl=none). It cannot remove V8's ICU-backed \p{...} regex
// support, so property-escape coverage still needs a device run. Never shipped in the APK.
'use strict';
Object.defineProperty(process, 'platform', { value: 'android', configurable: true });
try { delete globalThis.Intl; } catch { /* non-configurable on some builds: keep going */ }
