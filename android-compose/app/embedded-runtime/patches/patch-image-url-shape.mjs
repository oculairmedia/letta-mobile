// letta-mobile-nojhc: letta.js's chat/completions image builder emits a NON-STANDARD camelCase
// `imageUrl: "data:..."` part. OpenAI-compatible providers (MiniMax, lmstudio, etc.) expect the
// standard `image_url: { url: "data:..." }` and reject the camelCase form ("invalid params,
// image_url is empty"), surfacing as the opaque "999 (1000)" turn error. Rewrite both camelCase
// emit sites to the standard shape so images actually reach the provider. PROVEN against the live
// :8082 proxy: standard shape -> 200 + description; camelCase -> bad_request "image_url is empty".
import { readFileSync, writeFileSync } from 'fs';
const file = process.argv[2];
let src = readFileSync(file, 'utf8');
// Whitespace-agnostic: convert every chat/completions camelCase
// image_url part into the OpenAI-standard
// type:"image_url", image_url:{ url: <data-url-template> }.
// Matches the data-URL template body without pinning indentation,
// so both emit sites convert regardless of minifier whitespace.
const re = /type:\s*"image_url",\s*imageUrl:\s*(`data:[^`]*`)/g;
let changed = 0;
src = src.replace(re, (_m, urlExpr) => {
  changed++;
  return 'type: "image_url", image_url: { url: ' + urlExpr + ' }';
});
if (changed === 0) {
  throw new Error('patch-image-url-shape: no camelCase imageUrl tokens found — letta.js shape changed');
}
writeFileSync(file, src);
console.log('[embedded-lettacode] rewrote ' + changed + ' camelCase imageUrl -> standard image_url{url}');
