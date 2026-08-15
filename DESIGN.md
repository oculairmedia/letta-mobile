# Letta Mobile Design System

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

Spacing follows a 4 dp base grid. Common values are 4, 8, 12, 16, 20, 24, and 32 dp. Compact web layouts use one content pane and top navigation; expanded layouts may expose the 68 dp agent rail and 260 dp sidebar. Chat content remains width-constrained for readability while the shell fills the viewport.

## 5. Components

### Connection status
- **Structure**: status dot, transport/state label, optional error detail.
- **States**: unconfigured, connecting, connected, failed.
- **Accessibility**: state is always expressed in text, never color alone.

### Agent navigation
- **Structure**: compact agent selector plus optional expanded rail/sidebar.
- **States**: empty, loading, selected, connection error.
- **Accessibility**: every icon has a content description and every agent remains selectable by text.

### Chat composer
- **Structure**: multiline field and semantic send button.
- **States**: disabled while unconfigured, connecting, loading history, or running a turn.
- **Accessibility**: the disabled state follows the same truth as the connection label.

## 6. Motion & Interaction

Use Material component motion and short local state transitions only. Navigation remains conservative. Loading indicators communicate active work; no animation hides connection or protocol failures. Respect platform reduced-motion behavior through Compose defaults.

## 7. Depth & Surface

The strategy is mixed tonal shift plus restrained one-pixel outlines. `surfaceContainer*` roles establish hierarchy; `outlineVariant` separates stable chrome. Shadows are reserved for Material components that own elevation behavior.
