# Design sync with Penpot — plan & status

**Status:** exploratory, but the core unknowns are now resolved (June 2026).
The Penpot API + auth work (access token in vault), Penpot 2.14.5 has a native
token catalog, and our `letta mobile` file has `design-tokens/v1` enabled but
**empty** — so the immediate work is a one-way **code → Penpot push** via a
DTCG-JSON exporter. See "Current status" for the verified ground truth; the
older "shapes + plugin-data" architecture is struck through where superseded.

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

> **CORRECTION (June 2026):** the original Layer 1 below proposed encoding tokens
> as **decorative shapes with plugin-data** because we assumed Penpot had no
> native token support. That assumption was wrong — Penpot 2.14.5 has a
> **first-class native token catalog** (`design-tokens/v1`, enabled on our file).
> Target the native catalog, **not** shapes-with-plugin-data. The corrected
> approach is below; the original text is struck through for history.

### Layer 1 (CORRECTED): Code → DTCG JSON → Penpot native token catalog

- Read `LettaColorTokens.kt`, `LettaThemeTokens.kt`, `DesignTokens.kt`,
  `CustomColors.kt`, `Type.kt` from the working tree.
- Parse out: base palette colors (6 presets × 2 modes × 8 M3 roles), 10-step
  spacing scale + semantic aliases, motion durations, type role families,
  custom semantic colors.
- Emit a **DTCG-style token JSON** (`docs/design/penpot-tokens.json`) — the
  W3C/Design-Tokens-Community-Group shape that Penpot's own importer round-trips.
  Token sets group via `/`; theme = a token set selection (light/dark/preset);
  aliases use `{token.name}`. This file is the sync contract.
- **Push into the `letta mobile` file's native token catalog** via the
  authed REST `update-file` change-ops (`:set-token-set`, `:set-token`,
  `:set-token-theme`) — see the "How writes work" section under Current status.
  NOT shape-upserts.
- Regenerate `docs/DESIGN_SYSTEM.md` and commit spec + JSON in the same PR as
  the Kotlin change (standard branch → PR → CI → merge loop per `AGENTS.md`).

#### ~~Layer 1 (ORIGINAL, superseded — shapes + plugin-data)~~

- ~~Diff against the last-synced snapshot in `docs/.design-snapshot.json`.~~
- ~~POST to Penpot REST API to upsert a "Design Tokens" board with a colored
  swatch per token and the token name as the shape's plugin-data name.~~
  *(Superseded: use the native token catalog, not swatch shapes.)*

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

~~The blocker is getting a working session token.~~ **RESOLVED (June 2026).**
This whole section is kept for history but is no longer accurate.

### Ground truth (verified against the live box, June 2026)

- **Penpot version: 2.14.5** (containers `penpot-penpot-backend-1` etc. on
  `192.168.50.80`).
- **The API works and auth is solved.** A long-lived **access token** exists in
  Vaultwarden — item **"Penpot API — prototype.oculair.ca (access token)"**,
  custom field `access_token` (+ `team_id`, `default_project_id`). Use it as
  `Authorization: Token <token>` (Penpot's scheme is `Token`, **not** `Bearer`).
  Verified: `get-profile` returns Emmanuel's real profile, and `get-file` on the
  `letta mobile` file returns HTTP 200 with the full decoded file (312 KB).
- **The earlier "empty `perms`" / `login-with-password` rabbit hole was
  unnecessary** — a UI-minted access token sidesteps all of it. Don't reset
  passwords or poke the PREPL; just read the token from the vault.
- **API doc:** `https://prototype.oculair.ca/api/main/doc` (the RPC command
  surface). RPC base from the box: `POST http://localhost:9001/api/rpc/command/<cmd>`.

### Where tokens actually live (verified)

- **No dedicated token DB tables.** The only `%token%` table is `access_token`
  (API auth). Design tokens live **inside the file `data` blob** (zstd-compressed
  Fressian, ~31 KB inline in Postgres for `letta mobile`).
- **`design-tokens/v1` feature is ENABLED** on both relevant files:
  `letta mobile` (`46e68d89-bd4b-8008-8007-fa0da54a88af`) and
  `Pencil` (`11a02f61-0341-81f7-8008-115286a21c43`).
- **…but both token catalogs are currently EMPTY.** A `get-file` scan found zero
  `token-set` / `token-theme` / token entries in either file (the only
  `tokens-lib` hits are migration names like `0014-fix-tokens-lib-duplicate-ids`).
  The feature is switched on; nobody has populated tokens yet.

**Consequence:** the first job is a **one-way PUSH** (code → Penpot), not a
bidirectional sync. There is nothing to pull until designers start editing
tokens in Penpot. The pull/merge machinery in this doc is deferred.

### How writes work (verified vocabulary)

- There is **no dedicated `import-tokens-lib` RPC** (404). Tokens are written via
  **`update-file`** change-ops — the same path the plugin uses.
- Token change-op vocabulary (from Penpot `common/files/changes.cljc`, 2.x):
  `:set-token-set` `{:id, :attrs {:name, :description}}`,
  `:set-token` `{:set-id, :token-id, :attrs {:name, :type, :value, :description}}`,
  `:set-token-theme` `{:id, :attrs}`,
  `:set-active-token-themes` `{:theme-paths #{...}}`.
- Token `:type` values are DTCG-style: `color`, `dimension`, `spacing`,
  `sizing`, `border-radius`, `font-size`, `font-weight`, `font-family`,
  `letter-spacing`, `line-height`, `opacity`, `number`, `typography` (composite),
  etc. Aliases use `{token.name}` reference syntax.
- `update-file` requires `{:id, :session-id, :revn, :changes [...]}` and the
  payload is **transit+json** for nested values — see `transit.ts` (adaptable
  from the MIT `design-token-manager` repo) for decode, and the inverse for
  encode.

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
