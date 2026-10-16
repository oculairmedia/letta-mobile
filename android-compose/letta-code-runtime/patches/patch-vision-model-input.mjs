// letta-mobile-nojhc / by4vm.110.2: make OpenAI-compatible local-endpoint models (lmstudio/*, the
// on-device LiteRT bridge, the remote proxy) image-capable when their id matches the data-driven
// vision list in LETTA_CODE_VISION_MODEL_IDS (comma-separated, case-insensitive substrings, so
// "minimax" matches "MiniMax-M3"). letta.js 0.34.x builds these models in the local-endpoint
// provider's buildModel(), which marks a model image-capable only when the endpoint's LM Studio
// native /api/v0/models metadata says so (type "vlm" / capabilities "vision"). The proxies and the
// LiteRT bridge serve only /v1/models, so every model comes out text-only and letta.js replaces
// each image with "(image omitted: model does not support images)". A models[].input entry in
// providers/auth.json is NOT read for these providers (verified on 0.34.14), so this patch stays.
import { readFileSync, writeFileSync } from 'fs';
import { patchTarget } from './patch-target.mjs';
const file = patchTarget();
const src = readFileSync(file, 'utf8');
const token = 'input: metadata.vision === true ? ["text", "image"] : ["text"],';
const count = src.split(token).length - 1;
if (count !== 1) {
  throw new Error(`patch-vision-model-input: expected 1 local-endpoint buildModel input token, found ${count} — letta.js shape changed`);
}
const replacement =
  'input: metadata.vision === true || String(process.env.LETTA_CODE_VISION_MODEL_IDS || "").split(",")' +
  '.map((s) => s.trim().toLowerCase()).filter(Boolean).some((p) => String(metadata.id || "").toLowerCase().includes(p))' +
  ' ? ["text", "image"] : ["text"],';
writeFileSync(file, src.replace(token, replacement));
console.log('[embedded-lettacode] patched local-endpoint buildModel for env-driven vision capability');
