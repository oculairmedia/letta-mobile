# Progressive Composer Affordances: Design & Migration Plan

Status: draft design spec
Date: 2026-06-11
Tracking beads: `letta-mobile-uyysi` (this spec), `letta-mobile-9nlvm` (headless PromptComposerState substrate)

## Purpose

Define a concrete implementation plan for progressive-disclosure composer
affordances — slash commands, mentions, prompt templates, attachment previews,
and an optional model selector — as optional layers atop the headless
`PromptComposerState` (bead 9nlvm). These affordances must not clutter the
default (simple-mode) composer surface.

The reference for metaphor is NadeemIqbal/prompt-bar, but we adopt zero code or
build dependency from it. We borrow the progressive-disclosure pattern only.

## Executive Read

The current composer (`ChatComposer` + `ChatComposerController`) presents a
text field, attachment button, and tool-affordance chips. Adding slash commands,
mentions, and templates will make it richer for power users, but the default
surface must stay clean.

The implementation strategy is:

1. **Headless substrate first** — extend the shared `ChatComposerState` (bead
   9nlvm) with extension slots for autocomplete providers, mention state, and
   template state. The Android `ChatComposerController` then wraps this in an
   `AdminPromptComposerCoordinator` that layers affordances via optional slots.

2. **Progressive disclosure** — each affordance appears only when triggered:
   - `/` opens the slash-command autocomplete panel
   - `@` opens agent/conversation mentions
   - Attachment tap opens the attachment picker (already implemented)
   - Template chips appear only when the field is empty and relevant tools/agents exist
   - Model selector is an optional power-user drawer affordance, not a composer fixture

3. **Layer per affordance** — each affordance is an independent composable that
   reads the headless state. They never communicate directly with each other;
   the headless state is the single source of truth.

## Current State (Baseline)

### Shared headless state: `sharedLogic/.../ChatComposerState`

```kotlin
// ChatRuntimeContracts.kt — already in sharedLogic commonMain
@Immutable
data class ChatComposerState(
    val text: String = "",
    val pendingImageAttachments: List<MessageContentPart.Image> = emptyList(),
    val error: ChatComposerError? = null,
)
```

This is the substrate. It is pure data, platform-agnostic, and already tested
via `ChatSessionReducer` and `ChatComposerPolicy`.

### Android UI controller: `ChatComposerController`

Wraps `ChatComposerState` with Android-specific concerns:
- `inputHistory` (last 30 sent messages)
- `error: String?` (human-readable error string)
- Telemetry events
- `AttachmentLimits` enforcement

### Composer UI: `ChatComposer`

A single `@Composable` function receiving all state as parameters. No
sub-components for autocomplete, mentions, or templates. The `/bug` slash
command is parsed inline in `AdminChatComposerCoordinator`.

### Model surface: drawer model card + `ModelPickerSheet`

The model picker lives in `AgentScaffoldPickers.kt` and is mounted from the
drawer. There is no model selector in the composer. This is the correct
baseline — we do not want to move the primary model surface into the composer.

### Tool affordances: `ToolAffordanceRow`

Tool chips appear above the composer when it's empty and the agent has tools.
Tapping a chip inserts a `buildToolCallTemplate()` string. This is the closest
existing precedent for progressive composer enrichment.

## Affordance-by-Affordance Plan

### 1. Slash Commands (Trigger: `/`)

**Letta use case:** Quick actions without leaving the keyboard — `/bug`, `/clear`,
`/agent`, `/model`, `/conversation`, `/export`, and Letta-specific actions like
`/memory`, `/tools`, `/source`. Commands dispatch via `ChatAction` sealed
interface or trigger `ChatComposerEffect`.

**How it works:**
- When the user types `/` as the first character (or after whitespace), the
  composer enters "slash mode."
- A dropdown or inline chip strip appears above the composer showing candidate
  commands filtered by the typed prefix.
- Tapping a command replaces `/prefix` with the full command text or
  dispatches an effect immediately.
- Closing the dropdown (tap away, Esc, backspace past `/`) returns to simple mode.

**Headless state extension:**

```kotlin
data class ChatComposerState(
    // ... existing fields ...
    val slashMode: SlashMode = SlashMode.Inactive,
)

sealed interface SlashMode {
    data object Inactive : SlashMode
    data class Active(
        val prefix: String,    // the text after "/"
        val cursorPosition: Int, // where in text the "/" was typed
    ) : SlashMode
}

// Extension properties computed from state:
val ChatComposerState.slashQuery: String
    get() = (slashMode as? SlashMode.Active)?.prefix.orEmpty()

val ChatComposerState.isSlashMode: Boolean
    get() = slashMode !is SlashMode.Inactive
```

**Autocomplete candidates** are NOT stored in the headless state — they are
computed by the `SlashCommandProvider` interface decorated onto the
coordinator. The UI reads candidates from this provider reactively.

```kotlin
// In sharedLogic — the interface contract
interface SlashCommandProvider {
    fun candidates(query: String, context: ChatSessionState): List<SlashCommandSuggestion>
}

data class SlashCommandSuggestion(
    val token: String,          // e.g., "/bug"
    val label: String,          // e.g., "File bug report"
    val category: String,       // "chat", "agent", "system"
    val dispatch: SlashDispatch, // inline replace or side-effect
)

sealed interface SlashDispatch {
    data class Replace(val replacement: String) : SlashDispatch  // substitute text
    data class Effect(val effect: ChatComposerEffect) : SlashDispatch  // trigger action
}
```

**Risk:** The current `ChatSlashCommandParser` is hardcoded to `/bug` only.
The extension from enum to provider interface must not regress the
`projectContextAvailable` gate.

**Risk:** Slash mode must correctly handle the case where the user types
`/something` but then backspaces past the `/`. The mode must deactivate
smoothly without flashing the dropdown.

**Migration step:**
1. Add `SlashMode` to `ChatComposerState` (sharedLogic).
2. Add `enterSlashMode` / `exitSlashMode` transitions to `ChatComposerPolicy`.
3. Define `SlashCommandProvider` interface in sharedLogic.
4. Implement Android provider wired to `AdminChatComposerCoordinator`.
5. Add `SlashAutocompleteDropdown` composable to `ChatComposer`.

### 2. Mentions (Trigger: `@`)

**Letta use case:** Reference other agents or conversations inline. Could seed
the message with a context pointer — e.g., `@CodeReviewer` to tag another agent
for cross-agent dispatch, or `@conv/abc123` to reference a conversation.

In Letta's agent-council and teams model, mentions are a natural way to
compose cross-agent messages without leaving the chat surface.

**How it works:**
- Typing `@` opens a mention autocomplete panel.
- Candidates: agents the user has access to, active conversations, or
  registered team members.
- Selecting a candidate inserts an inline token (e.g., `@CodeReviewer`) that
  the send pipeline resolves into the appropriate `MessageContentPart` or
  metadata field.

**Headless state extension:**

```kotlin
data class ChatComposerState(
    // ... existing fields ...
    val mentionMode: MentionMode = MentionMode.Inactive,
)

sealed interface MentionMode {
    data object Inactive : MentionMode
    data class Active(
        val prefix: String,
        val cursorPosition: Int,
    ) : MentionMode
}

interface MentionProvider {
    fun candidates(query: String, context: ChatSessionState): List<MentionSuggestion>
}

data class MentionSuggestion(
    val token: String,        // e.g., "@CodeReviewer"
    val label: String,
    val kind: MentionKind,
    val metadata: Map<String, String> = emptyMap(), // agent-id, conversation-id, etc.
)

enum class MentionKind { Agent, Conversation, Team, Command }
```

**Risk:** Mentions are not a native Letta API concept yet. The
serialized mention token in the message text needs a send-pipeline stage to
resolve to the backend's expected format. This is a backend-contract risk.

**Risk:** Mention autocomplete must be fast. If agent/conversation lists are
paginated, the provider should pre-fetch or use a local cache.

**Migration step:**
1. Add `MentionMode` to `ChatComposerState` (sharedLogic).
2. Define `MentionProvider` interface.
3. Implement Android provider wired to agent/conversation repositories.
4. Add `MentionAutocompleteDropdown` composable.
5. Define mention token format (inline text, structured metadata) and add
   `mentionMetadata` to `ComposerSendPayload`.

### 3. Prompt Templates / Tool Chips

**Letta use case:** Quick-start prompts for common Letta operations — memory
inspection, source search, agent steering, multi-agent dispatch. The existing
`ToolAffordanceRow` is already a weak template pattern: tool chips insert
structured text.

**How it works:**
- When the composer is empty and the keyboard is open, a horizontal chip strip
  appears above the input showing suggested templates.
- Chips are categorized: frequent actions, recent agent tools, saved templates.
- Tapping a chip inserts the template text and optionally positions the cursor
  at the first placeholder.

**Already partially implemented:** `ToolAffordanceRow` + `buildToolCallTemplate()`.
This affordance is primarily about expanding the chip set beyond tool calls
to include prompt templates and saved phrases.

**Headless state extension (minimal):**

```kotlin
// Templates don't need new headless state — they insert text like any
// other input. What's needed is the template registry provider:

interface PromptTemplateProvider {
    fun templates(context: ChatSessionState): List<PromptTemplate>
}

data class PromptTemplate(
    val id: String,
    val label: String,
    val body: String,             // the text to insert
    val cursorPlacement: Int,     // where to place cursor after insert (negative = end)
    val category: PromptTemplateCategory,
)

enum class PromptTemplateCategory {
    ToolCall, SavedPrompt, FrequentAction, SystemAction
}
```

**Risk:** Too many chips → visual overload. Keep the chip strip to 3-5
intelligent suggestions that rotate based on context. Use the existing
`ToolAffordanceRow` layout pattern.

**Risk:** Template text must not contain formatting that breaks the send
pipeline. Validate template bodies against the same `ChatComposerPolicy`
constraints.

**Migration step:**
1. Define `PromptTemplateProvider` interface.
2. Expand `ToolAffordanceRow` to accept a combined `List<AnySuggestion>`.
3. Seed initial templates for the top 5 Letta actions.

### 4. Attachments (Already Implemented)

Current state supports image attachments via the `+` button and
`onAttachImage` callback. This is adequate for the current scope.

**Future extension (out of scope for this spec):** Document/file attachments
beyond images, clipboard paste handling, drag-and-drop on desktop.

### 5. Model Selector in Composer (Explicitly Optional)

**Current state:** The model picker lives in the drawer (`ModelPickerSheet` in
`AgentScaffoldPickers.kt`). This is the correct primary surface.

**Proposed:** A small model badge in the composer (e.g., a chip showing the
current model name) that opens the existing `ModelPickerSheet`. This badge:

- Is hidden by default in simple mode.
- Can be enabled via settings toggle (`showModelBadgeInComposer`).
- When visible, shows the current model name as a compact badge.
- Tapping it opens the drawer model picker (no duplicate model surface).

No new headless state is required for this — it is purely a UI visibility
toggle.

**Risk:** Putting the model surface in the composer risks the same confusion
as ChatGPT's model dropdown — users might think they need to set it per-message
when the model is an agent-level concern. Mitigation: the badge is hidden by
default and explicitly labeled as a shortcut to the drawer, not a per-message
model override.

**Migration step:**
1. Add `showModelBadgeInComposer` to `ChatComposerPolicy` (a UX policy flag, not
   state).
2. Wire the badge to open the existing `ModelPickerSheet`.

## Simple Mode Guarantee

The simple-mode composer must never show autocomplete panels, mention
dropdowns, template strips, or model badges unless:

1. The user triggers them explicitly (`/`, `@`, attachment tap), or
2. The setting is explicitly enabled (model badge, template strip).

The default composer surface is: text field + attachment button + send button
+ voice dictation (when empty). This is the current behavior and must not
regress.

Implementation guard: each affordance's `@Composable` checks a visibility gate
derived from the headless state `isSlashMode`, `isMentionMode`, etc. If the
gate is closed, the affordance does not compose — no invisible layout, no
accessibility noise, no recomposition cost.

## Migration Sequence

Each phase is independently mergeable and testable. Phases do not block each
other.

```
Phase 1 (9nlvm): Headless PromptComposerState
  Extend sharedLogic ChatComposerState with SlashMode + MentionMode slots.
  Add ChatComposerPolicy transitions for enter/exit modes.
  Add unit tests for state transitions. (Bead 9nlvm scope.)

Phase 2 (uyysi): Slash command autocomplete
  Define SlashCommandProvider interface.
  Implement Android provider + SlashAutocompleteDropdown.
  Migrate existing /bug from hardcoded parser to provider.

Phase 3 (uyysi): Mentions
  Define MentionProvider interface.
  Implement Android provider + MentionAutocompleteDropdown.
  Define mention token format and send-pipeline extension point.

Phase 4 (uyysi): Prompt templates
  Define PromptTemplateProvider interface.
  Expand ToolAffordanceRow to template chips.
  Seed initial Letta templates.

Phase 5 (uyysi): Optional model badge
  Add visibility toggle.
  Wire badge to existing ModelPickerSheet.

Phase 6 (follow-up): Desktop parity
  Port Android affordance composables to Compose Desktop over sharedLogic.
```

## File Map

```
sharedLogic/commonMain/.../data/chat/runtime/
  ChatRuntimeContracts.kt     ← extend ChatComposerState with SlashMode, MentionMode
  ChatComposerPolicy.kt       ← add enter/exit transitions
  SlashCommandProvider.kt     ← interface (new)
  MentionProvider.kt          ← interface (new)
  PromptTemplateProvider.kt   ← interface (new)

feature-chat/.../coordination/
  ChatComposerController.kt   ← wire mode transitions
  AdminChatComposerCoordinator.kt  ← wire providers
  SlashCommandAutoComplete.kt ← Android slash provider impl (new)
  MentionAutoComplete.kt      ← Android mention provider impl (new)

feature-chat/.../screen/
  ChatComposer.kt             ← add dropdown composables
  SlashDropdown.kt            ← (new, extracted from ChatComposer)
  MentionDropdown.kt          ← (new)
  ComposerModelBadge.kt       ← (new, optional)
```

## Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Mention token format needs backend contract | Medium | Define inline text as interim; add structured metadata slot to `ComposerSendPayload` without requiring backend changes |
| Simple-mode composer gets visually heavy | High | Each affordance has an explicit `visible` gate; default = hidden; preview screenshots in CI |
| SharedLogic grows too UI-specific | Medium | Keep provider interfaces in sharedLogic; implementations stay in platform modules. Headless state stays pure data |
| Keyboard/IME interactions with dropdowns | Medium | Use existing `WindowInsets.ime` awareness; the dropdown pushes the composer up rather than overlaying the IME |
| Desktop port diverges from Android | Low | All headless state and provider contracts are in sharedLogic; only `@Composable` functions differ |
| Model badge confuses per-message vs per-agent model | Medium | Badge hidden by default; opens drawer picker (not in-place); badge label reads "Model: X" not "Send with X" |

## Acceptance Criteria

- [ ] Headless `ChatComposerState` has `SlashMode` and `MentionMode` slots with
      policy-tested enter/exit transitions (9nlvm scope).
- [ ] Typing `/` opens slash autocomplete; typing `@` opens mention autocomplete.
- [ ] Autocomplete panels are keyboard-navigable (arrow keys + Enter/Tab).
- [ ] Default composer is visually identical to current simple mode.
- [ ] Existing `/bug` slash command works via the provider, not the hardcoded parser.
- [ ] Mention tokens pass through the send pipeline without errors (inline text
      format is acceptable as MVP).
- [ ] `ChatComposerControllerTest` has tests for mode transitions and
      autocomplete state.
- [ ] CI gate: screenshot-diff captures show no regression to simple-mode layout.
- [ ] No build dependency on prompt-bar or any third-party composer library.

## References

- Bead `letta-mobile-9nlvm` — headless PromptComposerState
- Bead `letta-mobile-uyysi` — this spec
- `ChatComposerController.kt` — current Android composer controller
- `ChatComposer.kt` — current composer UI
- `Chat