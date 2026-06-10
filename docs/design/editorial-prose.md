# Design decision: editorial prose for chat

**Status:** proposed (token-level changes ready; serif body deferred for review)
**Date:** June 2026
**Scope:** `letta-mobile` chat reading surface only — the assistant/user prose
column, Markdown rendering, and inter-message rhythm. Does **not** touch tool
cards, A2UI surfaces, app chrome, or the desktop target.

## Why this doc exists

`docs/DESIGN_SYSTEM.md` is the regeneratable spec — it captures *what* the
tokens are. It does not capture *why*. This doc holds the intent behind a set
of chat-typography token changes so the next person (or the Penpot review)
understands the direction, not just the values.

This is the disciplined entry point required by `docs/design-sync-with-penpot.md`:
the design emerges from building the screen, the pattern gets promoted to a
**named token in Kotlin**, the spec is regenerated, and only then does Penpot
mirror it. Per `AGENTS.md §7`, editorial typography lands as semantic tokens,
never as inline `fontWeight`/`lineHeight` overrides in feature UI.

## The problem

Tested on a black-and-white (high-contrast) read of the chat surface, the prose
column felt cramped and mechanical:

- **List items had no air.** Bullets and numbered items butted directly against
  each other (mikepenz default `listItemBottom` ≈ 4dp), so a multi-point answer
  read as a dense wall instead of a scannable list. The Vercel/AI-SDK docs were
  the reference for the *right* rhythm — visible gutter between every item.
- **Line height was tight.** `bodyMedium` at 14/20 (1.43 ratio) is fine for UI
  labels but reads dense for multi-paragraph prose.
- **Inter-message spacing was undifferentiated.** Grouped (`messageSpacing` 2dp)
  vs ungrouped (`6dp`) were too close to read as a hierarchy, so the boundary
  between an individual response and a run/tool collection felt jarring and
  uneven.

## The direction

**Loose and editorial.** The chat prose column should read like a well-set
article: generous vertical rhythm, scannable lists, a clear beat between
sections — while staying dense enough that the app never feels empty.

This is *evolution, not redesign* (per the established direction): the layout,
flat-prose structure, and timeline rail are kept; only the typographic rhythm
and list spacing are tuned, expressed as tokens.

## Decisions

### 1. Editorial body prose (ACCEPT)

Open up the body text used for chat prose:

| Property | Was | Now | Rationale |
|---|---|---|---|
| `bodyMedium` line height | 20sp (1.43) | 24sp (1.71) | Magazine vertical rhythm for multi-paragraph prose |
| `bodyMedium` letter spacing | 0.25sp | 0.30sp | Slightly opened tracking for air, not so wide it reads spaced-out |
| `bodyLarge` line height | 24sp (1.5) | 26sp (1.63) | Same intent at the larger size |
| OpenType features | none | `liga, calt` | Ligatures + contextual alternates for smoother letterforms |
| Hyphenation | none | `Hyphens.Auto` | Cleaner ragged edge, fewer awkward long-word overflows |
| Line break | `Simple` | `Paragraph` | Balanced line wrapping (the Knuth-Plass-style paragraph breaker) |

These are conservative, reversible, and font-agnostic — they improve Inter today
and would improve any future body face.

### 2. List-item breathing room (ACCEPT)

Configure the Markdown renderer (`mikepenz/multiplatform-markdown-renderer`)
with explicit editorial padding instead of the cramped defaults:

| `markdownPadding` field | Value | Effect |
|---|---|---|
| `block` | 8dp | Air between paragraphs/blocks |
| `list` | 8dp | Air around the whole list |
| `listItemBottom` | 8dp | **The key fix** — visible gutter between every bullet/number |
| `listItemTop` | 0dp | No double-gap at the top of a list |
| `indentList` | 12dp | Comfortable, not exaggerated, nesting indent |

### 3. Inter-message rhythm (ACCEPT)

Differentiate grouped vs ungrouped message spacing so the hierarchy is legible:

| Token | Was | Now | Meaning |
|---|---|---|---|
| `messageSpacing` (grouped) | 2dp (`xxxs`) | 8dp (`sm`) | Comfortable beat within the same speaker's turn |
| `ungroupedMessageSpacing` | 6dp (`xs`) | 24dp (`xl`) | Clear editorial section break between speakers / run boundaries |

The grouped value goes *up* (2→8) so consecutive bubbles aren't crammed; the
ungrouped value goes up more (6→24) so the boundary between an answer and a
run/tool collection is unmistakable. Net feel: tighter *within* a turn, looser
*between* turns.

### 4. Serif body face (DEFER — propose, do not ship yet)

A serif body face (e.g. system `FontFamily.Serif` / Noto Serif, or a bundled
Lora / Source Serif) would push the editorial feel furthest and is the single
biggest lever on "reads like an article." **But it is a large, opinionated swing**
that:

- changes the entire reading character of the app, not just spacing;
- interacts with the theme system and the companion-vs-tool brand balance;
- deserves a side-by-side Penpot review against the sans baseline before it
  becomes the canonical body face.

So serif is **proposed here, not bundled into the token PR.** If accepted after
review, it lands as a `LettaSerifFont` family in `Type.kt` applied to the chat
prose body roles only (never code, chrome, or labels), with its own letter-
spacing tuning (serif needs *less* tracking than sans).

## Non-goals

- No change to code blocks (JetBrains Mono, `liga`/`calt` **off** for ASCII-art
  alignment — that contract is unchanged and must stay).
- No change to tool cards, A2UI surfaces, app bars, or the composer.
- No new fonts in this PR (serif is deferred per §4).
- No desktop-specific tuning yet; tokens are shared (`sharedLogic`) so desktop
  inherits the rhythm, but desktop density review is a separate pass.

## Token surface touched

- `android-compose/designsystem/.../ui/theme/Type.kt` — `bodyMedium`,
  `bodyLarge` (line height, tracking, OpenType features, hyphens, line break).
- `android-compose/sharedLogic/.../ui/theme/DesignTokens.kt` —
  `messageSpacing`, new `ungroupedMessageSpacing` semantic token, wired into
  `LettaChatTokens.dimens`.
- `android-compose/designsystem/.../ui/components/MarkdownText.kt` — add the
  `markdownPadding(...)` editorial config to the `CoreMarkdown` call.

## Verification

Per the regression-test-per-feature policy:

- Unit assertions in `DesignTokensCommonTest` for the new spacing values.
- A typography assertion that `bodyMedium` carries the editorial line height +
  OpenType features.
- On-device read on the Pixel 9 Pro against a multi-bullet / multi-paragraph
  answer, confirming the list gutter and inter-message rhythm match the
  Vercel-docs reference.

After merge, regenerate `docs/DESIGN_SYSTEM.md` (§3 Typography, §8 Chat tokens)
so the spec reflects the new values, and queue the Penpot mirror update.
