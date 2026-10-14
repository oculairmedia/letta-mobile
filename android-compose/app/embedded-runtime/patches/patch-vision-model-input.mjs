// letta-mobile-nojhc: make custom OpenAI-compatible (lmstudio/*) models image-capable when they
// match a data-driven vision-model list. The embedded letta.js customOpenAICompatibleModel()
// hardcodes `input.modelId.includes("llava"|"vision"|"vl") ? ["text","image"] : ["text"]`, so a
// vision-capable custom model like MiniMax-M3 gets input:["text"] and the image is DROPPED before
// the provider request (model.input.includes("image") gate). This patch routes the capability
// through LETTA_CODE_VISION_MODEL_IDS (comma-separated, case-insensitive SUBSTRINGS — so "minimax"
// matches "MiniMax-M3"), mirroring the shim's LETTA_VISION_MODELS pattern so both surfaces stay
// consistent. Adding a vision model = one list entry, no code edit.
import { readFileSync, writeFileSync } from 'fs';
const file = process.argv[2];
let src = readFileSync(file, 'utf8');
const token = 'input: input.modelId.includes("llava") || input.modelId.includes("vision") || input.modelId.includes("vl") ? ["text", "image"] : ["text"],';
if (!src.includes(token)) {
  throw new Error('patch-vision-model-input: customOpenAICompatibleModel input token not found — letta.js shape changed');
}
const replacement =
  'input: ((() => { try { ' +
  'const h = String(input.modelId || "").toLowerCase(); ' +
  'const env = String(process.env.LETTA_CODE_VISION_MODEL_IDS || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean); ' +
  'if (env.some((p) => h.includes(p)) || /\\bvl\\b/.test(h) || h.includes("llava") || h.includes("vision")) return ["text", "image"]; ' +
  '} catch {} return ["text"]; })()),';
src = src.replace(token, replacement);
writeFileSync(file, src);
console.log('[embedded-lettacode] patched customOpenAICompatibleModel for env-driven vision capability');
