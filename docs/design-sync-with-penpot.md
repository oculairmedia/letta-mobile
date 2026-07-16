# Design sync with Penpot — plan & status

**Status:** validated infrastructure; workflow authority corrected July 2026.
The self-hosted Penpot API, access-token authentication, MCP server, native token
catalog, canonical `letta mobile` file, and Kotlin-to-DTCG export path are all
working. The mature, component-driven **App Mockups v2** page is the visual and
interaction source of truth. Kotlin remains the canonical runtime serialization
of approved tokens; it does not originate the product design. Historical
code-first assumptions below are retained only where they explain the token
transport implementation.

## Why this doc exists

The repo has a token-driven Compose implementation in Kotlin
(`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/`)
and a mature product design in the self-hosted Penpot file **`letta mobile`**.
The canonical design page is **App Mockups v2** (`9ad9fa5c-d4f1-80b2-8008-321c1ee404a4`).
`docs/DESIGN_SYSTEM.md` is the regeneratable code-side token reference; Penpot is
the authority for visual language, component composition, variants, screen
states, responsive layout intent, and linked journeys.

This doc describes how approved Penpot design decisions are serialized into the
Kotlin token implementation and how code/design conformance is checked. API and
authentication are no longer blockers.

## Goals

1. **Penpot is the product-design authority.** Visual language, component
   variants, hierarchy, screen composition, responsive intent, states, and
   journeys are approved in `letta mobile` / App Mockups v2 before production
   implementation.
2. **Kotlin is the runtime token authority.** Approved design tokens are given
   stable semantic names and serialized in `LettaColorTokens`,
   `LettaThemeTokens`, `DesignTokens`, and `CustomColors`. Runtime constraints
   and accessibility implementation remain code responsibilities.
3. **The contract is bidirectional but not symmetric.** Penpot wins product
   design; the shared manifest wins semantic IDs and deterministic fixtures;
   Kotlin wins platform/runtime mechanics. A disagreement requires an explicit
   design revision or an implementation correction, never silent drift.
4. **Design precedes feature decomposition.** A design agent works inside the
   existing component language, presents alternatives, links the journey, and
   produces an approved redline/state contract. The PM then compiles that frozen
   revision into dependency-ordered beads and objective verification gates.
5. **No Figma or Adobe lock-in.** The self-hosted Penpot instance is the
   canonical collaborative design environment.

## Non-goals

- Generating production Compose code directly from arbitrary Penpot shapes.
  Agents implement approved contracts with maintainable shared composables.
- Treating raw pixel equality as the only acceptance signal. Conformance also
  covers structure, semantics, behavior, accessibility, real data, and platform
  constraints.
- Replacing `docs/DESIGN_SYSTEM.md`; it remains the code-side token reference.
- Allowing unreviewed experimental boards to become production requirements.
  Only explicitly approved/frozen design revisions are implementation inputs.

## The three reference layers

```
┌─────────────────────────────────────────────────────────────────────┐
│  Penpot file `letta mobile` / App Mockups v2 (self-hosted)          │
│  - Approved visual language, components, variants, states, journeys │
│  - "Pencil | Penpot Design System" as supporting prior art          │
│  - Native token bindings + stable semantic design metadata          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  approved revision + contract sync
                           │  (Penpot wins product design)
┌──────────────────────────▼──────────────────────────────────────────┐
│  Versioned design contract + docs/DESIGN_SYSTEM.md                  │
│  - Penpot file/page/board IDs and approved revision                 │
│  - Semantic token/component names, states, fixtures, viewports      │
│  - Accessibility and interaction acceptance                         │
│  - Kotlin token serialization exported as reviewable DTCG JSON      │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  extracted by the sync script
┌──────────────────────────▼──────────────────────────────────────────┐
│  Kotlin/Compose production implementation                           │
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

### Layer 1: Approved tokens → Kotlin → DTCG JSON → Penpot native catalog

- After a Penpot design revision approves token changes, update the stable
  semantic Kotlin representation in `LettaColorTokens.kt`, `LettaThemeTokens.kt`,
  `DesignTokens.kt`, and `CustomColors.kt`.
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

### Layer 2: Penpot approved revision → versioned design contract

- A design agent works in the existing App Mockups v2 component language,
  creates alternatives and complete states, links the journey, and tags any
  capability not yet implemented (the avatar brief's `[NEW-*]` convention is
  the current proven example).
- Approval freezes the Penpot file/page/board identifiers and revision in a
  repository-tracked manifest with deterministic fixtures, viewports, states,
  interactions, component keys, and accessibility requirements.
- On-demand export records normalized board/component metadata and reference
  renders. Experimental or deprecated boards are not implementation inputs.
- The PM translates the approved contract into one-bead/one-PR implementation
  slices. Every bead links the exact design revision and relevant states.

### Layer 3: Semantic metadata and conformance

- Native tokens carry stable semantic names. Approved components and boards
  also need stable metadata such as `letta.component`, `letta.variant`,
  `letta.state`, and `letta.status = approved|experimental|deprecated`.
- Production verification uses the same fixture and viewport as the design
  contract. Visual comparison is supplemented by structural, interaction,
  accessibility, scrolling, large-text, and real-data checks.
- Anything without approved status may be valid exploration or artwork, but it
  cannot silently become a production requirement.

## Implementation boundaries

- Prefer the existing MCP and authenticated REST API. Add bridge/plugin
  capability only when the current surface cannot expose stable revisions,
  semantic metadata, interactions, or deterministic exports.
- CI should not mutate Penpot. It may consume committed design manifests and
  reference exports to verify Compose conformance without requiring design
  credentials.
- Do not generate Compose directly from Penpot's raw shape tree. Screens remain
  maintainable production code built from shared components; Penpot supplies
  the approved contract and verification baseline.

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

## Historical blocker (resolved June 2026)

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
