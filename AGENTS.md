# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd dolt push          # Push beads data to remote
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**

```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**

- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

## Material Design System Rules

Use these rules for all new UI work and UI refactors in this repo. The app already uses Material 3 broadly; the goal is to use it **systematically**.

### 1. Stable chrome, expressive content

- Treat app bars, drawers, and other navigation chrome as **stable structure**.
- Put rich emphasis, hero moments, and shared transitions on **content surfaces** like cards, rows, tiles, and detail headers.
- Avoid anchoring critical shared-element transitions inside unstable collapsing chrome unless there is no safer option.

### 2. Fix geometry before tuning motion

- If an animation glitches, first fix layout/state restoration and anchor stability.
- Do **not** try to solve geometry bugs with spring tuning, expressive motion, or shape morphing alone.
- Shared-element transitions must use deterministic source and destination bounds before any motion polish is added.

### 3. Prefer reusable screen shells over ad hoc screen composition

- Search/list/admin screens should converge on a standard shell:
  - `Scaffold`
  - `LargeFlexibleTopAppBar` (or a simpler top bar when hierarchy does not need a flexible header)
  - optional search slot
  - optional selection/filter row
  - optional FAB slot
  - standardized loading / error / empty / content states
- Reuse existing design-system components instead of rebuilding screen structure per feature.
- `AllToolsScreen`, `AgentListScreen`, and `McpScreen` are the current reference implementations for this shell pattern.
- The first dedicated reusable scaffold adopters should be `letta-mobile-bz40.2.2` (project grid home screen) and `letta-mobile-bz40.2.6` (active agents dashboard), then back-port the extracted scaffold to older admin/list screens as they are touched.

### 4. Use Material components by interaction semantics

- Use **segmented buttons** or another mutually-exclusive selector pattern for binary/mode switches when only one option can be active.
- Use **chips** for filters, tags, or multi-select style controls.
- Use **input chips** for user-added items such as editable tags or removable selections.
- Use **assist chips** for read-only metadata or lightweight status labels.
- Use **bottom sheets** / `ActionSheet` for contextual and destructive action menus.
- Use **dialogs** / `ConfirmDialog` for confirmation and blocking decisions.
- Use **switches** for direct on/off settings, not for choosing between multiple modes.
- Use **cards** and **surfaces** to communicate emphasis and hierarchy, not arbitrary decoration.

### 5. Motion policy

- Keep one motion language across the app: low-surprise navigation, stable shared elements, and small expressive accents inside content.
- The app theme already uses `MaterialExpressiveTheme` with `MotionScheme.expressive()`, but navigation should still feel conservative and readable.
- Fix geometry and state restoration before tuning motion. If a transition is glitchy, first verify stable anchors, scroll state, and consistent source/destination layout.

#### Navigation transitions

- Prefer the centralized drill transition pattern in `android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AppNavGraph.kt` for drill-in screens.
- Keep navigation transitions brief, directional, and consistent. Use the shared drill timing/constants instead of inventing per-screen durations or easing.
- Do not add one-off route animations unless the navigation model is materially different and the exception is documented in the owning screen or issue.
- When a screen does not need special treatment, prefer default navigation behavior over custom animation.

#### Shared-element transitions

- Shared elements must go through `optionalSharedElement()` so they degrade safely when no shared scopes are present.
- Only use shared-element motion for stable content-to-content relationships such as list/grid items to detail headers.
- Never anchor critical shared elements inside unstable or collapsing chrome such as `LargeFlexibleTopAppBar`, collapsing search bars, or transient toolbar content.
- Prefer destination anchors in stable content surfaces like cards, rows, tiles, or fixed detail headers.
- Shared-element keys must be deterministic and unique to the item identity.
- If either side cannot provide stable bounds, remove the shared element and fall back to standard navigation motion.

#### Sheets, drawers, and contextual surfaces

- Use Material components such as `ModalBottomSheet`, `ActionSheet`, `ModalNavigationDrawer`, and dialogs for container motion instead of hand-rolled enter/exit animation.
- Let container components own their default motion unless a reusable design-system wrapper needs a documented adjustment.
- Contextual actions should appear from sheets or menus, not from bespoke animated overlays.

#### Micro-interactions

- Use expressive motion for local content changes only after layout geometry is correct.
- Preferred patterns are small, reusable helpers like `AnimatedVisibility`, `animateContentSize`, and icon rotation for expand/collapse, banners, and inline affordances.
- Keep micro-motion close to the component that owns the state change; avoid animating entire screens for small local updates.
- Avoid stacking multiple animations on the same interaction unless they clearly reinforce hierarchy.

#### Lazy lists, loading, and progressive data

- Do not use motion to hide expensive loading or unstable list geometry.
- For list/detail flows, preserve stable item identity and restore list/grid/top-app-bar state before adding polish.
- Prefer progressive rendering when secondary data arrives later. Show primary content first and add lightweight status affordances, such as the MCP loading banner, instead of blocking the full screen.
- Loading motion should communicate status, not delay access. Use existing shimmer/progress patterns and remove them as soon as content is ready.

#### Decision rules

- If motion clarifies spatial relationship or state change, keep it.
- If motion competes with readability, causes layout jumps, or depends on fragile anchors, simplify or remove it.
- Reuse existing repo patterns before adding a new motion treatment. New reusable motion behavior belongs in `android-compose/designsystem` or shared navigation helpers, not inside a single screen.

### 6. Color role policy

The app theme defines **primary**, **secondary**, **tertiary**, and **error** color roles across all 6 presets. Use each role for its intended semantic purpose.

#### Role assignments

| Color role | Semantic purpose | Typical components |
|---|---|---|
| `primary` / `primaryContainer` | Main brand action, default emphasis | Primary buttons, FABs on main screens, active nav items, progress indicators |
| `secondary` / `secondaryContainer` | Supporting actions and less-prominent selections | Filter chips, secondary buttons, less-prominent toggles |
| `tertiary` / `tertiaryContainer` | Contrasting accent that balances primary/secondary, draws differentiated attention | Accent FABs on secondary screens, "live/active" status indicators, third data-series color in charts, highlight badges, feature-differentiating accents |
| `error` / `errorContainer` | Error and destructive states | Error text, destructive confirmations, failure indicators |
| `surface*` variants | Container hierarchy and elevation | Cards, sheets, scaffolds, dialogs |

#### Tertiary introduction targets

Tertiary is currently underused. When touching these areas, prefer tertiary over inventing one-off accent colors:

- **Status differentiation** — use `tertiaryContainer`/`onTertiaryContainer` for "running", "live", or "active" states that need to contrast with primary success and secondary idle states. `UsageScreen` running chips and `AgentListScreen` already do this — extend the pattern to other status surfaces.
- **Chart accent colors** — use tertiary as the third data-series color alongside primary and secondary in Vico charts and any future data visualization.
- **Accent FABs** — when a screen already has a primary-colored FAB or primary-colored main action, use `tertiaryContainer`/`onTertiaryContainer` for a secondary FAB or competing action to avoid color collision.
- **Highlight badges** — "new", "beta", or feature-differentiating badges that need to stand out from both primary and secondary chip colors.

#### Existing tertiary derivations in `CustomColors`

`deriveCustomColors()` in `Theme.kt` already maps tertiary into:
- `reasoningBubbleBgColor` → `tertiaryContainer` at 45% alpha (reasoning chat bubbles)
- `warningContainerColor` → `tertiaryContainer` (warning state backgrounds)
- `warningTextColor` → `onTertiaryContainer` (warning state text)

Do not duplicate these — use `MaterialTheme.customColors.warningContainerColor` etc. for warning states rather than reaching for `tertiaryContainer` directly.

#### Rules

- Never use raw hex colors when a color role already expresses the intent. If primary, secondary, or tertiary fits, use the role.
- When adding a new accent that does not fit primary or secondary, reach for `tertiary`/`tertiaryContainer` before inventing a custom color.
- If a screen needs more than three accent colors beyond error, add a semantic slot to `CustomColors` and derive it from an existing role rather than hardcoding a hex value.
- Keep `Color.kt` for named reusable constants. If a new tertiary-derived constant is needed app-wide, define it there.

#### Surface and hierarchy

- Follow the repo theme layer and `LettaTopBarDefaults` rather than inventing per-screen app-bar/surface colors.
- Use consistent corner-radius and surface emphasis rules across similar cards and tiles.
- Prefer a clear surface hierarchy over stacking multiple decorative treatments on the same element.

### 7. Typography rules

- Use the existing repo typography tokens and extensions (`MaterialTheme.typography`, `listItemHeadline`, `listItemSupporting`, etc.) as the default.
- Prefer semantic extensions from `TypeHierarchy.kt` such as `sectionTitle`, `chatBubbleSender`, and `statValue` over one-off styling in screens.
- Do not add raw `fontWeight` overrides in feature UI when an existing typography token can express the hierarchy. Add or extend a semantic token first, then reuse it.
- Introduce expressive or emphasized typography only for real hierarchy changes or hero moments, not as ad hoc styling.

### 8. Prefer in-repo patterns first

- Before inventing a new UI pattern, search for an existing implementation in the repo and reuse/adapt it.
- The `android-compose/designsystem` module is the canonical home for reusable UI foundations. Add new shared components there rather than creating per-screen one-offs.
- Current reusable foundations include `ActionSheet`, `ConfirmDialog`, `EmptyState`, `ErrorContent`, themed top-bar defaults, and the shared design-system theme layer.

### 9. Design-system direction issues

- `letta-mobile-q7jt` — Establish Material design system guardrails
- `letta-mobile-iqwq` — Build reusable searchable list screen scaffold
- `letta-mobile-pkzn` — Define motion and navigation transition policy
- `letta-mobile-ns9n` — Standardize selection and emphasis components
- `letta-mobile-k8x0` — Introduce tertiary color role across app UI

### 10. Current design-system migration checklist

- **ActionSheet targets**
  - `FolderAdminScreen`, `ProviderAdminScreen`, `IdentityListScreen`, and other admin list cards with inline edit/delete icon rows should converge on an overflow or long-press `ActionSheet` for contextual actions.
  - Detail screens that currently expose multiple secondary actions inside dialogs should prefer `ActionSheet` when the action set is contextual rather than confirmational.
- **ConfirmDialog / TextInputDialog targets**
  - Use `ConfirmDialog` for dismissible error/info dialogs only when the interaction is a single acknowledge/confirm step; keep destructive confirmations on `ConfirmDialog` rather than raw `AlertDialog`.
  - Use `TextInputDialog` for single-field rename/create flows before reaching for raw `AlertDialog`.
  - Multi-field editor flows such as `FolderAdminScreen`, `ProviderAdminScreen`, `ScheduleListScreen`, `EditAgentScreen`, `McpScreen`, and `AgentListScreen` should converge on reusable dialog wrappers instead of bespoke `AlertDialog` forms over time.
- **FormItem targets**
  - `AgentListScreen` create/import dialogs and `ScheduleListScreen` create dialog should use `FormItem` for label + helper + switch rows.
  - `McpScreen` server form, `ProviderAdminScreen` editor dialog, `FolderAdminScreen` editor dialog, and `EditAgentScreen` block/tag sections are the next targets for `FormItem` adoption.
- **CardGroup targets**
  - `ConfigScreen` and `AgentSettingsScreen` are the reference implementations.
  - Grouped admin/detail surfaces in `FolderAdminScreen`, `ProviderAdminScreen`, `IdentityListScreen`, `ArchiveAdminScreen`, `McpServerToolsScreen`, `RunMonitorScreen`, `JobMonitorScreen`, `MessageBatchMonitorScreen`, and `ToolDetailScreen` should migrate from flat `Column` dialog bodies toward `CardGroup` sections.
- Treat this checklist as the default migration map when touching any of the above screens. Reuse the existing designsystem component before inventing a new layout or dialog pattern.

### 11. Current old-style screen audit

- Most primary screens already use `LettaTopBarDefaults` plus the standardized `Scaffold` / top-bar shell. Treat shell migration as largely complete.
- Remaining modernization work is concentrated in **dialogs, pickers, and admin/detail surfaces**, not the screen chrome itself.
- The highest-value remaining targets are:
  - `ToolPickerDialog`, `CreateToolDialog`, `BlockPickerDialog` — migrate bespoke dialog bodies toward reusable dialog wrappers and semantic typography.
  - `FolderAdminScreen`, `ProviderAdminScreen`, `GroupAdminScreen`, `IdentityListScreen`, `ArchiveAdminScreen`, `McpServerToolsScreen`, `ToolDetailScreen` — replace flat detail columns and raw dialogs with `CardGroup`, `ConfirmDialog`, `TextInputDialog`, and `ActionSheet` where semantics match.
  - `EditAgentScreen`, `ScheduleListScreen`, `McpScreen`, `AgentListScreen` — continue migrating multi-field forms toward `FormItem` and reusable dialog structure.
- When auditing a screen now, prioritize: (1) raw `AlertDialog` replacement, (2) grouped detail surfaces, (3) semantic typography, and only then shell-level chrome changes.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->

## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**

- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
