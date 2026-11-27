# letta-code runtime patches

`letta-code-patch-register.mjs` is passed to the Meridian App Server as
`NODE_OPTIONS=--import=file://…/letta-code-patch-register.mjs`; it registers
`letta-code-patch-loader.mjs`, which rewrites the deployed `letta.js` bundle at load time
(the `lcp-*` patches; the boot log reports `lcp-patches applied=N skipped=M`).

## Bundle parity

The patches apply to exactly one file: `LETTA_CLI_PATH_REAL`. Any child process that loads a
different `@letta-ai/letta-code/letta.js` (a `letta` resolved from PATH to a self-updated global
install, a stray symlink) runs UNPATCHED, which used to happen with no boot summary at all. The
loader now logs, once per stray bundle per process:

```
[letta-code-patch] ERROR: bundle mismatch: this process loaded <path> instead of the pinned LETTA_CLI_PATH_REAL=<pin>; …
```

Setting `LETTA_PATCH_STRICT_PATH=1` in the service env makes the loader refuse to load the wrong
bundle at all (the child dies at module load instead of running unpatched).
`scripts/deploy/letta-code-host-cutover.sh` checks the same parity during a cutover: the `letta`
the service PATH resolves must be the pin, and under the service's `NODE_OPTIONS` +
`LETTA_CLI_PATH_REAL` its `--version` output must carry `lcp-patches applied=<expected> skipped=0`.

Moved on 2026-11-04 from the deprecated `letta-code-parallel` repo
(`oculairmedia/letta-code-admin-shim`, `admin-shim/scripts/`), byte-identical to its deployed
commit `6354a12`. The deployed host still loads the old path until `/etc/meridian/appserver.env`
is repointed and `meridian-appserver` restarted.

## Host install

Install both `.mjs` files from a main commit into an immutable directory and point `current` at it,
the same shape as wrapper releases; `NODE_OPTIONS` loads `…/runtime-patches/current/`:

```bash
REL=/opt/meridian/runtime-patches/$(git rev-parse --short=9 HEAD)
install -D -m 0644 -t "$REL" scripts/appserver/runtime-patches/letta-code-patch-{register,loader}.mjs
ln -sfn "$REL" /opt/meridian/runtime-patches/current
systemctl restart meridian-appserver   # then confirm `lcp-patches applied=N skipped=0`
```

Model profiles also need the mod in the node's config dir, see [Model profiles](#model-profiles).

## Tests

The tests verify the patches against the deployed bundle (`LETTA_CLI_PATH_REAL`, else the newest
`/root/letta-code-*`). They need Node 24 and `tsx`:

```bash
cd scripts/appserver/runtime-patches
LETTA_CLI_PATH_REAL=/root/letta-code-<version>/node_modules/@letta-ai/letta-code/letta.js \
  node --import tsx/esm --test --test-concurrency=1 test/*.test.ts
```

## Host-neutral agent store

Agent state replicates between nodes in both directions, so these patches make the local store
write the same bytes on every machine, with no translation step:

| Patch | Effect |
| --- | --- |
| `lcp-conversation-uuid` | `LocalStore.nextConversationId` mints `local-conv-<uuid>` instead of a backend-wide counter. |
| `lcp-ui-msg-uuid` | `nextLocalMessageId` mints `ui-msg-<uuid>`. Readers match ids by equality or by the `ui-msg-` prefix; the only numeric parser, in the pre-pi transcript migration, reads a uuid suffix as 0. |
| `lcp-memory-lf` | Every memory-repo prepare sets `core.autocrlf=false` and, if the repo has no `.gitattributes`, writes `* text=auto eol=lf` and commits it once (new repos carry it in the init commit). Git failures are logged, never fatal. |
| `lcp-session-cwd` | The `messages.jsonl` session line records `LETTA_WORKSPACE_ID` instead of the App Server's absolute cwd, and leaves `cwd` out when the variable is unset. |
| `lcp-agent-description` | The default agent description is `Letta Code agent`, without the creating host's path. |
| `lcp-memory-dir-token` | The agent-info reminder shows the literal `$MEMORY_DIR` instead of this node's absolute memory root. |

`LETTA_WORKSPACE_ID` (optional) is a host-neutral name for the node's workspace, set in the App
Server environment next to `NODE_OPTIONS`. They add six to the boot count. The tests are in
`test/patch-loader-host-neutral.test.ts`.

Skills and per-agent settings:

| Patch | Effect |
| --- | --- |
| `lcp-memory-dir-expand` | File tools (Read, Write, Edit, Glob, Grep and the rest of `expandPathArgs`' list) expand `$MEMORY_DIR`, `${MEMORY_DIR}` and `$LETTA_MEMORY_DIR` to the running agent's memory root. Stock letta-code expands them only from the App Server's own environment, where they are unset, so a literal `$MEMORY_DIR/...` path resolved against the cwd. An environment that already sets them wins. |
| `lcp-skill-dir` | Skill content (`<SKILL_DIR>`, `${CLAUDE_SKILL_DIR}` and the `Skill directory:` line, all persisted in the `<skill_content>` tool result) names a skill under the agent's memory root as `$MEMORY_DIR/skills/<name>`. It applies only together with `lcp-memory-dir-expand`. Bundled, global and project skills keep their install path: that is history, not agent state, and no node acts on another node's install path. |
| `lcp-system-prompt-tracking` | Whether the agent's system prompt is a managed preset (preset, hash, letta-code version) or custom moves from the node-local `settings.json` into the agent record as `system_prompt_tracking`, so it replicates. The getters read the record first and fall back to `settings.json`, copying an existing entry into the record on first read. The setters still write `settings.json` too, so tracking a node set itself survives a rollback on that node; a node that only received the tracking through the record has no `settings.json` entry, and after a rollback its stock getters read the prompt as custom and stop upgrading it (copy each agent's `system_prompt_tracking` into that node's `settings.json` before reverting the loader if that matters). Every node that shares agent state must run the loader with this patch before the replicated field is relied on: stock `normalizeAgentRecord` rebuilds the record from a whitelist, so a node without it drops `system_prompt_tracking` on its next save of that agent. Local backend only. |

The `memfs` flag in `settings.json` `agents[]` stays node-local: on the local backend it holds no
intent of its own. The opt-out is the replicated `memfs:disabled` tag (written by `create_agent`
with `memfs: false`, next to the flag), which `LocalBackend.isMemfsEnabledForAgent` reads
directly, and the flag is otherwise refilled from the node's process state
(`isMemfsEnabledOnServer`) when a memory command runs. Until then a node can read it as unset,
which is a stale cache rather than lost state. These three patches add three to the boot count. The tests are in
`test/patch-loader-host-neutral-skills.test.ts`.

## Model profiles

Replicated agent and conversation records store a model profile handle, `profile/<id>`, never a
provider handle. Each node maps the profile to its own provider handle at turn time; nothing is
written back. Two pieces do this:

| Piece | Where it lives | Replicates? |
| --- | --- | --- |
| `mods/profile-provider.mjs` (letta-code mod) | `$LETTA_CONFIG_DIR/mods/profile-provider.mjs` on every node | No: node-local |
| `lcp-model-profile` (runtime patch, one counted patch, eight anchors applied together) | in `letta-code-patch-loader.mjs` | No: node-local. The user cap field it writes, `lcp_user_context_window_limit`, replicates with the record |
| Profile catalog, `LETTA_MODEL_PROFILES` | any path, named in the App Server environment | Yes: shared, the same on every node |
| Profile bindings, `LETTA_MODEL_PROFILE_BINDINGS` | any node-local path, named in the App Server environment | No: node-local, never replicated |

The mod registers a pi provider named `profile` with one model per catalog entry, so
`profile/<id>` passes letta-code's handle normalisation, persists as written, shows in
`list_models`, and gets its write-time defaults from the catalog. It also installs the resolver
that `lcp-model-profile` calls at the head of `resolvePiModelForAgent`, the single site where a
stored handle becomes a provider, model and auth (turns, context preflight, compaction and
summaries). The bound real handle then resolves in-process, with that provider's own auth,
quirks and real model id. Install the mod only in the node-local `$LETTA_CONFIG_DIR/mods`, never
in an agent's memfs `mods/` directory: that one replicates, and its mods do not get the
providers capability.

Catalog (shared):

```json
{
  "main": { "name": "Main", "contextWindow": 200000, "maxTokens": 16000, "reasoning": false, "input": ["text"] },
  "fast": { "name": "Fast", "contextWindow": 64000, "maxTokens": 4096 }
}
```

Every field but the key is optional (defaults: name = id, contextWindow 128000, maxTokens 8192,
reasoning false, input `["text"]`).

Bindings (node-local):

```json
{
  "main": { "handle": "anthropic/claude-opus-5-5" },
  "fast": { "handle": "lmstudio/qwen3-30b", "model_settings": { "context_window_limit": 32000 } }
}
```

`handle` is a provider handle this node can serve. `model_settings` (optional) is laid over the
stored settings for this node only. The stored `provider_type`, `context_window_limit` and
`max_tokens` are write-time defaults for the profile provider, so they are dropped before the
overlay and the real provider derives its own; other stored settings (reasoning, temperature)
are profile-level intent and are kept.

**The user's context cap.** `/context-limit` (alias `/set-max-context`) stores the cap in the
same `context_window_limit` that every model switch fills with the catalog default, so the stored
value cannot be trusted on a profile turn. `lcp-model-profile` therefore also writes the user's
cap to its own field, `lcp_user_context_window_limit`, in the conversation record (the agent
record for the default conversation), next to the stock field, which is still written as before.
On a profile turn the effective window is the smallest of: the user's cap, the binding's
`model_settings.context_window_limit` if it sets one, and the bound model's own window. With no
user cap the turn runs exactly as before (the binding's window, or the bound model's). The field
is valid only while the stock field holds the same value: a reset (`/context-limit` with no
value) removes it, and so does any later write that stores a different window there (a model
switch to another default), so the cap is lost exactly when letta-code loses it today; a switch
that keeps the stored window keeps the cap. Non-profile conversations are untouched: their turn
settings never carry the field. Local backend only.

**Every node that shares agent state must run a loader with this change before a profile is
bound.** Stock `normalizeAgentRecord` rebuilds the agent record from a whitelist, so a node
without it drops the default conversation's cap from the agent record on its next save of that
agent. Conversation records keep unknown fields, but a stock node that rewrites
`context_window_limit` leaves a stale cap behind; the reader ignores the field whenever it differs
from the stock field, so a stale cap never applies.

Failure is loud, never a silent fallback to another provider: a `profile/<id>` turn throws when
the mod is not installed (no resolver), when the profile has no binding on this node, or when a
binding points at another profile. If the patch itself fails open, the mod's provider points at a
dead address and the turn fails there.

**Write profile handles only through the App Server** (`runtime_start` `create_agent`,
`update_model`). The App Server loads mods before it handles any model write. `letta model set`
and `letta model list` do not load mods and reject the handle, and headless mode (`letta -m
profile/<id>`) loads mods after it handles `-m`, so it persists a mangled handle
(`openai/profile/<id>` with `provider_type: "openai"`).

`lcp-model-profile` adds one to the boot count; its anchors apply together or not at all. The
tests, including a two-node App Server contract test with fake upstreams, are in
`test/patch-loader-model-profile.test.ts`; the user cap tests are in
`test/patch-loader-model-profile-user-cap.test.ts`.
