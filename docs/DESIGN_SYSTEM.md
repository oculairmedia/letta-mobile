# Letta Design System Reference (extracted from code)

Source of truth: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/`
and `android-compose/designsystem/src/main/java/com/letta/mobile/ui/theme/`.

This document is the **canonical spec** that drives all design work in this repo. It was
extracted directly from the Compose `object` declarations so any change to those files
invalidates this document and it must be regenerated.

## 1. Color Tokens

### `LettaColorTokens` — base palette
- **Dark surfaces**: surface `#121212`, surfaceVariant `#1E1E1E`, surfaceContainer `#2A2A2A`, background `#0A0A0A`
- **Dark primary**: primary `#00BFA5` (Material A700 teal), primaryVariant `#009688` (teal 500)
- **Dark text**: onSurface `#E0E0E0`, onSurfaceVariant `#BDBDBD`, outline `#424242`
- **Dark error**: error `#CF6679`, onError `#000000`
- **Light surfaces**: surface `#FAFAFA`, surfaceVariant `#EEEEEE`, surfaceContainer `#E0E0E0`, background `#FFFFFF`
- **Light primary**: primary `#00897B` (teal 600), primaryVariant `#00695C` (teal 800)
- **Light text**: onSurface `#1A1A1A`, onSurfaceVariant `#424242`, outline `#BDBDBD`
- **Light error**: error `#B00020`, onError `#FFFFFF`
- **Accents**: tealAccent `#1DE9B6`, cyanAccent `#00E5FF`, amberAccent `#FFD740`

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

### `CustomColors` — semantic color roles
Beyond Material 3, the app defines its own semantic colors:
- **Chat bubbles**: `userBubbleBgColor`, `agentBubbleBgColor`, `reasoningBubbleBgColor`, `toolBubbleBgColor`, `systemMessageColor`, `dateSeparatorColor`
- **Text**: `textPrimary`, `textSecondary`, `textDisabled`, `textLink`, `textOnPrimary`
- **Status**: `errorTextColor`, `warningTextColor`, `successColor` (each with `*ContainerColor` and `harmonized*` variants — the `HctColorHarmonizer` blends status colors with the active theme primary to keep accents coherent)
- **Connection**: `onlineColor`, `offlineColor`, `reconnectingColor`
- **Icons**: `iconPrimary`, `iconSecondary`, `iconAccent`
- **Fresh accent**: `freshAccent`, `onFreshAccent`, `freshAccentContainer`, `onFreshAccentContainer` (for unread/new item states)
- **Selection**: `selectionContainer`, `onSelectionContainer`, `selectionIndicator` (warm↔cool complement of primary)
- **Borders**: `borderDefault`, `borderFocused`, `borderCritical`

## 2. Spacing Scale

`LettaSpacingTokens` — density-independent pixels.

| Token | dp |
|---|---|
| `none` | 0 |
| `xxxs` | 2 |
| `xxs` | 4 |
| `xs` | 6 |
| `sm` | 8 |
| `md` | 12 |
| `lg` | 16 |
| `xl` | 24 |
| `xxl` | 32 |
| `xxxl` | 64 |

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

### Chat-specific tokens
| Token | dp |
|---|---|
| `bubblePaddingHorizontal` | 10 |
| `bubblePaddingVertical` | 7 |
| `messageSpacing` | 2 |
| `bubbleRadius` | 12 |
| `codeBlockRadius` | 8 |
| `composerAttachIconSize` | 18 |
| `composerAttachButtonSize` | 36 |
| `avatarSize` | 24 |
| `iconSizeSmall` | 14 |
| `borderWidthThin` | 1 |
| `chipMinHeight` | 32 |
| `chipPaddingVertical` | 6 |
| `chipPaddingHorizontal` | 12 |
| `chipRingSize` | 20 |
| `chipRingStroke` | 2 |

## 3. Typography

`Typography` — Material 3 scale using **Inter** variable font (and **JetBrains Mono** for code).

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
| bodyLarge | 16 | 24 | Normal | 0.5 |
| bodyMedium | 14 | 20 | Normal | 0.25 |
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

## 5. Elevation

`LettaElevationTokens` (in dp):
- `none` 0, `low` 1
- `actionSheetItemResting` 2, `actionSheetItemPressed` 4
- `floatingBannerTonal` 3, `floatingBannerShadow` 6

## 6. Motion

`LettaMotionTokens` (milliseconds):
- `StreamingSize` 60, `ContentSize` 220
- `Enter` 190, `Exit` 130
- `FastFadeIn` 120, `FastFadeOut` 90
- `Chip` 150

## 7. Sizing

`LettaSizingTokens`:
- `compactWidthBreakpoint` 600dp (window-size-class compact/medium)
- `readableDialogMaxWidth` 1000dp (cap for dialog content)
- `diagramPreviewMinHeight` 120dp

## 8. Chat tokens

`LettaChatTokens`:
- `shapes.bubbleRadiusDp` 12, `shapes.codeBlockRadiusDp` 8
- `dimens.bubbleMaxWidthFraction` 0.88 (88% of parent width)
- `dimens.contentPaddingHorizontalDp` 12
- `dimens.groupedMessageSpacingDp` 2, `ungroupedMessageSpacingDp` 6
- `typography.codeBlockFontSizeSp` 12, `codeBlockLineHeightSp` 16

## Design system rules (from AGENTS.md)

1. **Stable chrome, expressive content** — app bars/drawers are stable structure; rich emphasis and shared transitions belong on content surfaces (cards, rows, detail headers).
2. **Fix geometry before tuning motion** — don't paper over layout bugs with animation tweaks.
3. **Material 3 systematically** — all new UI uses M3 components and tokens; avoid raw colors.
4. **Lucide icon system** — use the centralized `Lucide` icon set; do not inline SVG paths.
5. **HctColorHarmonizer** — status colors (error/warning/success) are automatically harmonized against the active theme primary; never hardcode these — let the harmonizer derive them.
6. **AdaptiveDialog** — dialogs use the shared adaptive dialog component with `readableDialogMaxWidth` cap.

## Module map

- `sharedLogic` (`commonMain`) — token definitions (DPI-agnostic, used by Android + Desktop JVM targets)
- `designsystem` (`main`) — Compose MaterialTheme wiring, custom semantic colors, shape/elevation, `Theme` composable
- `app` — screens, navigation, ViewModels; consumes the themes
- `chat` — chat-specific dimensions and bubble rendering

## What "regimented design" should add (gaps)

- **Figma/Penpot library** mirroring the six theme presets (none exists yet)
- **A11y color contract** — every `LettaThemeTokens` preset should specify AA-contrast pairings explicitly. Currently implicit via Material 3 defaults.
- **Motion spec sheet** — `LettaMotionTokens` are in code; no design surface documents the curves/easings (we use `Motion.kt` for that, but it's not human-readable).
- **Icon naming spec** — Lucide icons are referenced by string; no design-side catalog of which icon is used where.
- **Density variants** — current scale targets phones. Desktop/Android-tablet density variants (`compactWidthBreakpoint = 600` is the only breakpoint) need more thought.
