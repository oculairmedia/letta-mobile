# Notification Content Bug

**Date:** 2026-05-04
**Severity:** Low
**Status:** Open

## Issue

Notifications are showing the thinking/reasoning block content instead of the final assistant response.

## Root Cause

The notification builder picks the first `assistant` text chunk, but this may be the internal thinking/reasoning content rather than the final response.

## Expected Behavior

Notifications should show the final response text, not the reasoning/thinking block.

## Fix Required

In the notification generation logic, filter for the last `ASSISTANT` event chunk, not the first. Reasoning/thinking typically arrives before the final response.

### Before (incorrect)
```kotlin
// Picks first ASSISTANT chunk — may be thinking
val notificationText = chunks.first { it.event == BotStreamEvent.ASSISTANT }.text
```

### After (correct)
```kotlin
// Picks last ASSISTANT chunk — the final response
val notificationText = chunks.lastOrNull { it.event == BotStreamEvent.ASSISTANT }?.text
```

### Alternative: Skip thinking events explicitly

```kotlin
// Filter out thinking/reasoning events
val finalResponse = chunks
    .filter { it.event == BotStreamEvent.ASSISTANT }
    .filter { !it.isReasoning }  // if there's a flag
    .lastOrNull()
    ?.text
```

## Files to Check

- `ChatPushService.kt` — notification building logic
- `TimelineSyncLoop.kt` — event stream handling
- `BotStreamChunk` — event types (THINKING vs ASSISTANT vs REASONING?)

## Related

- See `screen-off-streaming-findings.md` for notification architecture context
- WsBotClient stream events: `assistant`, `reasoning`, `tool_call`, `tool_result`