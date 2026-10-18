# Letta Design System Reference (extracted from code)

**Status:** Design usage reference with retained historical value snapshots. Public declaration inventories link to generated API; presets, Android typography and resolved aliases below are not fully represented by Dokka.

Design direction and policy: [DESIGN.md](../DESIGN.md) and [AGENTS.md](../AGENTS.md).
Current implementation: [shared UI theme](../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/theme/)
and [Android design system theme](../android-compose/designsystem/src/main/java/com/letta/mobile/ui/theme/).
When this snapshot disagrees with code, code determines implemented token values;
use the design policy to decide intended behavior.

## 1. Color Tokens

### Base palette

Current palette constants and their values are in the
[generated `LettaColorTokens` API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-color-tokens/index.html).
The [source](../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/ThemeTokens.kt)
uses hexadecimal ARGB; Dokka emits numeric constant values. This replaces the
older grey-palette copy, which had drifted from the implemented cool-slate
palette. Use semantic roles for product UI rather than copying raw values.

### `LettaThemeTokens` — 6 theme presets
Each preset has **light + dark** variants. Eight color roles per mode: `primary`, `primaryContainer`, `secondary`, `tertiary`, `background`, `surface`, `surfaceVariant`, `outline`.

| Preset | Light primary | Dark primary | Mood |
|---|---|---|---|
| `default` (Material teal) | `#00897B` | `#00BFA5` | Default brand |
| `ocean` | `#006D8F` | `#6FD8F6` | Cool blue |
| `amoledBlack` | `#2E2E2E` | `#E6E6E6` | Pure monochrome |
| `sakura` | `#B45C7B` | `#F0A8C1` | Soft pink |
| `autumn` | `#9A4F1A` | `#FFB689` | Warm orange |
| `spring` | `#2D7D46` | `#8FDC9B` | Fresh green |

User can swap themes via the `ThemePreset` enum at runtime (theme picker UI exists).

### Semantic color roles

The [generated `CustomColors` API](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.theme/-custom-colors/index.html) owns the
property inventory. Read [the implementation](../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/theme/CustomColors.kt)
for derived values. Keep status colors harmonized with the active theme rather
than hardcoding replacements; fresh/unread accents and selection colors express
separate states from errors, warnings and connectivity.

## 2. Spacing Scale

The [generated `LettaSpacingTokens` API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-spacing-tokens/index.html) lists
scale members and literal values in density-independent pixels. Its
[source](../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/DesignTokens.kt)
also shows aliases; Dokka omits initializer values for aliases. The tables below
retain the semantic mapping and resolved aliases rather than duplicating the
literal scale inventory.

### Semantic spacing tokens
| Token | Value | Use |
|---|---|---|
| `screenHorizontal` | 12dp | Edge inset for screen content |
| `cardGap` | 8dp | Gap between cards in a list |
| `sectionGap` | 16dp | Gap between sections |
| `cardGroupItemGap` | 2dp | Gap between rows in a `CardGroup` |
| `innerPadding` | 16dp | Standard inner padding |
| `innerPaddingSmall` | 12dp | Compact inner padding |
| `iconGap` | 12dp | Gap between icon and adjacent text |
| `chipGap` | 8dp | Gap between chips |

### Chat-specific aliases

Literal chat dimensions are in the generated spacing API above. The aliases that
Dokka does not resolve to numbers are:

| Alias | Resolved value | Purpose |
|---|---|---|
| `messageSpacing` | 8dp | Beat within the same speaker's turn |
| `ungroupedMessageSpacing` | 24dp | Section break between speakers or runs |
| `bubbleRadius` / `codeBlockRadius` | 12dp / 8dp | Message and code surfaces |
| `avatarSize` | 24dp | Avatar slot |
| `chipPaddingVertical` / `chipPaddingHorizontal` | 6dp / 12dp | Chip content insets |

## 3. Typography

`Typography` — Material 3 scale using **Inter** variable font (and **JetBrains Mono** for code).

> **Editorial body prose** (`bodyMedium`, `bodyLarge`) — see `docs/design/editorial-prose.md`.
> Both carry `fontFeatureSettings = "liga, calt"`, `Hyphens.Auto`, and
> `LineBreak.Paragraph` for magazine-quality chat prose. Line height is opened to
> 1.71 (`bodyMedium` 14/24) and 1.63 (`bodyLarge` 16/26). Code styles keep
> ligatures **off** for ASCII-art alignment (unchanged).

| Role | Size | Line | Weight | Letter |
|---|---|---|---|---|
| displayLarge | 57 | 64 | Bold | -0.25 |
| displayMedium | 45 | 52 | Bold | 0 |
| displaySmall | 36 | 44 | Bold | 0 |
| headlineLarge | 32 | 40 | SemiBold | 0 |
| headlineMedium | 28 | 36 | SemiBold | 0 |
| headlineSmall | 24 | 32 | SemiBold | 0 |
| titleLarge | 22 | 28 | SemiBold | 0 |
| titleMedium | 16 | 24 | Medium | 0.15 |
| titleSmall | 14 | 20 | Medium | 0.1 |
| bodyLarge | 16 | 26 | Normal | 0.3 |
| bodyMedium | 14 | 24 | Normal | 0.3 |
| bodySmall | 12 | 16 | Normal | 0.4 |
| labelLarge | 14 | 20 | Medium | 0.1 |
| labelMedium | 12 | 16 | Medium | 0.5 |
| labelSmall | 11 | 16 | Medium | 0.5 |

### `TypeHierarchy` — app-specific extensions
- `listItemHeadline` → `titleMedium` (16/24 Medium)
- `listItemSupporting` → `bodySmall` Medium
- `listItemMetadata` → `labelMedium` SemiBold
- `listItemMetadataMonospace` → `labelMedium` SemiBold in **JetBrains Mono**
- `dialogSectionHeading` → `labelLarge` SemiBold
- `sectionTitle` → `titleSmallEmphasized`
- `chatBubbleSender` → `labelMediumEmphasized`
- `statValue` → `headlineMedium`

## 4. Shape

`LettaShapeTokens`:
- `listRadius` = 12dp (md)
- `prominentListRadius` = 16dp (lg)
- `actionRadius` = 8dp (sm)

## 5. Elevation, motion and sizing APIs

The generated references own these constant inventories and literal values:

- [Elevation](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-elevation-tokens/index.html).
- [Motion durations](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-motion-tokens/index.html).
- [Sizing and breakpoints](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-sizing-tokens/index.html).

All three come from [DesignTokens.kt](../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/DesignTokens.kt).
Motion intent and easing choices remain design decisions; a duration declaration
does not replace the geometry and navigation rules in [DESIGN.md](../DESIGN.md).

## 6. Chat tokens

[Generated `LettaChatTokens` API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-chat-tokens/index.html) lists the holders;
the resolved values below are retained because Dokka does not expand their
object initializers.

- `shapes.bubbleRadiusDp` 12, `shapes.codeBlockRadiusDp` 8
- `dimens.bubbleMaxWidthFraction` 0.88 (88% of parent width)
- `dimens.contentPaddingHorizontalDp` 12
- `dimens.groupedMessageSpacingDp` 8, `ungroupedMessageSpacingDp` 24 (editorial rhythm — see `docs/design/editorial-prose.md`)
- **Markdown list spacing** (`MarkdownText.kt` → `markdownPadding`): `block` 8dp, `list` 8dp, `listItemBottom` 8dp, `listItemTop` 0dp, `indentList` 12dp
- `typography.codeBlockFontSizeSp` 12, `codeBlockLineHeightSp` 16

## Design system rules (from AGENTS.md)

1. **Stable chrome, expressive content** — app bars/drawers are stable structure; rich emphasis and shared transitions belong on content surfaces (cards, rows, detail headers).
2. **Fix geometry before tuning motion** — don't paper over layout bugs with animation tweaks.
3. **Material 3 systematically** — all new UI uses M3 components and tokens; avoid raw colors.
4. **Lucide icon system** — use the centralized `Lucide` icon set; do not inline SVG paths.
5. **HctColorHarmonizer** — status colors (error/warning/success) are automatically harmonized against the active theme primary; never hardcode these — let the harmonizer derive them.
6. **AdaptiveDialog** — dialogs use the shared adaptive dialog component with `readableDialogMaxWidth` cap.

## Module map

- `sharedLogic/commonMain` — portable scalar token definitions.
- `sharedUI/commonMain` — semantic colors, shared theme and product chat rendering.
- `designsystem/main` — Android MaterialTheme/font and rich-renderer bindings.
- `app` / `feature-chat` — Android host, platform services and ViewModel bindings.

## What "regimented design" should add (gaps)

- **Native Penpot token coverage** for all six theme presets. The canonical `letta mobile` / App Mockups v2 component library exists; remaining work is complete native-token binding and automated conformance.
- **A11y color contract** — every `LettaThemeTokens` preset should specify AA-contrast pairings explicitly. Currently implicit via Material 3 defaults.
- **Motion spec sheet** — `LettaMotionTokens` are in code; no design surface documents the curves/easings (we use `Motion.kt` for that, but it's not human-readable).
- **Icon naming spec** — Lucide icons are referenced by string; no design-side catalog of which icon is used where.
- **Density variants** — current scale targets phones. Desktop/Android-tablet density variants (`compactWidthBreakpoint = 600` is the only breakpoint) need more thought.
