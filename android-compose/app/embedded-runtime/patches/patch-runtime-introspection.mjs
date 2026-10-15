// letta-mobile-84a59: @letta-ai/letta-code 0.26.1 has generic system-reminders but not the shim's
// runtime-introspection strings ("Context utilization", "Serving model", "Session role", "Model
// changed"). The canonical shim module ships from src/main/embedded-lettacode (preloaded via
// embedded-runtime-introspection-preload.cjs); this patch makes the embedded stream-json turn
// boundary call it. Every occurrence of the needle is rewritten; finding none fails the build.
import { readFileSync, writeFileSync } from 'fs';
import { patchTarget } from './patch-target.mjs';
const file = patchTarget();
const src = readFileSync(file, 'utf8');
const needle =
  '        const enrichedContent = prependReminderPartsToContent(userContent, sharedReminderParts);\n' +
  '        let currentInput = [{';
const replacement =
  '        let enrichedContent = prependReminderPartsToContent(userContent, sharedReminderParts);\n' +
  '        if (typeof globalThis.__lettaMobileBuildRuntimeIntrospectionContent === "function") {\n' +
  '          enrichedContent = await globalThis.__lettaMobileBuildRuntimeIntrospectionContent(agent2.id, conversationId, enrichedContent);\n' +
  '        }\n' +
  '        let currentInput = [{';
const patched = src.split(needle).join(replacement);
if (patched === src) {
  throw new Error('Failed to patch letta.js runtime-introspection turn boundary.');
}
writeFileSync(file, patched);
console.log('[embedded-lettacode] patched stream-json turn boundary for runtime introspection');
