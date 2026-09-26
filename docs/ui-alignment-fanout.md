# Desktop/Android UI alignment — fanout brief

Work brief for parallel agents. Epic: `letta-mobile-hzddx`.

## The problem, in numbers

```bash
python scripts/ui-design-lint.py --summary
```

**2,833 findings across 234 files** at the time of writing:

| Rule | Count | What it means |
|---|---|---|
| `raw-dimension` | 2,632 | `12.dp` / `14.sp` written at a call site instead of read from a token |
| `low-content-alpha` | 115 | `.copy(alpha = <0.6)` on a content colour — the unreadable-control bug |
| `hardcoded-color` | 86 | `Color(0xFF…)` bypassing the theme |

80 distinct dp values are in use — 1 through 20 near-contiguously, plus
22/24/26/28/30/31/32/34/36/38/40/44/46/48. There is no scale. Two controls on
the same row have no reason to agree, and a fix on one screen never reaches the
next. That is the whole problem; everything below follows from it.

## The assets you reference

| Asset | Purpose |
|---|---|
| `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/theme/LettaDimens.kt` | **The scale.** Every dimension comes from here. |
| `scripts/ui-design-lint.py` | Finds violations. No Gradle, no compile, ~1s. |
| `config/ui-design-lint-baseline.json` | The count. It only goes down. |
| `android-compose/quality/detekt-rules/…/MobileDesignSystemRules.kt` | The same rules for CI. |

`LettaDimens` was derived from measured usage, not invented. The 4dp grid
already dominated real code (`8.dp` 490 uses, `16.dp` 335, `12.dp` 302,
`4.dp` 264); the off-grid values are the drift (`6.dp` 183, `2.dp` 135,
`10.dp` 126, `14.dp` 105, `18.dp` 60, `28.dp` 42, and a long tail).

## How to work

**Do not run Gradle to find work.** The linter is a text pass and returns in
about a second. Use it to scope, edit, re-run, and only compile once per batch.

```bash
python scripts/ui-design-lint.py --summary                     # worst files first
python scripts/ui-design-lint.py --path=DesktopAgentRailUi     # one file
python scripts/ui-design-lint.py --rule=low-content-alpha      # one rule
python scripts/ui-design-lint.py --format=json                 # machine worklist
```

**Claim a file, not a rule.** Files are the unit of work — two agents editing
the same file conflict; two agents on different files never do. Take a file off
the summary, fix every finding in it, compile the owning module, move on.

```bash
cd android-compose
./gradlew --no-daemon :desktop:compileKotlin      # or :app:compileRootDebugKotlin, :sharedUI:compileKotlinJvm
```

## The mapping

### `raw-dimension`

Import `com.letta.mobile.ui.theme.LettaDimens` and substitute. Snap off-grid
values to the **nearest** token; do not add tokens.

| Was | Use |
|---|---|
| `2.dp` | `LettaDimens.Space.hair` |
| `3.dp` `4.dp` `5.dp` | `LettaDimens.Space.xs` |
| `6.dp` `7.dp` `8.dp` `9.dp` | `LettaDimens.Space.sm` |
| `10.dp` `11.dp` `12.dp` `13.dp` | `LettaDimens.Space.md` |
| `14.dp` `15.dp` `16.dp` `17.dp` `18.dp` | `LettaDimens.Space.lg` |
| `20.dp` `22.dp` `24.dp` `26.dp` | `LettaDimens.Space.xl` |
| `28.dp` `30.dp` `32.dp` `34.dp` | `LettaDimens.Space.xxl` |

`0.dp` and `1.dp` stay as they are — "no inset" and "hairline" are structural,
not points on a scale, and the linter ignores them.

**When the value is a control, not a gap, use `Control` / `Orb` / `Radius`
instead of the spacing scale.** An icon button is `Control.iconButton`, not
`Space.xxl` that happens to equal the same number. The names are the point: the
next person changing the icon-button size must not also move every 28dp gap.

Icon buttons pick by role, not by taste:

- `Control.iconButtonSm` (20) — a hit target around a glyph **inside** another control (a tab's close cross).
- `Control.iconButton` (28) — a control in its own right, in a bar or composer.
- `Control.iconButtonLg` (34) — a primary navigation target (the rail).

Large layout values (`360.dp`, `480.dp`, `640.dp` — pane widths, max column
widths, dialog sizes) are **not** spacing. Leave them, and add a
`@Suppress("RawDimensionLiteral")` with a one-line reason, or hoist them to a
named `private val` in the file. They are a separate question from the scale.

### `low-content-alpha`

`.copy(alpha = 0.5f)` and below on a content colour. The muted roles
(`onSurfaceVariant`) are **already** dimmed relative to `onSurface`; dimming
again puts a control under the contrast it needs to still read as a control on
a dark theme.

- Disabled content → drop the `.copy()`, use `onSurfaceVariant` directly.
- Deliberately faint dividers/hairlines → `LettaDimens.Alpha.hairline`.
- Genuinely decorative (scrims, gradient stops, glow) → keep, and
  `@Suppress("LowContentAlpha")` with a reason.

Judgement required: *is a person meant to read or click this?* If yes, it is not
allowed below `LettaDimens.Alpha.disabled`.

### `hardcoded-color`

`Color(0xFF2A2A2A)` cannot follow light/dark. Replace with a `MaterialTheme`
colour role. If no role fits, that is a finding worth reporting rather than
inventing a colour — brand gradients and mascot palettes are legitimately
literal and belong in a token file, not a call site.

## Rules for the pass

1. **Do not change layout while substituting.** If a token is not the same
   number as the literal, the surface *will* shift slightly — that is expected
   and is the point. But do not also restructure the composable. One concern per
   change, or nobody can review it.
2. **A boundary is drawn by exactly one side.** Two adjacent hairlines read as
   one thick, slightly wrong border. This was a real bug: the chat pane drew its
   own left edge a pixel from the rail divider's line. If you add a border,
   check what is next to it.
3. **A `Spacer` inside a `Column`/`Row` with `Arrangement.spacedBy` is an item.**
   The arrangement applies on *both* sides of it. This is why Home sat 20dp clear
   of the rail list it heads, from a `Spacer(4.dp)` under an `8.dp` arrangement.
   Prefer adjusting the arrangement over inserting spacers.
4. **Never lower a threshold to make the linter pass.** The baseline only goes
   down. If a finding is genuinely wrong, `@Suppress` it with a reason.
5. **Compile the module you touched** before claiming a file is done. The linter
   does not type-check.

## Definition of done, per file

- `python scripts/ui-design-lint.py --path=<file>` reports zero findings, or the
  remainder are `@Suppress`ed with reasons.
- The owning module compiles.
- Its tests pass if it has UI tests (`:desktop:test`, `:app:testRootDebugUnitTest`).
- `config/ui-design-lint-baseline.json` is regenerated only by the last agent to
  finish, not per file — otherwise the baseline races.

## Suggested order

Worst files first; they are also the ones a person looks at most.

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
 38  feature-chat  screen/AgentScaffoldDrawer.kt
 36  desktop       chat/DesktopChatSurface.kt
```

Re-run `--summary` for the current list; these counts move as work lands.

## When it is finished

Turn the rules on so the drift cannot come back. In
`android-compose/detekt-guardrails.yml`, flip each to `active: true` **as its
count reaches zero** — per rule, not all at once, so `low-content-alpha` (115)
can be enforced long before `raw-dimension` (2,632) is done.

Note the custom ruleset is currently applied only to `:quality:detekt-rules`
itself. Wiring it into `:app`, `:desktop`, `:sharedUI` and the feature modules
is part of turning it on, and is its own change.

## What this is NOT

This pass does **not** build shared primitives (`LettaIconButton`,
`LettaSearchField`, `LettaChip`, `LettaMenu`) — that is `letta-mobile-x1aa1`,
and it lands on top of the tokens. It does not build the unified search
(`letta-mobile-goa3r`), and it does not start the KMP unification
(`letta-mobile-pz6gj`), which is blocked on this epic for a reason: unifying
129k lines onto a scale that does not exist would relocate the sprawl into
`sharedUI` rather than remove it.

Substituting tokens is the whole job. Resist the rest.
