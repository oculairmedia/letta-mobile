# Desktop App — Design-Template Update Spec

Date: 2026-06-21
Owner of execution: Claude Code (local, can run Gradle)
Design source of truth: Penpot file "letta mobile", page **App Mockups v2** (mobile chat chrome, agent affordances, tokens). This spec translates that template into the `:desktop` Compose target.

> Build/verify locally (network drive can't be built from the design sandbox):
> ```
> cd android-compose
> export JAVA_HOME=/path/to/jdk-26
> ./gradlew --no-daemon :desktop:compileKotlinJvm   # or the desktop run task
> ./gradlew --no-daemon :designsystem:compileDebugKotlin :sharedLogic:compileKotlinMetadata
> ```
> Follow CLAUDE.md: track work with **bd** (not TodoWrite), run quality gates, and complete the session-close push protocol.

---

## Goal

Make the desktop app visually match the mobile design template:
1. **Re-base desktop color** off Jewel's palette onto our `LettaColorTokens` (teal/dark) values, and add new semantic agent-status tokens.
2. **Restyle the desktop chat header** into the floating "agent island."
3. **Add agent-status affordances** (subagent progress + schedule/heartbeat) to the desktop chat header.

Decision on file: **full re-base** (Option A) — desktop should use our palette, not Jewel-derived colors.

---

## Phase 1 — Token foundation (shared + desktop)

### 1.1 New semantic tokens (values)

Add these everywhere tokens are declared. Light values included for when desktop gains a light scheme; desktop is dark-only today.

| Token | Dark | Light | Use |
|---|---|---|---|
| `running` | `#E0A458` | `#B26A00` | in-progress / heartbeat-active / recommended (amber) |
| `onRunning` | `#2B1B00` | `#FFFFFF` | text/icon on a `running` fill |
| `success` | `#46C08F` | `#2E9E73` | connected / completed (green) |
| `onSuccess` | `#06302B` | `#FFFFFF` | text/icon on a `success` fill |
| `agentA` | `#8B7CF0` | `#6B4EE6` | subagent identity 1 (violet) |
| `agentB` | `#4C9AFF` | `#1A73E8` | subagent identity 2 (blue) |
| `agentC` | `#E36FB3` | `#C03D8E` | subagent identity 3 (magenta) |

> Note: `CustomColors` already has `successColor`/`successContainerColor` (currently mapped from `primary`/`primaryContainer`). Reconcile: repoint `successColor` to the new green `#46C08F`/`#2E9E73` (it's used by `ActiveSubagentBar` ring for RUNNING/SUCCESS — confirm that still reads correctly after the change, or split RUNNING→`running` amber and SUCCESS→`success` green per the template, which distinguishes "working" from "done").

### 1.2 `LettaColorTokens` — `sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/ThemeTokens.kt`

Add `const val` ARGB entries for the new tokens (dark + light), alongside the existing `darkPrimary` etc.:
```kotlin
// Agent status tokens
const val darkRunning      = 0xFFE0A458
const val lightRunning     = 0xFFB26A00
const val darkOnRunning    = 0xFF2B1B00
const val lightOnRunning   = 0xFFFFFFFF
const val darkSuccess      = 0xFF46C08F
const val lightSuccess     = 0xFF2E9E73
const val darkOnSuccess    = 0xFF06302B
const val lightOnSuccess   = 0xFFFFFFFF
const val darkAgentA       = 0xFF8B7CF0
const val lightAgentA      = 0xFF6B4EE6
const val darkAgentB       = 0xFF4C9AFF
const val lightAgentB      = 0xFF1A73E8
const val darkAgentC       = 0xFFE36FB3
const val lightAgentC      = 0xFFC03D8E
```
Also confirm these surface levels exist (the template uses them); add any missing as consts so the desktop scheme can reference them:
`surfaceContainerLowest #0D0D0D`, `surfaceContainerLow #121212`, `surfaceContainer #1E1E1E`, `surfaceContainerHigh #2A2A2A`, `surfaceContainerHighest #353535`, `outlineVariant #303030`.

### 1.3 `CustomColors` — `sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/ui/theme/CustomColors.kt`

Add fields (default `Color.Unspecified`):
```kotlin
val runningColor: Color = Color.Unspecified,
val onRunningColor: Color = Color.Unspecified,
val onSuccessColor: Color = Color.Unspecified,
val agentAColor: Color = Color.Unspecified,
val agentBColor: Color = Color.Unspecified,
val agentCColor: Color = Color.Unspecified,
// successColor already exists — repoint to #46C08F per 1.1
```

### 1.4 `deriveCustomColors()` — `designsystem/src/main/java/com/letta/mobile/ui/theme/Theme.kt`

Populate the new fields. These are **fixed brand values** (not derived from the dynamic scheme) so they match the template across presets:
```kotlin
runningColor   = Color(if (dark) 0xFFE0A458 else 0xFFB26A00),
onRunningColor = Color(if (dark) 0xFF2B1B00 else 0xFFFFFFFF),
successColor   = Color(if (dark) 0xFF46C08F else 0xFF2E9E73),
onSuccessColor = Color(if (dark) 0xFF06302B else 0xFFFFFFFF),
agentAColor    = Color(if (dark) 0xFF8B7CF0 else 0xFF6B4EE6),
agentBColor    = Color(if (dark) 0xFF4C9AFF else 0xFF1A73E8),
agentCColor    = Color(if (dark) 0xFFE36FB3 else 0xFFC03D8E),
```
(`dark` = whether the active scheme is dark; derive from `colorScheme` luminance or pass the flag in.)

### 1.5 Desktop theme re-base — `desktop/.../DesktopJewelTheme.kt` → `DesktopMaterialTheme`

Replace the Jewel-derived `darkColorScheme(...)` mapping with our palette, and provide `LocalCustomColors`:
```kotlin
@Composable
internal fun DesktopMaterialTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary               = Color(0xFF00BFA5),
        onPrimary             = Color(0xFF06302B),
        primaryContainer      = Color(0xFF009688),
        onPrimaryContainer    = Color(0xFFE6F4F1),
        secondary             = Color(0xFF1DE9B6),
        secondaryContainer    = Color(0xFF2A2A2A),
        onSecondaryContainer  = Color(0xFFE0E0E0),
        tertiary              = Color(0xFF00E5FF),
        background            = Color(0xFF0A0A0A),
        onBackground          = Color(0xFFE0E0E0),
        surface               = Color(0xFF121212),
        onSurface             = Color(0xFFE0E0E0),
        surfaceVariant        = Color(0xFF1E1E1E),
        onSurfaceVariant      = Color(0xFFBDBDBD),
        surfaceContainerLowest  = Color(0xFF0D0D0D),
        surfaceContainerLow     = Color(0xFF121212),
        surfaceContainer        = Color(0xFF1E1E1E),
        surfaceContainerHigh    = Color(0xFF2A2A2A),
        surfaceContainerHighest = Color(0xFF353535),
        outline               = Color(0xFF424242),
        outlineVariant        = Color(0xFF303030),
        error                 = Color(0xFFCF6679),
        errorContainer        = Color(0xFF93000A),
        onError               = Color(0xFF000000),
        onErrorContainer      = Color(0xFFFFDAD6),
    )
    CompositionLocalProvider(LocalCustomColors provides deriveCustomColors(scheme /*, dark = true */)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
```
Keep `DesktopJewelTheme` (window chrome) as-is; only the Material layer changes. Verify the Jewel `TitleBar` still reads acceptably against the new `surface`/`background` (tweak `DesktopJewelWindow` title-bar colors if there's a clash).

**Risk/QA:** this recolors the entire desktop. Sweep every desktop surface (nav sidebar, conversation pane, memory/schedule/channel/tool surfaces, cards, chips) for hard-coded colors or Jewel-color reads that now clash; route them through `colorScheme`/`customColors`.

---

## Phase 2 — Desktop chat header → agent island

File: `desktop/.../chat/DesktopChatSurface.kt`, function `ChatHeader()` (~line 573).

Current: a flush full-width `Row` (28.dp padding) with avatar + title/subtitle + a "Graph …" status chip, divided from the message list by a 1.dp line.

Target (desktop adaptation of the mobile island — **no** mid-right hamburger; desktop nav stays in the rail/sidebar):
- Wrap the header content in a floating **island**: a `Surface`/`Box` with `shape = RoundedCornerShape(24.dp)`, `color = colorScheme.surfaceContainer`, a 1.dp `outlineVariant` border, and a soft shadow (`Modifier.shadow(elevation = 8.dp, shape = …, clip = false)`), inset with ~16–20.dp margin from the pane edges. Let the message list scroll under it (optional top fade: a `Brush.verticalGradient(background → transparent)` overlay strip at the top of the message area).
- Inside the island, left→right:
  - Agent avatar (keep 44.dp, `primaryContainer`, `SmartToy`) — or the gradient orb to match the mockup.
  - Agent name (`titleLarge`, SemiBold) + a status line.
  - **Status cluster** (Phase 3): pulsing dot (heartbeat) + schedule countdown chip + subagent chips.
- Remove the flush `28.dp` row styling; the divider below can go (the island provides separation).

Reference the mockup boards: `Top island …` / `Mid-ham …` on App Mockups v2 (desktop keeps the island, drops the FAB).

---

## Phase 3 — Agent-status affordances (desktop)

Port/adapt the Android `feature-chat/.../subagent/ActiveSubagentBar.kt` concept into the desktop header status cluster.

### 3.1 Subagent indicator
- A row of **progress chips**: each = a circular progress ring around a small avatar with the subagent's initial, colored by identity (`customColors.agentAColor/agentB/agentC`), ring = `runningColor` while working, `successColor` when done (with a check).
- One chip per active subagent; collapse to circles-only as count grows; a trailing count.
- Clicking a chip opens the subagent's to-do/transcript (desktop has room — use a side panel or popover rather than the mobile bottom sheet).
- States/colors (match template): working → `running` ring; done → `success` ring + check; identity fill → `agentA/B/C`.

### 3.2 Schedule / heartbeat indicator
- **Pulsing dot** next to the agent name = current activity: idle = `onSurfaceVariant`; heartbeat-active = pulsing `running` (amber); scheduled-run firing = pulsing `success` (green).
- **Countdown chip** = next scheduled run: a clock glyph + "in 2h 14m" (`onSurfaceVariant`; switch to `running` when imminent). Click → a schedule popover listing next runs + "Create schedule" (desktop already has `DesktopScheduleLibrarySurface` — link the popover's "manage/create" to it).

---

## Acceptance criteria
- Desktop renders with our palette (teal primary `#00BFA5`, `#0A0A0A` background, `#1E1E1E` surfaces) — not Jewel hues.
- `running/success/agentA-C` available via `MaterialTheme.customColors` on desktop and used by the affordances.
- Desktop chat header reads as a floating island (rounded, shadowed, `surfaceContainer`).
- Subagent chips + schedule/heartbeat indicator appear in the header, colored per template.
- `:desktop` and `:designsystem`/`:sharedLogic` compile; no regression in other desktop surfaces.

## File checklist
- `sharedLogic/.../ui/theme/ThemeTokens.kt` — new ARGB consts (1.2)
- `sharedLogic/.../ui/theme/CustomColors.kt` — new fields (1.3)
- `designsystem/.../ui/theme/Theme.kt` — `deriveCustomColors()` (1.4)
- `desktop/.../DesktopJewelTheme.kt` — `DesktopMaterialTheme` re-base + `LocalCustomColors` (1.5)
- `desktop/.../DesktopJewelWindow.kt` — title-bar color reconcile (if needed)
- `desktop/.../chat/DesktopChatSurface.kt` — `ChatHeader()` island + status cluster (Phase 2–3)
- (reference) `feature-chat/.../subagent/ActiveSubagentBar.kt` — chip logic to port

---

## bd issue breakdown

> File these with **bd** (run `bd prime` first to confirm flag syntax for your version — the commands below are a starting point). Suggested structure: 1 epic + 3 phase tasks, with Phases 2 and 3 blocked by Phase 1.

### EPIC — Desktop: match the design template
- **Type:** epic / **Priority:** high
- **Summary:** Bring the `:desktop` Compose target in line with the mobile design template (Penpot "App Mockups v2"): re-base color onto our tokens, restyle the chat header into the agent island, and add agent-status affordances.
- **Spec:** `docs/design/desktop-template-update-spec.md`
- **Done when:** all three child tasks closed and `:desktop` builds with the template applied (see acceptance criteria above).

### TASK 1 — Token foundation: re-base desktop color + add agent tokens
- **Type:** task / **Priority:** high / **Blocks:** Task 2, Task 3
- **Scope:** Spec §1.1–1.5.
  - Add `running/onRunning/success/onSuccess/agentA/agentB/agentC` consts to `ThemeTokens.kt` (dark+light); add any missing surface levels.
  - Add fields to `CustomColors.kt`; populate in `deriveCustomColors()` (Theme.kt) with the fixed brand values; reconcile existing `successColor` → `#46C08F`.
  - Re-base `DesktopMaterialTheme` `darkColorScheme` onto our palette and provide `LocalCustomColors` (DesktopJewelTheme.kt); reconcile Jewel title-bar colors if they clash.
- **Acceptance:**
  - Desktop renders with teal `#00BFA5` primary, `#0A0A0A` background, `#1E1E1E` surfaces (not Jewel hues).
  - `MaterialTheme.customColors.{running,success,agentA,agentB,agentC}` resolve on desktop.
  - `:sharedLogic`, `:designsystem`, `:desktop` compile; no color regressions across desktop surfaces (nav, conversation pane, memory/schedule/channel/tool surfaces).
- **Files:** ThemeTokens.kt, CustomColors.kt, Theme.kt, DesktopJewelTheme.kt, (DesktopJewelWindow.kt if needed).

### TASK 2 — Desktop chat header → agent island
- **Type:** task / **Priority:** medium / **Blocked by:** Task 1
- **Scope:** Spec §Phase 2. Restyle `ChatHeader()` in `DesktopChatSurface.kt` into a floating island (rounded `surfaceContainer`, `outlineVariant` border, soft shadow, inset margins); optional top fade over the message list; remove the flush row + divider. No mid-right hamburger (desktop nav stays in the rail).
- **Acceptance:** chat header reads as a floating island matching the mockup; message list scrolls under it cleanly; existing header info (agent name/subtitle/graph status) preserved; `:desktop` compiles.
- **Files:** DesktopChatSurface.kt.

### TASK 3 — Desktop agent-status affordances (subagent + schedule/heartbeat)
- **Type:** task / **Priority:** medium / **Blocked by:** Task 1 / **Relates to:** Task 2
- **Scope:** Spec §Phase 3. Add to the header status cluster: subagent progress chips (identity `agentA/B/C`, ring `running`→`success`, count + collapse), click → desktop side-panel/popover; schedule/heartbeat pulsing dot + next-run countdown chip, click → schedule popover linking to `DesktopScheduleLibrarySurface`. Port logic from `feature-chat/.../subagent/ActiveSubagentBar.kt`.
- **Acceptance:** subagent chips and schedule/heartbeat indicator render in the header with template colors and states; interactions open the right desktop surfaces; `:desktop` compiles.
- **Files:** DesktopChatSurface.kt (+ new desktop subagent/schedule composables), reference ActiveSubagentBar.kt.

### Suggested commands (verify against `bd prime`)
```bash
bd create "Desktop: match the design template" -t epic -p high
bd create "Token foundation: re-base desktop color + agent tokens" -t task -p high
bd create "Desktop chat header -> agent island" -t task -p medium
bd create "Desktop agent-status affordances (subagent + schedule)" -t task -p medium
# link dependencies (adjust to bd's syntax): Task2/Task3 blocked by Task1; all children of the epic
```

