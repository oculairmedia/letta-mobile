# PRD: Tool Card Readability + Interaction Polish

## Status
Draft / implemented in PR #341 (`fix/toolcard-dedup`) with follow-up polish hooks.

## Problem
Expanded tool cards became visually noisy and hard to scan. A single tool call could render the same information multiple times:

- Header summary: `Bash - Result: ...`
- `Args:` summary line
- Raw JSON `Arguments` block containing the same command/file path again
- `Result:` preview line repeating the output
- `Tool: <name>` line repeating the header
- `Output` section containing the same result again

For Bash/Agent cards, this produced a wall of duplicated text and made tool output feel heavier than the actual assistant message.

## Goals
- Make tool cards recede by default and scan quickly.
- Show each piece of information once.
- Preserve useful summaries: tool name, concise args summary, output preview/result.
- Improve touch targets so users can expand/collapse output by tapping the output body, not only the small chevron/header.
- Avoid regressions to streaming/render performance (`rmzmo`).

## Non-goals
- Redesign all tool cards from scratch.
- Remove access to important tool output.
- Change raw tool data in the transport/model layer.
- Solve subagent dispatch/result chrome (`pbnxa`) beyond general tool-card cleanup.

## User Experience

### Collapsed / default card
A compact card should show:

1. Tool icon + tool name + concise status/result state.
2. One concise argument summary, when useful:
   - Bash: command summary
   - Read/Edit/Write: file path
   - Search tools: query/pattern
3. A compact output affordance when output exists.

It should not show raw JSON arguments by default.

### Expanded card
Expanded cards should show:

1. Header summary.
2. Concise `Args:` summary (one line / small block).
3. Optional timing/detail metadata.
4. `Output` section.

Expanded cards should not show:

- Raw JSON `Arguments` block by default.
- Separate `Result:` preview line if the `Output` section is already present.
- `Tool: <name>` if the header already names the tool.

### Output interaction
The whole output section should toggle expand/collapse:

- Tapping the `Output` header toggles.
- Tapping the output preview/body toggles.
- Chevron remains as a visual hint, not the only practical target.

## Acceptance Criteria
- A Bash card no longer displays the command three times.
- An Agent card no longer displays raw JSON args + result preview + output redundantly.
- Raw JSON arguments are absent from the normal expanded view.
- Output body tap toggles expand/collapse.
- Existing output rendering still works for long outputs, errors, and multi-line output.
- Build passes: `:app:assembleSideloadDebug`.

## Implementation Notes
Implemented in `ChatToolCallCards.kt`:

- Removed duplicate raw-JSON `Arguments` render blocks.
- Removed duplicate `Result:` preview line from `ToolCallExpandedSummary`.
- Removed duplicate `Tool: <name>` lines from expanded bodies.
- Added clickable modifier to `ToolOutputRenderer` area so tapping output content toggles expansion.

## Follow-ups
- Add behavior-focused Compose tests for output body tap target.
- Consider a dedicated `Show raw args` debug affordance for developer builds only.
- Coordinate with `pbnxa` subagent dispatch/result chrome so Agent tool calls render as first-class dispatch cards instead of generic tool cards.
- Review compact multi-tool group cards for the same duplication rules.
