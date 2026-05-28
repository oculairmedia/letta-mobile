# A2UI Catalog Authoring Guide

This package contains the Android Compose renderer for A2UI surfaces. Use this
guide when adding a Basic widget, adding a Letta domain catalog, or writing
agent-authored payloads that should render in the mobile chat surface.

Authoritative implementation files:

- Renderer: `android-compose/designsystem/src/main/java/com/letta/mobile/ui/a2ui/A2uiRenderer.kt`
- Protocol models and catalog negotiation: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/a2ui/A2uiProtocol.kt`
- Surface state and binding resolver: `android-compose/core/src/main/java/com/letta/mobile/data/a2ui/A2uiSurfaceManager.kt`
- JSON Pointer data model: `android-compose/core/src/main/java/com/letta/mobile/data/a2ui/A2uiDataModel.kt`
- Action context resolution: `android-compose/core/src/main/java/com/letta/mobile/data/a2ui/A2uiActions.kt`
- Renderer tests and examples: `android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/A2uiRendererTest.kt`
- WS round-trip tests: `android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/A2uiToolApprovalRoundTripTest.kt`
- Screenshot tests: `android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/A2uiRendererScreenshotTest.kt`

## Surface Shape

A rendered surface is assembled from the A2UI v0.9 server-to-client messages:

1. `createSurface` creates or updates the surface record and declares `catalogId`.
2. `updateComponents` merges component definitions into the surface registry and
   sets `root`.
3. `updateDataModel` patches the local JSON data model at a JSON Pointer path.
4. `deleteSurface` removes the surface.

Components are stored as `A2uiComponent(id, component, raw)`. The renderer keeps
the raw JSON so new props and future catalog fields can land without widening the
protocol model for every field.

Minimal surface:

```json
[
  {
    "version": "v0.9",
    "createSurface": {
      "surfaceId": "example-1",
      "catalogId": "basic"
    }
  },
  {
    "version": "v0.9",
    "updateComponents": {
      "surfaceId": "example-1",
      "root": "card",
      "components": [
        { "id": "card", "component": "Card", "child": "body" },
        { "id": "body", "component": "Column", "children": ["title", "cta"] },
        { "id": "title", "component": "Text", "text": "Ready" },
        {
          "id": "cta",
          "component": "Button",
          "label": "Continue",
          "action": { "name": "example.continue" }
        }
      ]
    }
  }
]
```

## Renderer Dispatch

`A2uiComponentNodeContent` dispatches on the string value of
`component.component`. The component string is the catalog contract. If a new
widget is negotiated in `A2UI_DEFAULT_SUPPORTED_WIDGETS`, it must also have a
matching branch in the renderer.

Dispatch rules:

- Keep `A2uiRenderer` and `A2uiSurfaceRenderer` public entrypoints stable.
- Unknown component strings render `A2uiTestTags.MissingComponent`, not a crash.
- Recursive containers pass `visited + component.id` to prevent cycles.
- Nested render calls must pass through `A2uiComponentNode` so local state,
  render scope, action plumbing, and missing-component behavior stay consistent.
- Add or reuse an `A2uiTestTags` constant for every visible widget state that
  tests need to find.

When adding a widget, wire it in this order:

1. Add the widget name to `A2UI_DEFAULT_SUPPORTED_WIDGETS` if Android should
   advertise support in the WS hello.
2. Add a dispatch branch in `A2uiComponentNodeContent`.
3. Implement a focused private composable that accepts `A2uiComponent`,
   `A2uiSurfaceState`, `Modifier`, and any required renderer plumbing.
4. Add prop extraction helpers near the existing helper section rather than
   spreading raw JSON parsing through layout code.
5. Add renderer tests that cover valid props, missing required props, binding,
   and user interaction.

## Basic Widgets

Use the Basic catalog when the UI is a generic Material control or layout
primitive. Current Basic widget support is declared by
`A2UI_DEFAULT_SUPPORTED_WIDGETS` and includes:

- Layout and display: `Text`, `Column`, `Row`, `Card`, `Divider`, `Image`,
  `Modal`, `Video`, `AudioPlayer`
- Inputs: `TextField`, `DateTimeInput`, `Checkbox`, `Switch`, `Radio`,
  `Slider`, `Stepper`, `Dropdown`, `Select`, `ChoicePicker`
- Status and selection: `LinearProgress`, `CircularProgress`, `Chip`,
  `FilterChip`, `Badge`, `Tabs`, `Accordion`
- Template primitive: `ListView` and its `List` alias

`CheckBox` is accepted as a compatibility alias for `Checkbox`. Prefer
`Checkbox` in new payloads.

Basic-widget implementation checklist:

- Extract display text with `resolveBindingText(...)` so literals and
  `{ "path": "..." }` bindings behave the same way.
- For editable values, prefer an explicit binding such as
  `{ "value": { "path": "/draft/name" } }`.
- Treat unbound input state as local UI state only unless the renderer already
  routes that widget through the reserved `/_inputs/<componentId>` safety-net
  namespace. Agent-authored catalogs should not rely on unbound values for
  important actions.
- Respect `surfaceSubmitting` on controls that can dispatch or mutate values.
  Disable click/input affordances while an action is in flight.
- Use Material 3 semantics: segmented buttons for exclusive short choices,
  chips for filters/tags, switches for direct on/off settings, and dialogs or
  sheets for blocking/contextual decisions.
- For `ChoicePicker`, choose the display shape deliberately. Short exclusive
  choices render as segmented buttons by default; `display: "chips"` renders
  filter chips; `display: "list"` or larger option sets render list rows.
- Prefer existing typography, colors, haptics, and test-tag patterns from this
  renderer. Do not introduce one-off raw colors.

## Binding Patterns

Bindings are JSON values read by `A2uiBindingResolver`:

```json
{ "path": "/title" }
{ "literalString": "Approve" }
{ "literal": 4 }
{ "value": true }
```

Rules for authored payloads:

- Absolute paths start with `/` and resolve from the surface data-model root.
- In a `ListView` item template, relative paths resolve from the current item.
  For example, `{ "path": "title" }` inside item index `0` under `/issues`
  resolves as `/issues/0/title`.
- Use explicit paths for every value the agent must receive later.
- `updateDataModel` with an omitted `value` deletes the target path.
- Patches deep-merge objects and replace scalar/array values.
- Keep binding objects small. If a widget needs derived values, prefer sending
  the derived value in the data model until a computed-binding contract exists.

Example form:

```json
[
  {
    "version": "v0.9",
    "createSurface": {
      "surfaceId": "booking-1",
      "catalogId": "basic",
      "sendDataModel": true
    }
  },
  {
    "version": "v0.9",
    "updateComponents": {
      "surfaceId": "booking-1",
      "root": "form",
      "components": [
        { "id": "form", "component": "Column", "children": ["party", "submit"] },
        {
          "id": "party",
          "component": "TextField",
          "label": "Party size",
          "value": { "path": "/draft/partySize" },
          "textFieldType": "number"
        },
        {
          "id": "submit",
          "component": "Button",
          "label": "Book",
          "action": {
            "name": "booking.submit",
            "context": [
              { "key": "partySize", "value": { "path": "/draft/partySize" } }
            ]
          }
        }
      ]
    }
  }
]
```

## Actions

Buttons and domain widgets emit `A2uiAction`. For Basic `Button`, the renderer
accepts both the current nested event shape and the older flat shape:

```json
{
  "id": "submit",
  "component": "Button",
  "label": "Submit",
  "action": {
    "event": {
      "name": "form.submit",
      "actionId": "submit-1",
      "context": [
        { "key": "title", "value": { "path": "/title" } }
      ]
    }
  }
}
```

Action rules:

- Prefer stable, domain-qualified names such as `issue.open` or
  `schedule.update`.
- Include `actionId` when the server needs idempotency or ack correlation.
- Use `context` as an object or as `{ key, value }` pairs. Binding values are
  resolved before the action leaves Android.
- `createSurface.sendDataModel: true` attaches the current surface data model
  under `context.data_model`. This is useful for form submit buttons, but fixed
  context fields are easier for agents and tests to reason about.
- Keep input widgets side-effect-free. They update the local data model; the
  explicit user action is usually a Button tap or a domain widget control.

## ListView Templating

`ListView` renders one component template per item in a data-model array. The
renderer also accepts `List` as a compatibility alias, but new payloads should
prefer `ListView` so the template contract stays explicit.

Required fields:

- `itemTemplate`: component id to render for each item.
- `items`: either `{ "path": "/items" }` or the shorthand string `"/items"`.

Optional fields:

- `itemKey` or `itemKeyPath`: stable identity path inside each item. Defaults to
  `id`.
- `spacing`: same spacing token used by container widgets.
- `templateComponentId` or `itemTemplateId`: accepted aliases for
  `itemTemplate`.

Example:

```json
[
  {
    "version": "v0.9",
    "updateComponents": {
      "surfaceId": "issues-1",
      "root": "issuesList",
      "components": [
        {
          "id": "issuesList",
          "component": "ListView",
          "itemTemplate": "issueCard",
          "items": { "path": "/issues" },
          "itemKey": "id",
          "spacing": "sm"
        },
        { "id": "issueCard", "component": "Card", "child": "issueContent" },
        {
          "id": "issueContent",
          "component": "Column",
          "children": ["issueTitle", "issueSubtitle", "openIssue"],
          "spacing": "xs"
        },
        { "id": "issueTitle", "component": "Text", "text": { "path": "title" } },
        { "id": "issueSubtitle", "component": "Text", "text": { "path": "subtitle" } },
        {
          "id": "openIssue",
          "component": "Button",
          "label": { "path": "actionLabel" },
          "action": {
            "name": "issue.open",
            "context": [
              { "key": "issueId", "value": { "path": "id" } },
              { "key": "title", "value": { "path": "title" } }
            ]
          }
        }
      ]
    }
  },
  {
    "version": "v0.9",
    "updateDataModel": {
      "surfaceId": "issues-1",
      "path": "/issues",
      "value": [
        {
          "id": "issue-1",
          "title": "Alpha issue",
          "subtitle": "Needs review",
          "actionLabel": "Open Alpha"
        }
      ]
    }
  }
]
```

## Local UI State

Local state is for ephemeral presentation state that should not go back to the
agent: expanded rows, selected tab index, revealed sensitive values, and similar
view-only toggles.

Catalog authors can seed state per component:

```json
{
  "id": "faqAccordion",
  "component": "Accordion",
  "localState": { "expanded_summary": true },
  "items": [
    { "key": "summary", "title": "Summary", "child": "summaryBody" }
  ]
}
```

Renderer rules:

- State slots are keyed by `(surfaceId, componentId, key)`.
- State survives recomposition and data-model updates.
- State is forgotten when the surface leaves composition after deletion.
- Use `rememberA2uiLocalBooleanState` or `rememberA2uiLocalIntState` instead of
  ad hoc `remember` when the widget state is part of the catalog convention.
- Do not use local state for values the agent needs in a later action.

## Domain Catalogs

Use a domain catalog when Basic widgets cannot express the semantics safely or
compactly. Current domain catalogs:

- `com.letta.mobile:tool-approval/v1` with `ToolApprovalCard`
- `com.letta.mobile:schedule/v1` with `ScheduleCard` and
  `ScheduleSelectorInput`

Three rules for choosing Basic vs a domain catalog:

1. Use Basic when generic layout, display, and Material input semantics are
   enough.
2. Use a domain catalog when the action contract is app-owned, security
   sensitive, or needs fixed server-side semantics. Tool approval is the model:
   it emits `tool_approval_response` with stable `decision`, `scope`, and
   request/call ids rather than asking an agent to infer approval semantics from
   a generic Button.
3. Keep domain catalogs narrow and versioned. A domain widget should hide a
   stable business contract behind a compact UI, not become a tunnel for
   arbitrary app screens.

Domain-catalog checklist:

- Add a versioned catalog id constant in `A2uiProtocol.kt`.
- Add widget id constants and include them in `A2UI_DEFAULT_SUPPORTED_WIDGETS`.
- Add a renderer branch for each widget.
- Extract props into a small data class; return a skeleton/missing state when
  required fields are absent.
- Emit a fixed-shape action or fixed data-model update. Tests should assert the
  exact wire shape.
- Add at least one renderer test and one protocol/round-trip test if the widget
  sends actions.
- Document the catalog-specific payload shape in this README or a linked doc.

## Schedule/v1 Example

`com.letta.mobile:schedule/v1` is the reference domain catalog because it uses
both a display widget and an input widget.

Display a list of schedules with a Basic `ListView` and a domain
`ScheduleCard` template:

```json
[
  {
    "version": "v0.9",
    "createSurface": {
      "surfaceId": "schedules-1",
      "catalogId": "com.letta.mobile:schedule/v1"
    }
  },
  {
    "version": "v0.9",
    "updateComponents": {
      "surfaceId": "schedules-1",
      "root": "scheduleList",
      "components": [
        {
          "id": "scheduleList",
          "component": "ListView",
          "itemTemplate": "scheduleTemplate",
          "items": { "path": "/schedules" },
          "itemKey": "id",
          "spacing": "sm"
        },
        {
          "id": "scheduleTemplate",
          "component": "ScheduleCard",
          "scheduleId": { "path": "id" },
          "name": { "path": "name" },
          "agentName": { "path": "agentName" },
          "status": { "path": "status" },
          "summary": { "path": "summary" },
          "cronExpression": { "path": "cron" },
          "nextScheduledTime": { "path": "nextRun" },
          "lastRun": { "path": "lastRun" }
        }
      ]
    }
  },
  {
    "version": "v0.9",
    "updateDataModel": {
      "surfaceId": "schedules-1",
      "path": "/schedules",
      "value": [
        {
          "id": "sched-1",
          "name": "Morning check-in",
          "agentName": "Ada",
          "status": "active",
          "summary": "Ask for a standup update",
          "cron": "0 8 * * *",
          "nextRun": "2026-05-20T08:00:00Z"
        }
      ]
    }
  }
]
```

Add a schedule selector for draft values:

```json
{
  "id": "selector",
  "component": "ScheduleSelectorInput",
  "label": { "literalString": "Schedule cadence" },
  "value": { "path": "/draftSchedule" },
  "agentId": { "literalString": "agent-1" },
  "message": { "literalString": "Send me a digest" }
}
```

The selector stores an object at the bound path:

```json
{ "mode": "cron", "value": "0 8 * * *" }
```

Supported schedule modes are `cron`, `every`, and `at`.

## Tests And Review

Every widget or catalog change should update the smallest useful test set:

- Protocol or parsing changes: `A2uiProtocolTest`
- Data model or JSON Pointer changes: `A2uiDataModelTest`
- Surface lifecycle and message folding: `A2uiSurfaceManagerTest`
- Renderer behavior and interactions: `A2uiRendererTest`
- Visual regression: `A2uiRendererScreenshotTest`
- WS action round trips: `A2uiToolApprovalRoundTripTest`

For new authoring examples, add one JSON fixture-style test in
`A2uiRendererTest` before relying on a live agent. The test should prove:

- The surface does not crash with missing data.
- Data-model updates progressively fill the widget.
- User actions emit the exact expected `name`, `actionId`, and `context`.
- Any local state survives data updates and resets after surface deletion.

## Compatibility Notes

- A2UI support is opt-in through the WS hello capability declaration. Keep
  `A2UI_DEFAULT_SUPPORTED_CATALOGS` and `A2UI_DEFAULT_SUPPORTED_WIDGETS` honest.
- Unknown A2UI envelopes parse as `A2uiMessage.Unknown`; unknown widgets render
  a placeholder.
- Do not remove accepted aliases (`onClick`, flat `action.name`,
  `templateComponentId`, string `items`) without a compatibility bead.
- Keep payloads raw-preserving. Strict validation belongs at the shim/protocol
  boundary, while the renderer should degrade visibly and cheaply.
