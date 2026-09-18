# UI cleanup & token adherence — handoff

Work brief for agents doing the alignment pass. Epic: `letta-mobile-hzddx`.

Supersedes nothing; `docs/ui-alignment-fanout.md` is the same job written before
the reference implementations existed. **Read this one** — it says what to copy.

---

## Current state

```bash
python scripts/ui-design-lint.py --summary
```

**2,798 findings across 234 files**

| Rule | Count | Meaning |
|---|---|---|
| `raw-dimension` | 2,598 | `12.dp` / `14.sp` at a call site instead of a token |
| `low-content-alpha` | 114 | `.copy(alpha = <0.6)` on a content colour |
| `hardcoded-color` | 86 | `Color(0xFF…)` bypassing the theme |

The baseline in `config/ui-design-lint-baseline.json` is 2,833 from when the
tooling landed. **It only goes down.** Regenerate it only when you finish, not
per file, or parallel agents race each other.

---

## What already exists — copy these, don't invent

| Asset | What it is |
|---|---|
| `sharedUI/…/ui/theme/LettaDimens.kt` | **The scale.** Every dimension comes from here. |
| `sharedUI/…/ui/search/LettaSearch.kt` | **The reference implementation.** Read it before writing any component. |
| `scripts/ui-design-lint.py` | Finds violations. No Gradle, no compile, ~1s. |
| `quality/detekt-rules/…/MobileDesignSystemRules.kt` | The same rules for CI, all `active: false` until the backlog clears. |

### Why `LettaSearch` is the pattern to follow

It replaced **four** separate search inputs (command palette, tab-strip picker,
new-conversation field, Home) that differed only in where they were drawn and
which parts were switched on. The shape that made that possible:

- **One implementation.** `LettaSearchScaffold` renders the field, scope row,
  toggle, actions, empty state and results. Nothing else renders any of those.
- **Expressions select parts of it**, they do not re-implement it.
  `LettaSearchParts.All | FieldOnly | PanelOnly` — the header draws the field in
  the chrome and the rest in a panel below by asking the *same* scaffold for
  different parts.
- **Hosts are thin.** `LettaSearchInline`, `LettaSearchDropdownContent`,
  `LettaSearchPopover`, `LettaSearchAnchoredField` are wrappers. A fix to row
  layout lands in all of them at once.
- **A new surface is a config, not a composable.** `LettaSearchConfig` defaults
  to the simplest useful search; a caller names only what it wants.
- **Zero literals.** Every dimension reads `LettaDimens`.

If you are tempted to add a second copy of something "just for this surface" —
that is the failure this whole epic exists to undo. Add a flag.

---

## How to work

**Do not run Gradle to find work.** The linter is a text pass, ~1s.

```bash
python scripts/ui-design-lint.py --summary                  # worst files first
python scripts/ui-design-lint.py --path=DesktopQuickQuery   # one file
python scripts/ui-design-lint.py --rule=low-content-alpha   # one rule
python scripts/ui-design-lint.py --format=json              # machine worklist
```

**Claim a file, not a rule.** Files are the unit of work: two agents on the same
file conflict, two agents on different files never do. Take a file, fix every
finding in it, compile the owning module, move on.

```bash
cd android-compose
./gradlew --no-daemon :desktop:compileKotlin        # or :app:compileRootDebugKotlin, :sharedUI:compileKotlinJvm
```

**Never run two Gradle tasks against this project at once.** Concurrent builds
corrupt incremental state and produce `NoClassDefFoundError` on classes that
compile fine. Several hours were lost to this; it is not a code defect and
re-running alone fixes it.

---

## The mapping

### `raw-dimension`

Import `com.letta.mobile.ui.theme.LettaDimens`. Snap to the **nearest** token;
do not add tokens.

| Was | Use |
|---|---|
| `2.dp` | `LettaDimens.Space.hair` |
| `3–5.dp` | `LettaDimens.Space.xs` |
| `6–9.dp` | `LettaDimens.Space.sm` |
| `10–13.dp` | `LettaDimens.Space.md` |
| `14–18.dp` | `LettaDimens.Space.lg` |
| `20–26.dp` | `LettaDimens.Space.xl` |
| `28–34.dp` | `LettaDimens.Space.xxl` |

`0.dp` and `1.dp` stay — "no inset" and "hairline" are structural, and the
linter ignores them.

**When the value is a control, not a gap, use `Control` / `Orb` / `Radius`.** An
icon button is `Control.iconButton`, not the `Space` token that happens to equal
the same number. The names are the point: whoever changes the icon-button size
next must not also move every 28dp gap.

Icon buttons pick by role:

- `Control.iconButtonSm` (20) — hit target around a glyph *inside* another control (a tab's close cross)
- `Control.iconButton` (28) — a control in its own right, in a bar or composer
- `Control.iconButtonLg` (34) — a primary navigation target (the rail)

**Large layout values** (`360.dp`, `480.dp`, `640.dp` — pane widths, max column
widths, dialog sizes) are not spacing. Leave them, and either hoist them to a
named `private val` in the file or `@Suppress("RawDimensionLiteral")` with a
one-line reason. `LettaSearch.kt` does the former — see its panel geometry
constants at the bottom.

### `low-content-alpha`

The muted roles (`onSurfaceVariant`) are **already** dimmed relative to
`onSurface`. Dimming again puts a control under the contrast it needs to read as
a control on a dark theme. This was a real, visible bug: the composer's disabled
send arrow was invisible.

- Disabled content → drop the `.copy()`, use `onSurfaceVariant` directly
- Deliberately faint dividers → `LettaDimens.Alpha.hairline`
- Genuinely decorative (scrims, gradient stops, glow) → keep, `@Suppress` with a reason

Judgement: *is a person meant to read or click this?* If yes, it may not go
below `LettaDimens.Alpha.disabled`.

### `hardcoded-color`

`Color(0xFF2A2A2A)` cannot follow light/dark. Use a `MaterialTheme` role. If no
role fits, report it rather than inventing one — brand gradients and mascot
palettes are legitimately literal and belong in a token file, not a call site.

---

## Layout traps found the hard way

These are real bugs from this codebase, not hypotheticals.

1. **A boundary is drawn by exactly one side.** Two adjacent hairlines read as
   one thick, slightly wrong border. `DesktopChatSurface` drew its own left pane
   edge a pixel from `RailDivider`'s line. Before adding a border, check what is
   next to it.
2. **A `Spacer` inside a `Column`/`Row` with `Arrangement.spacedBy` is an item** —
   the arrangement applies on *both* sides of it. A `Spacer(4.dp)` under an
   `8.dp` arrangement put Home 20dp clear of the rail list it heads. Prefer
   adjusting the arrangement.
3. **Icons in the title bar need an explicit `tint`.** Jewel's title bar sets a
   dimmed chrome `LocalContentColor`; anything inheriting it is nearly invisible.
4. **Nothing in the title bar can take keyboard focus** on Windows/Linux.
   Nucleus applies `focusProperties { canFocus = false }` to the whole subtree,
   and a descendant cannot re-enable it. A text field there must live in a
   `Popup`, which composes outside that focus scope.
5. **A `LazyColumn` cannot live inside a `DropdownMenu`** — a menu measures by
   intrinsic width and lazy layouts refuse to answer, so the menu collapses.
   `LettaSearchConfig.lazyResults` exists for this.
6. **Desktop renders at 0.8 density** (`Main.kt`). Shared Compose is authored at
   Android touch density. Do not "fix" a size by fighting that scale.

---

## Rules

1. **Do not change layout while substituting.** A token that is not the same
   number *will* shift the surface slightly — that is the point. Do not also
   restructure the composable; one concern per change or nobody can review it.
2. **Never lower a threshold to make the linter pass.** `@Suppress` with a
   reason if a finding is genuinely wrong.
3. **Compile the module you touched.** The linter does not type-check.

## Definition of done, per file

- `python scripts/ui-design-lint.py --path=<file>` reports zero, or the
  remainder are `@Suppress`ed with reasons
- The owning module compiles
- Its tests pass if it has any (`:desktop:test`, `:app:testRootDebugUnitTest`)

## Suggested order

Worst first; they are also the ones a person looks at most.

```
 89  feature-chat  screen/AgentScaffoldPickers.kt
 51  feature-chat  screen/ChatToolCallCards.kt
 48  sharedUI      canvas/CanvasChrome.kt
 46  app           screens/projects/ProjectIssuesScreen.kt
 42  desktop       DesktopAgentRailUi.kt
 40  desktop       DesktopNewConversationSurface.kt
 40  desktop       schedules/DesktopScheduleCreateModal.kt
 39  app           screens/runs/RunMonitorScreen.kt
 39  desktop       DesktopQuickQuery.kt
 39  desktop       schedules/DesktopScheduleRail.kt
```

Re-run `--summary` for the live list.

## When it is finished

In `android-compose/detekt-guardrails.yml`, flip each rule to `active: true`
**as its own count reaches zero** — per rule, not all at once, so
`low-content-alpha` (114) can be enforced long before `raw-dimension` (2,598).

The custom ruleset is currently applied only to `:quality:detekt-rules` itself.
Wiring it into `:app`, `:desktop`, `:sharedUI` and the feature modules is part of
turning it on, and is its own change.

## Out of scope

Building new shared primitives (`LettaIconButton`, `LettaChip`, `LettaMenu`) is
`letta-mobile-x1aa1`. Migrating the remaining search surfaces (command palette,
Home) is `letta-mobile-04rcn`. The KMP UI unification is `letta-mobile-pz6gj`
and is blocked on this epic for a reason: unifying 129k lines onto a scale that
does not exist would relocate the sprawl into `sharedUI`, not remove it.

Substituting tokens is the job. Resist the rest.
