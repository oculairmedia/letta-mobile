// letta-mobile-nojhc: neutralize letta.js's image-resize path on device. resizeImageIfNeeded()
// calls getImageDimensions() (sharp) + the magick CLI — neither loads on nodejs-mobile, so an image
// send would die with "999 (1000)" in failureMode:strict. The composer already downsamples
// client-side, so the resize is redundant — rewrite it to a passthrough.
import { readFileSync, writeFileSync } from 'fs';
import { patchTarget } from './patch-target.mjs';
const file = patchTarget();
let src = readFileSync(file, 'utf8');
const marker = 'async function resizeImageIfNeeded(buffer, inputMediaType) {';
const idx = src.indexOf(marker);
if (idx === -1) {
  throw new Error('bypass-image-resize: resizeImageIfNeeded marker not found — letta.js shape changed');
}
let i = idx + marker.length;
let depth = 1;
while (i < src.length && depth > 0) {
  const ch = src[i];
  if (ch === '{') depth++;
  else if (ch === '}') depth--;
  i++;
}
if (depth !== 0) throw new Error('bypass-image-resize: unbalanced braces');
const replacement = marker +
  '\n  // letta-mobile-nojhc: device passthrough, no sharp/magick.\n' +
  '  return { data: buffer.toString("base64"), mediaType: inputMediaType, width: 0, height: 0, resized: false };\n}';
src = src.slice(0, idx) + replacement + src.slice(i);
writeFileSync(file, src);
console.log('[embedded-lettacode] bypassed resizeImageIfNeeded (sharp/magick) for on-device images');
