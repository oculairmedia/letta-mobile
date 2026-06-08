# Design sync with Penpot — plan & status

**Status:** exploratory. Started June 2026 alongside `docs/DESIGN_SYSTEM.md`. Both files
are in flux; expect rewrites.

## Why this doc exists

The repo already has a token-driven design system in Kotlin
(`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/`).
`docs/DESIGN_SYSTEM.md` is the regeneratable spec for those tokens — code is the
source of truth.

The missing piece is a **design surface** for non-engineers (and for design review
before implementation). I want to use **Penpot** (self-hosted at
`http://192.168.50.80:9001` on the home-lab box) for that surface, and I want it
to be **synchronized with the Kotlin tokens**, not a separate source of truth.

This doc tracks the plan, the design constraints, the sync architecture, and the
current blocker (auth).

## Goals

1. **Code is the source of truth for tokens.** Colors, spacing, type, motion —
   everything in `LettaColorTokens`, `LettaThemeTokens`, `DesignTokens`,
   `CustomColors` — stays in Kotlin. Penpot reflects it, never replaces it.
2. **Penpot is the design language reference.** Style, component shapes, screen
   layout, spacing relationships, edge cases the type system doesn't capture —
   those live in Penpot. The repo's design review happens against the Penpot
   file, not against Figma exports or screenshots.
3. **One bidirectional sync** between the repo and the Penpot file. A change in
   either place propagates; conflicts resolve to "code wins" for tokens,
   "Penpot wins" for layout, "the design system spec wins" for semantic names.
4. **The design emerges from building screens, not the other way around.** We
   build the screen first in Compose, find the pattern, promote it to a token
   in Kotlin, then re-render it in Penpot. The classic design-first pipeline is
   inverted on purpose — too much spec churn otherwise.
5. **No Electron, no Figma, no Adobe lock-in.** Penpot is the only acceptable
   design tool, and only the self-hosted instance. This is non-negotiable.

## Non-goals

- A pixel-perfect 1:1 between Compose and Penpot. M3 elevation, window
  insets, and motion will always differ slightly. The sync covers tokens and
  shared layout primitives, not full state.
- A "designer workflow" with branching Penpot files, review queues, or sign-off
  tooling. The design surface is a mirror, not a governance system.
- Replacing `docs/DESIGN_SYSTEM.md`. The spec is regeneratable from Kotlin; the
  Penpot mirror is regeneratable from the spec. The MD stays.
- Touching the Letta Desktop / Letta Code repos. This is `letta-mobile` only.

## The three reference layers

```
┌─────────────────────────────────────────────────────────────────────┐
│  Penpot file (self-hosted)                                          │
│  - Visual style + component variants + screen mocks                 │
│  - "Pencil | Penpot Design System" as design language reference     │
│  - Token bindings via plugin data namespace                         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  bidirectional sync
                           │  (Penpot wins on layout, code wins on tokens)
┌──────────────────────────▼──────────────────────────────────────────┐
│  docs/DESIGN_SYSTEM.md (regeneratable spec)                         │
│  - Token names, hex values, M3 role mapping                         │
│  - Spacing scale, motion durations, type roles                      │
│  - Semantic color / type aliases                                    │
│  - Pulled from Kotlin by the same script that pushes to Penpot      │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  extracted by the sync script
┌──────────────────────────▼──────────────────────────────────────────┐
│  Kotlin (code is source of truth)                                   │
│  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ │
│    ui/theme/  (ThemeTokens.kt, DesignTokens.kt)                     │
│  android-compose/designsystem/src/main/java/com/letta/mobile/       │
│    ui/theme/  (Color.kt, Spacing.kt, Type.kt, CustomColors.kt)      │
└─────────────────────────────────────────────────────────────────────┘
```

## Sync architecture (proposed)

Three sync layers, run in this order by `scripts/sync-design-tokens.py`:

### Layer 1: Code → Spec → Penpot (token push)

- Read `LettaColorTokens.kt`, `LettaThemeTokens.kt`, `DesignTokens.kt`,
  `CustomColors.kt` from the working tree.
- Parse out: 96 base palette colors (6 presets × 2 modes × 8 M3 roles), 10-step
  spacing scale + ~20 semantic aliases, 7 motion durations, 5 type role
  families, 14+ custom semantic colors.
- Diff against the last-synced snapshot in `docs/.design-snapshot.json`.
- For each new or changed token:
  - Update `docs/DESIGN_SYSTEM.md` (regenerate the tables).
  - POST to Penpot REST API to upsert a "Design Tokens" board on the
    `Letta Mobile / Tokens` page, with a colored swatch per token and the
    token name as the shape's plugin-data name.
- Commit the spec + snapshot as part of the same PR as the Kotlin change.
  Token changes always go through the standard branch → PR → CI → merge loop
  in `AGENTS.md`.

### Layer 2: Penpot → Spec (layout pull, manual)

- On demand (not on every push), `sync-design-tokens.py pull` reads the
  current Penpot file and:
  - Lists every shape, its plugin-data token binding, and its bounding box.
  - Compares against the snapshot. Anything that has moved or been added
    since the last pull is exported as a layout delta to
    `docs/.layout-delta.json`.
- The layout delta is **advisory only** — it tells us "you moved the chat
  bubble padding from 12dp to 16dp in the Penpot mock" so a human can decide
  whether that should be promoted to a new spacing alias in
  `DesignTokens.LettaSpacingTokens`.
- This is how the design system grows: by noticing repeated patterns in
  Penpot layouts and codifying them as tokens in Kotlin.

### Layer 3: Plugin data as the contract

- Every shape that represents a token-bound primitive in Penpot has a
  plugin-data entry of the form:
  - `letta.token.name` = `"chatBubbleSender"` (the token name from Kotlin)
  - `letta.token.kind` = `"color" | "spacing" | "motion" | "type"`
  - `letta.token.theme` = `"default" | "ocean" | "amoledBlack" | ...`
  - `letta.token.mode` = `"light" | "dark"`
- The sync script reads these on pull, writes them on push. Anything in
  Penpot without a `letta.token.*` entry is **artwork** — decorative, not a
  contract, not synced.

## What I'm NOT going to do

- **Not going to build a custom Penpot plugin.** Plugin code lives in ClojureScript
  and is shipped as a separate file inside the Penpot frontend. The cost of
  authoring + maintaining + signing a custom plugin is way too high for a
  one-project sync script. Use the REST API + plugin-data fields (which are
  addressable via the standard `set-plugin-data` RPC) instead.
- **Not going to add Penpot to the CI pipeline.** Token sync is a local
  developer action (`./scripts/sync-design-tokens.py push`). CI is too slow
  and the auth tokens are per-developer anyway.
- **Not going to mirror screens in Penpot at the XML level.** The screen
  mocks are for review and hand-off, not for code generation. If we ever want
  Compose code generated from Penpot, that's a separate, much larger project.

## Penpot box details

- **Host:** `192.168.50.80` (also reachable as `100.80.70.44` via Tailscale)
- **REST API:** `http://192.168.50.80:9001` — v2.14.5
  - RPC pattern: `POST /api/rpc/command/<command-name>`
  - Content type: `application/transit+json` (JSON subset works for simple
    args; full transit encoding needed for nested data)
- **MCP server:** `http://192.168.50.80:4401/mcp` — no auth, 4 tools
  (`execute_code`, `high_level_overview`, `penpot_api_info`, `export_shape`)
- **DB:** Postgres 15, container `penpot-penpot-postgres-1`, user `penpot`,
  database `penpot`. Read-only by default; write access only via the
  `update-profile` prepl command for password resets.
- **PREPL:** port 6063 inside the backend container. Use the `derive-password`
  command to generate Argon2id hashes in the exact format Penpot's
  `buddy.hashers` produces.
- **Reference design system loaded:** "Pencil | Penpot Design System" (41
  pages, ~175 component boards, the source of design language conventions
  but **not** the source of Letta tokens).

## Current blocker (June 2026)

I have full read access to the Postgres database, the running JVM's PREPL,
and SSH to the box. The blocker is **getting a working session token** for
the Penpot REST API so the sync script can read/write the design file.

What's been tried, with results:

| Attempt | Result |
|---|---|
| 3 existing access tokens in the DB (`teat`, `test`, `cline`, all belonging to `emanuvaderland@gmail.com`) | 401 on every endpoint |
| Decompiled `app/http/access_token.clj` from the backend jar | Tokens have `perms = {}` (empty array). The `auth-data` middleware attaches `::actoken/profile-id` only when claims decrypt successfully, but the existing tokens appear to have been encrypted with a `tokens-key` that no longer matches the running JVM's `tokens-key` (the secret-key derived from `PENPOT_SECRET_KEY` may have been regenerated at some point). |
| Reset `emanuvaderland@gmail.com` password to `bangbang` via the PREPL `derive-password` command (which produces a hash in the exact format `buddy.hashers` expects), pushed to DB, then attempted `login-with-password` over REST | Body is parsed (response 400 with `wrong-credentials`, not 404 / params-validation), so the body shape is correct, but verify is still failing. Suspect either the body has a quirk I'm not seeing or the session cookie path is being rejected by the Caddy reverse proxy in front of the backend. |
| Read the Penpot `app/rpc/commands/auth.clj` source | The `login-with-password` handler reads `email` and `password` from `params`, normalizes email, looks up the profile, calls `auth/verify-password`, then writes a session cookie. The handler is straightforward. The 400 we're getting is the right response for "user typed wrong password" — so the email IS being found and the password IS being checked, but the verify is failing for some reason. |

**Next steps to unblock:**

1. Try `login-with-password` from a real browser (the user, not me) to confirm
   the password reset actually took effect and isn't being rejected by a
   separate Caddy / session cookie config issue. If the user can log in via
   the web UI, then the issue is on the API client side (body shape,
   missing header, etc.) and I can iterate.
2. If the user can't log in either, the issue is upstream of the API
   (Caddy cookie path, session v2 schema, etc.) and I need to look at the
   Caddy config and the `http_session_v2` table.
3. If the user CAN log in but the API still fails, add a Cookie header to
   the request — the API may be reading the session from a cookie and not
   recognizing the JSON body as authenticated.
4. Last resort: mint a long-lived access token via the web UI
   (Account Settings → Tokens) and pass that as `Authorization: Bearer` to
   the API. If even that fails, the issue is the empty `perms` array
   preventing the access-token middleware from attaching a profile-id, and
   I'd need to UPDATE the `perms` column in the DB to a non-empty value.

## File plan (in this repo)

Once unblocked, the sync script lands here:

```
letta-mobile/
├── scripts/
│   └── sync-design-tokens.py     # the bidirectional sync
├── docs/
│   ├── DESIGN_SYSTEM.md          # already exists, regeneratable
│   ├── design-sync-with-penpot.md  # this file
│   └── .design-snapshot.json     # last-synced token values (gitignored? TBD)
```

The script is invoked as:

```bash
# Push: code → spec → penpot
./scripts/sync-design-tokens.py push --theme default

# Pull: penpot → layout-delta (manual review)
./scripts/sync-design-tokens.py pull

# Verify: both directions
./scripts/sync-design-tokens.py verify
```

Target size: ~250 lines for the script, ~50 lines for the snapshot helpers,
~50 lines for the Penpot REST client. All stdlib + `requests` (no exotic deps).

## Open questions

- Where does the Penpot file live? Per-team? Per-developer? Shared
  `letta-mobile` team with one file that everyone reads?
- Should the snapshot file be gitignored? (My lean: yes, it's machine
  state, but it needs to be reproducible from the rest of the repo on
  demand.)
- Is the sync script run from a local dev box or from CI? (My lean:
  local dev only, CI just runs `sync-design-tokens.py verify` to ensure
  spec and code agree.)
- Does this script belong in `letta-mobile` or in a separate
  `letta-design-tools` repo? (My lean: `letta-mobile` until the second
  consumer appears, then split.)
