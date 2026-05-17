# A2UI Phase 1 WebSocket Contract

Date: 2026-05-17
Bead: `letta-mobile-51xm.2`

This document records the Android-side Phase 1 contract for coordinating with the Letta rig tickets `letta-mgn.2` and `letta-mgn.5`.

## Capability Declaration

Android sends the same A2UI capability object in both WebSocket session paths:

- Admin shim `/shim/v1/mobile`: top-level field `a2ui_capability` on the client `hello` frame.
- LettaBot gateway `/api/v1/agent-gateway`: top-level field `a2ui_capability` on `session_start`.

Payload:

```json
{
  "a2ui_version": "v0.9",
  "supported_catalogs": [
    "https://a2ui.org/specification/v0_9/basic_catalog.json",
    "com.letta.mobile:tool-approval/v1"
  ],
  "supported_widgets": [
    "Text",
    "Column",
    "Row",
    "Card",
    "Button",
    "Divider",
    "ToolApprovalCard"
  ],
  "theme_hints": {
    "platform": "android-compose",
    "material_version": "material3",
    "supports_dark_mode": true,
    "supports_dynamic_color": true
  }
}
```

The Letta custom catalog ID is intentionally domain-prefixed and versioned. Phase 5 owns the `ToolApprovalCard` props schema.

## Negotiation Ack

Server acknowledgement is optional but typed on Android. When present, the server returns `a2ui_negotiation` on the session ack frame:

- Admin shim: `welcome.a2ui_negotiation`
- LettaBot gateway: `session_init.a2ui_negotiation`

Payload:

```json
{
  "a2ui_enabled": true,
  "negotiated_catalog": "com.letta.mobile:tool-approval/v1",
  "negotiated_widgets": ["ToolApprovalCard"]
}
```

Missing ack is treated as disabled for renderer decisions, but it does not break the existing text/tool stream.

## A2UI Frames

Incoming A2UI frames use the existing WebSocket stream with `type: "a2ui"`. Android routes these into a dedicated A2UI event stream, not through the chat timeline message mapper.

Admin shim envelope:

```json
{
  "v": 1,
  "type": "a2ui",
  "id": "a2ui-1",
  "ts": "2026-05-17T00:00:00Z",
  "agent_id": "agent-123",
  "conversation_id": "conv-123",
  "turn_id": "turn-123",
  "run_id": "run-123",
  "message": {
    "version": "v0.9",
    "createSurface": {
      "surfaceId": "approval-1",
      "catalogId": "com.letta.mobile:tool-approval/v1"
    }
  }
}
```

LettaBot gateway envelope:

```json
{
  "type": "a2ui",
  "conversation_id": "conv-123",
  "request_id": "request-123",
  "message": {
    "version": "v0.9",
    "createSurface": {
      "surfaceId": "approval-1",
      "catalogId": "com.letta.mobile:tool-approval/v1"
    }
  }
}
```

Android accepts `message`, `messages`, `payload`, or `data` as the A2UI payload key. The payload may be a single A2UI object or an array. This keeps Phase 1 tolerant of ResponsePart/DataPart batching while still exposing typed `A2uiMessage` objects downstream.

## User Actions

Outgoing widget interactions use the same admin-shim WebSocket session with `type: "userAction"`. Android resolves each declared action context value before sending, so `path` bindings are replaced with the current local data-model value.

```json
{
  "v": 1,
  "type": "userAction",
  "id": "action-1",
  "ts": "2026-05-17T00:00:00Z",
  "actionName": "submit_booking",
  "surfaceId": "booking-1",
  "context": {
    "partySize": 4,
    "reservationTime": "2026-05-17T18:30"
  }
}
```

Android emits actions only for explicit user actions such as button taps. Input widgets update the local data model immediately; the current values are included in the next emitted action. If the WebSocket is disconnected, Android keeps a small FIFO retry queue and surfaces a banner if the queue is full.

## Parsed Message Set

The Phase 1 parser supports official v0.9 server-to-client envelopes:

- `createSurface`
- `updateComponents`
- `updateDataModel`
- `deleteSurface`

`updateComponents.components` are represented as typed `A2uiComponent(id, component, raw)` so Phase 2 can render known widgets while preserving custom and future fields. Unknown A2UI envelopes parse as `A2uiMessage.Unknown` for debug visibility without breaking the stream.

## Debug Visibility

Admin-shim A2UI frames are collected in chat state and shown in chat debug mode as a compact overlay with message type, surface ID, and conversation suffix. This is a development-only inspection path; Phase 2 owns the no-op surface manager and actual surface state.
