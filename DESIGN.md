# Letta Mobile Design System

This is the design direction and policy reference, together with the UI rules in
[AGENTS.md](AGENTS.md). [The token reference](docs/DESIGN_SYSTEM.md) records an older
implementation snapshot; current theme code defines actual token values.

## 1. Atmosphere & Identity

Letta Mobile is a calm operational workspace for managing agents and following their work. Its signature is stable, information-dense chrome around readable content surfaces: connection state, agent identity, and the active conversation remain clear without decorative distraction.

## 2. Color

All UI colors come from `MaterialTheme.colorScheme` and `MaterialTheme.customColors`; theme presets provide the concrete light and dark values.

| Role | Compose token | Usage |
|---|---|---|
| Main surface | `background`, `surface` | Page and content backgrounds |
| Layered surface | `surfaceContainer*` | Rails, sidebars, cards, composer |
| Primary | `primary`, `primaryContainer` | Main actions and current selection |
| Secondary | `secondary`, `secondaryContainer` | Supporting actions and metadata |
| Tertiary | `tertiary`, `tertiaryContainer` | Running or differentiated status |
| Error | `error`, `errorContainer` | Failures and destructive actions |
| Connected | `customColors.onlineColor` | Authenticated live connection |
| Transitional | `customColors.reconnectingColor` | Connecting and loading |
| Offline | `customColors.offlineColor` | Unconfigured or disconnected |

Raw colors are not introduced in feature UI. Transparent surfaces use `Color.Transparent` only.

## 3. Typography

Use semantic `MaterialTheme.typography` roles. Headlines use `headline*`, screen and panel titles use `title*`, primary content uses `body*`, and status or metadata uses `label*`. Do not introduce standalone font families or raw sizes when an existing role expresses the hierarchy.

## 4. Spacing & Layout

Spacing follows a 4 dp base grid. Common values are 4, 8, 12, 16, 20, 24, and 32 dp.
Shared dimensions live in `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/theme/LettaDimens.kt`:
`Pane.railWidth` is 56 dp, `railExpandedWidth` is 248 dp, and `navWidth` is 220 dp.
The shared shell owns adaptive pane composition; do not derive it from the old web-only
`WideNavigationWidth` constant, which is no longer present. `ChatColumnMaxWidth` in
`sharedUI/.../ui/chat/ChatLayout.kt` constrains message/composer content to 760 dp.

## 5. Components

### Connection status
- **Structure**: status dot, transport/state label, optional error detail.
- **States**: unconfigured, connecting, connected, failed.
- **Accessibility**: state is always expressed in text, never color alone.

### Agent navigation
- **Structure**: compact agent selector plus optional expanded rail/sidebar.
- **States**: empty, loading, selected, connection error.
- **Accessibility**: every icon has a content description, every agent remains selectable by text, and icon-only actions expose a side-aware tooltip after 150 ms hover.

### Chat composer
- **Structure**: centered 760 dp tonal input surface, multiline field, compact context controls, semantic send button, and desktop shortcut hint row.
- **States**: input acceptance comes from `ChatComposerUiState.acceptsInput`; sending requires a payload and `canSend`, or streaming with `canQueueWhileStreaming`. An active run can accept a queued draft. During streaming the action is Stop when cancellation is pending, queuing is unavailable, or the draft has no payload; otherwise it is Send (see `ComposerPolicy.kt`).
- **Accessibility**: disabled input and action state follow the owner-provided composer capabilities; connection text alone is not the send policy.

### Chat messages
- **User prompt**: full-width, quietly outlined `surfaceContainerLow` card within the readable column.
- **Assistant narration**: plain text beside the radial teal identity sphere; no generic assistant bubble.
- **Identity gradients**: rail/sidebar orbs use the Desktop palette (`#F0A03C→#E0457B`, `#E0457B→#8E5CFF`, `#3FA0F0→#3FE0C0`, `#7AD08F→#3FA0A0`, `#8E7CFF→#3F6EF0`, `#3FC0D0→#3F90A0`). The six pairs are declared once, as `AgentOrbGradients` in `sharedUI/.../chat/AgentOrb.kt`; `MascotIdentity.kt` holds the mascot's solid `MascotPalette` colors, not these gradients.

## 6. Motion & Interaction

Use Material component motion and short local state transitions only. Navigation remains conservative. Tooltips appear beside their anchor after 150 ms and flip direction near viewport edges, matching Desktop. Loading indicators communicate active work; no animation hides connection or protocol failures. Respect platform reduced-motion behavior through Compose defaults.

## 7. Depth & Surface

The strategy is mixed tonal shift plus restrained one-pixel outlines. `surfaceContainer*` roles establish hierarchy; `outlineVariant` separates stable chrome. Shadows are reserved for Material components that own elevation behavior.
