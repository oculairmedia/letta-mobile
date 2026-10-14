// nodejs-mobile's libnode is built WITHOUT ICU (--with-intl=none), so V8 rejects unicode
// property escapes (/\p{...}/u) with "Invalid property name" SyntaxError at module load.
// Desktop Node parses these fine, which is why letta.js "boots on Node 18" on a workstation but
// SIGABRTs inside node::Start on device. Transpile property escapes to plain character classes at
// asset-prep time (standard nodejs-mobile prior art via regexpu/babel). See letta-mobile-7to5o.
// Runs from the npm work dir so `@babel/core` resolves from its node_modules.
import { transformAsync } from '@babel/core';
import { readFileSync, writeFileSync } from 'fs';
const file = process.argv[2];
const src = readFileSync(file, 'utf8');
const result = await transformAsync(src, {
  plugins: ['@babel/plugin-transform-unicode-property-regex'],
  compact: false,
  babelrc: false,
  configFile: false,
  parserOpts: { sourceType: 'unambiguous' },
});
writeFileSync(file, result.code);
console.log('[embedded-lettacode] transpiled unicode property escapes in ' + file);
