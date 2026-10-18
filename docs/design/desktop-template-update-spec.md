# Desktop App — Design-Template Update Spec

Date: 2026-06-21 (updated 2026-06-23 — re-applied after the U: drive reverted earlier edits: palette retune, Phases 4–8, scheduling feature, component inventory)
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
1. **Re-base desktop color** off Jewel's palette onto our `LettaColorTokens` (teal/dark) values, and add new semantic agent-status + memory-category tokens. **Palette is retuned to "cool slate"** (see Phase 1 callout).
2. **Restyle the desktop chat header** into the floating "agent island."
3. **Add agent-status affordances** (subagent progress + schedule/heartbeat) to the desktop chat header.
4. **Work | Play mode lens** — a sidebar toggle that re-frames the same data (Phase 4).
5. **First-run / post-onboarding surface** for a new agent (Phase 5).
6. **Color hierarchy** — reserve teal for active/primary; category-code memory; neutral section captions (Phase 6).
7. **Scheduling feature** — Agenda/Week/Timeline/History views + click-detail (Phase 7).
8. **Design-system component inventory** the desktop should mirror as composables (Phase 8).

Decision on file: **full re-base** (Option A) — desktop should use our palette, not Jewel-derived colors.

---

## Phase 1 — Token foundation (shared + desktop)

> **Palette retune (2026-06-23) — "cool slate".** The neutrals were retuned from true-grey to a subtle cool blue-grey undertone (professional/clean yet interesting); teal primary `#00BFA5` unchanged. These OVERRIDE the older greys used elsewhere in this doc:
>
> | Token | Dark | Light |
> |---|---|---|
> | background | `#0B0D11` | `#FBFCFD` |
> | surface / surfaceContainerLow | `#13161B` | `#F5F7FA` |
> | surfaceVariant / surfaceContainer | `#1A1E25` | `#EAEEF3` |
> | surfaceContainerHigh / secondaryContainer | `#242A33` | `#DDE3EA` |
> | surfaceContainerHighest | `#2E343F` | `#D2D9E2` |
> | surfaceContainerLowest | `#0D0F13` | `#FFFFFF` |
> | onSurface | `#E6E9EF` | `#161A20` |
> | onSurfaceVariant | `#AEB6C2` | `#4A5360` |
> | **onSurfaceMuted** (NEW) | `#717A87` | `#6B7480` |
> | outline | `#3A414E` | `#B4BCC8` |
> | outlineVariant | `#2A2F39` | `#DCE2EA` |
> | primary | `#00BFA5` | `#00BFA5` |
> | primaryContainer | `#0E8C7E` | `#B2DFDB` |
> | success | `#3FBE93` | `#2E9E73` |
>
> `onSurfaceMuted` is a NEW token (muted section captions, darker than `onSurfaceVariant`). `running/onRunning/agentA-C/error/tertiary` unchanged from the tables below.

### 1.1b Memory-block category tokens (NEW)

Memory blocks are color-coded by category (see Phase 6). Add a `category*` group:

| Token | Dark | Light | Use |
|---|---|---|---|
| `categoryPersona` | `#00BFA5` | `#00BFA5` | persona block (= `primary`) |
| `categoryHuman` | `#5C9BD6` | `#2F6FB0` | human/user block (blue) |
| `categoryOnboarding` | `#D1A05A` | `#9A6A1F` | onboarding block (amber) |
| `categoryProject` | `#9B8AE0` | `#6E5BB8` | project block (violet) |
| `categoryArchival` | `#828B98` | `#5F6469` | archival / recall (neutral) |

Distinct from `agentA/B/C` (subagent identity). Add `const val`s in `ThemeTokens.kt` + `Color` fields in `CustomColors`/`deriveCustomColors()` as in §1.2–1.4.

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

## Phase 4 — Work | Play mode lens in the shell

A **Work | Play segmented toggle at the top of the left nav sidebar** (in the shared desktop shell). It is a **lens, not a fork** — same agents/memory/conversations/groups, re-framed. Add `enum WorkspaceMode { Work, Play }`, persisted per workspace. When Play: nav re-labels (Memory→Characters, Schedules→Worlds & Lore, Channels→Scenes, Skills→Personas, New chat→New scene), nav icons swap, composer placeholder → "Message the scene…", default message preset → narration+swipes (vs tool-cards+markdown). Toggle UI: track `surfaceContainerLow`+`outlineVariant`, active pill `surfaceContainerHigh`, active label `onSurface`/700, inactive `onSurfaceVariant`/500. Files: `desktop/.../LettaDesktopApp.kt` (+ nav-sidebar composable).

## Phase 5 — First-run / post-onboarding surface

When the active (freshly-onboarded) agent has **no conversations**, the main pane shows an inviting first-run state: `surfaceContainer` "NEW AGENT · ON-DEVICE" pill, 72.dp gradient **squircle** avatar (radius ≈ size×0.3 — desktop avatars are squircles, not circles), "{Agent} is ready" + subtitle, a **2×2 action-card grid** (Start a conversation[`primary`]/Seed a memory[`categoryHuman`]/Connect a tool[`categoryProject`]/Schedule a task[`categoryOnboarding`]), and a `success`-dot "Local · Unrestricted · {workdir}" footer. Gate on conversations.isEmpty() + completed-onboarding. New file `DesktopFirstRunSurface.kt`.

## Phase 6 — Color hierarchy: reduce all-teal

1. Reserve `primary` (teal) for active/selected nav, primary CTAs, active toggles, positive status — nothing decorative.
2. Section/group caption text → `onSurfaceMuted` (neutral), not `primary`.
3. Status group labels semantic: Schedules "ACTIVE" → `success`/`primary`, "PAUSED" → `running`.
4. Memory blocks color-coded by `category*` (graph nodes + block-row icons).

## Phase 7 — Scheduling feature (NEW — large)

The agent **Schedules** surface was fully designed. KEY INSIGHT: an agent's schedule = a set of **recurring triggers (cron-like)**, NOT human calendar events — so a month grid is weak. Build a **view switcher** (segmented tabs) over one schedule dataset:

- **Agenda** (default) — date strip + time-ordered list of materialized runs for the selected day, each with status (ran ✓ / failed ✕ / next ◷ / upcoming ○) + a live "now" line. Mobile-friendly.
- **Week** — a 7-day **time-grid** (Motion/Cron style): day columns × hour rows, runs placed as blocks at their times, colored by status; "now" line; weekday-only schedules skip the weekend.
- **Timeline** — Airflow/Temporal **per-schedule swimlane**: rows = schedules, x-axis = time; daily schedules = ticks (past solid / upcoming hollow-outline), hourly = per-day bars; "now" line.
- **History** — Cronitor-style monitoring: success-rate stats, per-schedule reliability squares (green/red), recent-runs list with durations.

**Status colors:** done = `success`, failed = `error`, next/running = `running` (amber), upcoming = `surfaceContainer` + `outlineVariant` outline.

**Click-detail = MASTER-DETAIL in the right rail (NOT a popover — desktop has a persistent sidebar):**
- Click a schedule → right rail shows: back-link, title + Active pill, "Runs in 3h 12m" next-run card, definition (Cadence/Action/Agent/If-missed), reliability strip, recent-runs list, **Run now / Pause / Edit / Delete**.
- Click a past run → run-detail: status + duration + exit code, "what it did", error, captured **output log** (mono terminal, failed line in `error`), **Retry / Full log / Go to schedule**.
- **New schedule** → a cron builder (preset dropdowns "every X / at time / on days" + custom-cron escape hatch + next-run preview), per n8n/Zapier.

**Panels are FLAT** (no rounded card backgrounds): grid + rail are flat regions separated by a 1px `outlineVariant` divider. This supersedes the old `DesktopScheduleLibrarySurface` flat list (which becomes the "Rules" tab). Mockups: "Desktop · Schedules (week/timeline)", "Desktop · Schedule detail (panel)", "Mobile · Schedules (Agenda/History)", "Mobile · Schedule detail (upcoming)", "Mobile · Run detail (past)" on App Mockups v2.

## Phase 8 — Design-system component inventory (mirror as composables)

The Penpot template is now component-driven; the desktop app should mirror these as reusable composables so iteration/permutations stay structured (edit once → propagates):

- **Chrome:** `Avatar` (squircle, Sm 32 / Lg 48), `Shell` (Work/Play variants), `Composer/Desktop`, `Tabs` (segmented; desktop 4-tab Week/Agenda/Timeline/History, mobile 3-tab), `FAB`, `Button/Link`.
- **Buttons:** `Btn/Primary`, `Btn/Outline`, `Btn/Danger` — **flexible width** (fill-bounds label so one composable serves any width; do NOT hard-code per-button widths).
- **Scheduling:** `AgendaRow`, `RuleCard`, `RunBlock` (status variants), `Stat`, `RunRow`, `RunLine`, `NextRunCard`, `DefRow` (label+value), `Reliability` (header+count+12 squares; failed squares = `error`), `LogBlock` (mono terminal), `LaneLabel`, `DayCell` (Default/Active).
- **Status/identity colors** drive variants: success/error/running/upcoming for run state; agentA-C for subagent identity; category* for memory blocks.

---

## Acceptance criteria
- Desktop renders with our palette (teal primary `#00BFA5`, cool-slate `#0B0D11` background, `#1A1E25` surfaces) — not Jewel hues.
- `running/success/agentA-C` available via `MaterialTheme.customColors` on desktop and used by the affordances.
- Desktop chat header reads as a floating island (rounded, shadowed, `surfaceContainer`).
- Subagent chips + schedule/heartbeat indicator appear in the header, colored per template.
- **Palette** is the cool-slate retune; `onSurfaceMuted` + `category*` resolve via `customColors` (Phase 1).
- **Work|Play toggle** flips nav/labels/icons/composer/preset with no data change (Phase 4).
- **First-run surface** renders for a new agent with no conversations (Phase 5).
- **Color hierarchy:** teal reserved; captions `onSurfaceMuted`; memory color-coded (Phase 6).
- **Scheduling** surface ships the 4 views + master-detail right-rail (not popover) + run-detail + cron builder, on flat panels (Phase 7).
- **Components:** buttons/tabs/schedule rows are reusable composables, not bespoke per-screen (Phase 8).
- `:desktop` and `:designsystem`/`:sharedLogic` compile; no regression in other desktop surfaces.

## File checklist
- `sharedLogic/.../ui/theme/ThemeTokens.kt` — new ARGB consts (1.2)
- `sharedLogic/.../ui/theme/CustomColors.kt` — new fields (1.3)
- `designsystem/.../ui/theme/Theme.kt` — `deriveCustomColors()` (1.4)
- `desktop/.../DesktopJewelTheme.kt` — `DesktopMaterialTheme` re-base + `LocalCustomColors` (1.5)
- `desktop/.../DesktopJewelWindow.kt` — title-bar color reconcile (if needed)
- `desktop/.../chat/DesktopChatSurface.kt` — `ChatHeader()` island + status cluster (Phase 2–3)
- (reference) `feature-chat/.../subagent/ActiveSubagentBar.kt` — chip logic to port
- `sharedLogic/.../ui/theme/ThemeTokens.kt` / `CustomColors.kt` / `Theme.kt` — cool-slate retune + `onSurfaceMuted` + `category*` (Phase 1, 1.1b)
- `desktop/.../LettaDesktopApp.kt` (+ nav-sidebar) — Work|Play lens (Phase 4)
- `desktop/.../DesktopFirstRunSurface.kt` (new) — post-onboarding empty state (Phase 5)
- Memory/Settings/list surfaces — captions → `onSurfaceMuted`; Memory nodes → `category*` (Phase 6)
- `desktop/.../schedule/DesktopScheduleSurface.kt` (new) — Agenda/Week/Timeline/History views + right-rail master-detail + run-detail + cron builder; supersedes `DesktopScheduleLibrarySurface` (now the Rules tab) (Phase 7)
- `designsystem/.../components/` — reusable composables: Avatar, Shell, Tabs, Btn{Primary,Outline,Danger}, schedule rows/cards (Phase 8)

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

### TASK 4 — Work | Play mode lens (Phase 4)
- **Type:** task / **Priority:** medium / **Blocked by:** Task 1
- **Scope:** `WorkspaceMode { Work, Play }` (persisted); sidebar segmented toggle; mode drives nav labels+icons, composer placeholder, default preset. Lens only — no data fork.
- **Files:** LettaDesktopApp.kt (+ nav-sidebar), settings/DataStore.

### TASK 5 — First-run / post-onboarding surface (Phase 5)
- **Type:** task / **Priority:** medium / **Relates to:** Task 1
- **Scope:** `DesktopFirstRunSurface` when active onboarded agent has no conversations: badge, squircle avatar, "{Agent} is ready", 2×2 category-colored action cards, `success`-dot footer; routes to composer/memory/skills/schedule.
- **Files:** DesktopFirstRunSurface.kt (+ wiring in chat host).

### TASK 6 — Color hierarchy + category tokens (Phase 6, §1.1b)
- **Type:** task / **Priority:** medium / **Relates to:** Task 1
- **Scope:** add `onSurfaceMuted` + `category*`; reserve `primary`; captions → `onSurfaceMuted`; status labels semantic; color-code Memory by `category*`.
- **Files:** ThemeTokens.kt, CustomColors.kt, Theme.kt, Memory/Settings/list surfaces.

### TASK 7 — Scheduling feature (Phase 7) — LARGE
- **Type:** task (consider sub-epic) / **Priority:** high / **Blocked by:** Task 1
- **Scope:** `DesktopScheduleSurface` with segmented Agenda/Week/Timeline/History views over one schedule dataset; right-rail master-detail on schedule click (definition + next-run + reliability + recent runs + Run now/Pause/Edit/Delete); run-detail on past-run click (status/duration/exit/log/retry); New-schedule cron builder; flat panels + 1px divider; supersede `DesktopScheduleLibrarySurface` → "Rules" tab.
- **Acceptance:** all 4 views render; clicking a schedule fills the right rail (NOT a popover); past-run shows captured log; cron builder previews next run; `:desktop` compiles.
- **Files:** desktop/.../schedule/DesktopScheduleSurface.kt (+ detail/run-detail/cron-builder composables).

### TASK 8 — Reusable component library (Phase 8)
- **Type:** task / **Priority:** medium / **Relates to:** Task 7
- **Scope:** extract reusable composables (Avatar, Shell, Tabs, Btn{Primary,Outline,Danger} flexible-width, schedule rows/cards/strips) into `designsystem`; drive variants by status/identity/category colors. No bespoke per-screen buttons.
- **Files:** designsystem/.../components/.

### Suggested commands (verify against `bd prime`)
```bash
bd create "Desktop: match the design template" -t epic -p high
bd create "Token foundation: re-base desktop color + agent tokens" -t task -p high
bd create "Desktop chat header -> agent island" -t task -p medium
bd create "Desktop agent-status affordances (subagent + schedule)" -t task -p medium
bd create "Work|Play mode lens" -t task -p medium
bd create "Desktop first-run surface" -t task -p medium
bd create "Color hierarchy + category tokens" -t task -p medium
bd create "Scheduling feature (Agenda/Week/Timeline/History + detail)" -t task -p high
bd create "Reusable component library (buttons/tabs/schedule rows)" -t task -p medium
# deps: Task2/3/4 blocked by Task1; Task7 blocked by Task1; Task8 relates to Task7; all children of the epic
```

