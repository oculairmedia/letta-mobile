// Node-local letta-code mod that makes `profile/<id>` a first-class model handle.
//
// Install it as $LETTA_CONFIG_DIR/mods/profile-provider.mjs on every node. That directory is
// node-local; never put it in an agent's memfs `mods/` directory, which replicates (and whose
// mods do not get the providers capability anyway).
//
// Two files, both named by environment variables (see docs/deploy/appserver-runtime-patches.md):
//   LETTA_MODEL_PROFILES          the shared profile catalog, the same on every node:
//                                 { "<id>": { name?, contextWindow?, maxTokens?, reasoning?, input? } }
//   LETTA_MODEL_PROFILE_BINDINGS  this node's bindings, never replicated:
//                                 { "<id>": { handle: "<provider>/<model>", model_settings?: {...} } }
//
// What it does:
//  1. Registers the pi provider `profile` with one model per catalog entry. `profile/<id>` then
//     passes handle normalisation unchanged, persists as written, shows in list_models, and
//     write-time defaults (context window, max tokens) come from the catalog.
//  2. Installs globalThis.__lcpResolveModelProfile. The lcp-model-profile runtime patch calls it
//     at the head of resolvePiModelForAgent and resolves the bound real handle in-process, so
//     the real provider's auth, quirks and model id apply. Nothing is written back to the store.
//
// The provider's own endpoint is a dead address on purpose: if the runtime patch is missing,
// a profile turn fails loudly there instead of silently reaching some other provider.
import fs from "node:fs";

const PROVIDER = "profile";
const PREFIX = `${PROVIDER}/`;
const UNBOUND_BASE_URL = "http://127.0.0.1:9/unbound-model-profile";
// Stored settings written at create/update time that describe the profile provider, not the
// bound one. The real provider derives its own; everything else (reasoning, temperature...)
// is profile-level intent and is kept.
const PROVIDER_BOUND_SETTINGS = ["provider_type", "context_window_limit", "max_tokens"];

/**
 * Reads a JSON object from the file an environment variable names.
 *
 * @param {string} envName
 * @returns {Record<string, any>}
 */
function readJsonObject(envName) {
  const file = process.env[envName];
  if (!file) return {};
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
    console.error(`[profile-provider] ${envName}=${file} is not a JSON object; ignoring it`);
  } catch (error) {
    console.error(`[profile-provider] cannot read ${envName}=${file}: ${error instanceof Error ? error.message : String(error)}`);
  }
  return {};
}

/**
 * Builds the resolver the lcp-model-profile patch calls. It throws for an unbound profile so
 * the turn fails with a message that names the missing binding.
 *
 * @param {Record<string, any>} bindings
 * @returns {(handle: string, modelSettings?: Record<string, unknown>) => ({ handle: string; modelSettings: Record<string, unknown> } | undefined)}
 */
function createResolver(bindings) {
  return (handle, modelSettings) => {
    if (typeof handle !== "string" || !handle.startsWith(PREFIX)) return undefined;
    const id = handle.slice(PREFIX.length);
    const binding = Object.hasOwn(bindings, id) ? bindings[id] : undefined;
    if (!binding || typeof binding.handle !== "string" || !binding.handle || binding.handle.startsWith(PREFIX)) {
      throw new Error(
        `Model profile ${handle} is not bound to a provider handle on this node. ` +
          `Add "${id}": { "handle": "<provider>/<model>" } to LETTA_MODEL_PROFILE_BINDINGS.`,
      );
    }
    const intent = { ...(modelSettings ?? {}) };
    for (const key of PROVIDER_BOUND_SETTINGS) delete intent[key];
    return { handle: binding.handle, modelSettings: { ...intent, ...(binding.model_settings ?? {}) } };
  };
}

/**
 * letta-code mod entry point.
 *
 * @param {any} letta the mod API letta-code passes to activate()
 */
export default function activate(letta) {
  if (!letta?.capabilities?.providers) return undefined;
  const profiles = readJsonObject("LETTA_MODEL_PROFILES");
  const bindings = readJsonObject("LETTA_MODEL_PROFILE_BINDINGS");

  globalThis.__lcpResolveModelProfile = createResolver(bindings);

  const models = Object.entries(profiles).map(([id, profile]) => ({
    id,
    name: profile?.name ?? id,
    reasoning: Boolean(profile?.reasoning),
    input: Array.isArray(profile?.input) ? profile.input : ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: profile?.contextWindow ?? 128000,
    maxTokens: profile?.maxTokens ?? 8192,
  }));

  return letta.providers.register(PROVIDER, {
    name: "Model profile",
    api: "openai-completions",
    baseUrl: UNBOUND_BASE_URL,
    connect: false,
    models,
  });
}
