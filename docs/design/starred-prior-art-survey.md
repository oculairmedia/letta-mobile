# Prior-art survey: design-system & Penpot-sync references

**Date:** June 2026
**Scope:** Mined from the `oculairmedia` GitHub stars for two active threads —
(1) the Penpot ↔ Kotlin token sync (`docs/design-sync-with-penpot.md`), and
(2) the desktop/KMP design-system unification. This is research notes, not a
commitment to adopt any of these.

## Headline finding — revise the Penpot-sync plan

`docs/design-sync-with-penpot.md` was written assuming **no native Penpot token
support**, so it proposed encoding tokens as generic *shapes with plugin-data*
(`letta.token.*` namespace) and explicitly said *"not going to build a custom
Penpot plugin."* That assumption is **out of date.**

**`elhombretecla/design-token-manager`** (starred, MIT, TS/Vite) is a working
Penpot **design-token plugin** that proves Penpot now has a **first-class native
token catalog**:

- Tokens live at **`penpot.library.local.tokens`** (a `TokenCatalog`), not as
  decorative shapes. There are real entities: **token sets** (groups via `/`),
  **tokens** (typed), and **themes** (light/dark mode switching).
- Token **types** are the W3C-ish set we already use: Color, Border Radius,
  Dimension, Font Family, Font Size, Font Weight, Letter Spacing, Number,
  Opacity, Shadow, Sizing, Spacing, Stroke Width, Text Case, Text Decoration,
  and composite **Typography**.
- **Aliases** via `{token.name}` references — maps cleanly onto our semantic
  aliases (`screenHorizontal = md`, `messageSpacing = sm`, etc.).
- **Import/Export as JSON** — a token set round-trips as JSON, which is the sync
  contract we want (no bespoke REST shape-pushing).

### What this changes in our plan

1. **Drop the "shapes + plugin-data" encoding.** Target the native token catalog
   instead. Our sync becomes: Kotlin tokens → **W3C/DTCG-style JSON** →
   import into Penpot's token catalog (and back).
2. **The plugin may sidestep the auth blocker.** The whole "Current blocker
   (June 2026)" section of the sync doc is about getting a REST session token.
   A plugin runs *inside* an authenticated Penpot session (`penpot.library.local`),
   so token read/write doesn't need a separately-minted API token at all. The
   sync can be plugin-mediated (export JSON from the repo, import via the plugin
   UI) rather than REST-mediated.
3. **Two reusable utilities to steal (MIT, so attribution is enough):**
   - **`src/utils/transit.ts`** — `transitToPlain()` decodes Penpot's
     ClojureScript Transit-JSON wire format (`{$meta$, $cnt$, $arr$:[…]}`) into
     plain JS. The sync doc flagged transit+json as "the hard part of the REST
     API" — this is the decoder, already written and battle-tested against real
     Penpot output.
   - **`src/utils/typographyNormalize.ts`** — normalizes composite typography
     token values across Penpot's *four* serialization formats (Transit map,
     API JSON, EDN JSON, alias strings) into a stable
     `{fontFamily, fontSize, fontWeight, lineHeight, letterSpacing, textCase,
     textDecoration}` shape, and sanitizes back to the API's
     `TokenTypographyValueString` schema (`fontFamilies`, `fontSizes`, …).
     This is *exactly* the shape our editorial `bodyMedium`/`bodyLarge` work
     would push.

### Decision (proposed, not yet executed)

Rewrite `docs/design-sync-with-penpot.md`'s sync architecture around the native
token catalog + JSON round-trip, and keep the plugin-mediated path as the
primary (REST as a fallback only if we want headless CI sync later). Our
`docs/DESIGN_SYSTEM.md` already encodes the right token *names*; the missing
glue is the Kotlin → DTCG-JSON exporter, which is far smaller than the original
~250-line REST client plan.

## Component-library / design-system references (desktop-KMP thread)

For when desktop unification needs a shared component layer (peer Android +
Desktop, per the multiplatform roadmap):

| Repo | ★ | License | Why it's relevant |
|---|---|---|---|
| `composablehorizons/compose-unstyled` | 1.2k | (no LICENSE file — **check before use**) | "Headless" CMP design-system layer: behavior/a11y/state primitives (Button, Dialog, Slider, TabGroup, BottomSheet, TextField, Scrollbars, FocusRing…) with **zero visual opinion**, multiplatform (android/desktop/web/ios). The right shape for "one behavior layer, our skin on top." Each primitive is its own Gradle module → cherry-pickable. Has a `composeunstyled-theming` + `platformtheme` module worth reading for our token-binding approach. |
| `nomanr/lumo-ui` | 587 | (no LICENSE file — check) | Gradle **plugin** that scaffolds your *own* Compose component library (generates components into your source, you own them — not a runtime dep). Interesting model for generating our `designsystem` module components from a spec. |
| `gabrieldrn/carbon-compose` | 327 | check | IBM Carbon design system in CMP (android/desktop/wasm/native). Reference for a *complete, rigorous* token→component system in Compose Multiplatform — good study for how a mature DS maps tokens to components across targets. |
| `kirill-grouchnikov/aurora` | — | check | Desktop-Compose design system (Radiance lineage). Desktop-specific polish reference. |
| `composablehorizons/compose-unstyled` `AGENTS.md` + `.agents/` | — | — | They ship agent guidance in-repo; worth reading how they structure it. |

**Caveat:** several of these have **no LICENSE file** despite being public.
"No license" = all-rights-reserved by default. We can *read and learn* from them,
but **must not vendor/copy code** until a license is confirmed. `design-token-manager`
is explicitly **MIT** (LICENSE present), so its utils are safe to adapt with
attribution.

## Other clusters in the stars (noted, not surveyed here)

- **Text/layout in Compose:** `chenglou/pretext` (text measurement/layout),
  `ArjunJadeja/texty`, `oleksandrbalan/textflow`, `Calvin-LL/AutoLinkText`,
  **`GIGAMOLE/ComposeFadingEdges`** (a published lib for the exact fading-edge
  effect we hand-rolled in `ChatFadingEdges.kt` — candidate to adopt + delete
  bespoke code), `skydoves/compose-performance-skills` (frame-budget discipline
  as agent skills — aligns with our render-hot-path rule).
- **Local on-device agent runtime (`lcp-59c`):** `RunanywhereAI/runanywhere-sdks`,
  `vNeeL-code/GHOST` (Gemma on Android), `sepivip/SeekerClaw`, `phodal/auto-dev`
  (KMP multi-agent SDLC), `openclaw-termux`.
- **Companion/agent product (north star):** `NousResearch/hermes-agent` +
  `hermes-android` ("the agent that grows with you"), `Rakile/NeuralCompanion`,
  Tamagotchi clones (gamification direction), `openchamber`, `can1357/oh-my-pi`,
  `letta-ai/letta-code-action`.

These are tracked for their respective threads; survey them when that thread is
active.

## Next actions (if we pull this thread)

1. Rewrite the sync-architecture section of `docs/design-sync-with-penpot.md`
   around the native token catalog + DTCG-JSON round-trip; demote the REST/auth
   blocker to a fallback concern.
2. Prototype a Kotlin → DTCG-JSON token exporter (the spec already lists every
   token name/value; this is a mechanical extraction, ~100 lines).
3. Adapt `transit.ts` + `typographyNormalize.ts` (MIT) as the Penpot-side
   decode/normalize layer, with attribution.
4. Separately: evaluate `GIGAMOLE/ComposeFadingEdges` against our
   `ChatFadingEdges.kt` to see if we can adopt-and-delete.
