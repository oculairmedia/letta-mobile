# Letta Mobile Chat UX Polish - Implementation Handoff

## Completed Features ✅

- Thinking indicator with pulsing border (primary-colored left line)
- Reasoning collapse/expand with animation
- Tool call cards with expand/collapse

---

## Feature 1: Run Again Button

### What It Does
Allows users to re-send their own messages with one tap.

### Implementation Pattern
Reference: `ChatPanel.kt` lines 476-492 (Google Gallery)

```kotlin
// In ChatPanel.kt - MessageActionButton row
if (message.side == ChatSide.USER) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Run again button
        if (selectedModel.showRunAgainButton) {
            MessageActionButton(
                label = stringResource(R.string.run_again),
                icon = Icons.Rounded.Refresh,
                onClick = { onRunAgainClicked(selectedModel, message) },
                enabled = !uiState.inProgress,
            )
        }
    }
}
```

### Our Implementation

**Location:** `ChatMessageComponents.kt` - Add to user message row after `MessageBubbleSurface`

**Step 1:** Add callback to `ChatMessageItem`:
```kotlin
onRerunMessage: ((UiMessage) -> Unit)? = null,
```

**Step 2:** Add button in `MessageBubbleSurface` for user messages:
```kotlin
if (message.role == "user" && onRerunMessage != null) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { onRerunMessage(message) }) {
            Icon(Icons.Default.Replay, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Run again")
        }
    }
}
```

**Step 3:** Wire in `AdminChatViewModel`:
```kotlin
fun rerunMessage(message: UiMessage) {
    sendMessage(message.content)
}
```

---

## Feature 2: Input History Bottom Sheet

### What It Does
Shows past user prompts in a bottom sheet for quick reuse.

### Implementation Pattern
Reference: `MessageInputText.kt` lines 417-430 (Google Gallery)

```kotlin
// Dropdown menu item
DropdownMenuItem(
    text = { Text("Input history") },
    onClick = {
        showAddContentMenu = false
        showTextInputHistorySheet = true
    },
)

// Bottom sheet
if (showTextInputHistorySheet) {
    TextInputHistorySheet(
        history = modelManagerUiState.textInputHistory,
        onHistoryItemClicked = { item -> ... },
    )
}
```

### Our Implementation

**Step 1:** Store input history in `ChatComposerState` or `AdminChatViewModel`

**Step 2:** Add history storage
```kotlin
// On send, store to history
private val _inputHistory = mutableStateListOf<String>()
val inputHistory: List<String> = _inputHistory
```

**Step 3:** Add bottom sheet UI in `ChatComposer.kt`

See: `ModalBottomSheet` pattern in our codebase (e.g., `AgentSettingsScreen`)

---

## Feature 3: FloatingBanner (Replace Toast)

### What It Does
Slide-in banner for limit warnings (image limit, rate limits) instead of system Toast.

### Reference Implementation
Google Gallery: `FloatingBanner.kt`

```kotlin
@Composable
fun FloatingBanner(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
```

### Our Implementation

**Location:** New file in `designsystem/` or add to `ChatComposer.kt`

**Usage:** Replace `Toast.makeText()` calls with state-triggered banner:
```kotlin
var showImageLimitBanner by remember { mutableStateOf(false) }

FloatingBanner(
    visible = showImageLimitBanner,
    text = "Image limit reached",
)
```

---

## Feature 4: Latency Display

### What It Does
Shows execution time per message (e.g., "2.3s", "450ms").

### Implementation Pattern
`ChatPanel.kt` lines 493-495:

```kotlin
if (message.side == ChatSide.AGENT) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LatencyText(message = message)
    }
}
```

### Our Implementation

**Step 1:** Add field to `UiMessage`:
```kotlin
val latencyMs: Long? = null,
```

**Step 2:** Display in message footer
Location: `ChatMessageComponents.kt` - Add after content in assistant bubbles

```kotlin
message.latencyMs?.let { latency ->
    Text(
        text = formatLatency(latency),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun formatLatency(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> "${ms / 1000.0}s"
}
```

---

## Feature 5: Context-Aware Auto-Scroll

### What It Does
Only auto-scroll to bottom when user is already near the bottom (within 90px threshold).

### Reference Implementation
`ChatPanel.kt` lines 140-163:

```kotlin
// Scroll to keep up with streaming, ONLY if we are already at the bottom.
LaunchedEffect(lastMessage.value, lastMessageContent.value, lastMessage.value?.latencyMs) {
    if (messages.isNotEmpty()) {
        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        if (lastVisibleItem != null) {
            val canScroll = lastVisibleItem.index == messages.size - 1 &&
                lastVisibleItem.offset + lastVisibleItem.size - listState.layoutInfo.viewportEndOffset < 90
            if (canScroll) {
                scrollToBottom(listState = listState, animate = true)
            }
        }
    }
}
```

### Our Implementation

**Location:** `ChatMessageList.kt` - Modify the scroll LaunchedEffect

Current code (line 139-147):
```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { messageCount }
        .distinctUntilChanged()
        .collect {
            if (it > 0 && isAtBottom && scrollToMessageId == null) {
                listState.animateScrollToItem(0)
            }
        }
}
```

**Step 1:** Check if near bottom before auto-scrolling:
```kotlin
val isNearBottom = remember {
    derivedStateOf {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        lastVisible.offset + lastVisible.size - viewportEnd < 90
    }
}
```

**Step 2:** Only scroll when near bottom:
```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { messageCount }
        .distinctUntilChanged()
        .collect {
            if (it > 0 && isNearBottom.value && scrollToMessageId == null) {
                listState.animateScrollToItem(0)
            }
        }
}
```

---

## Implementation Priority

| Priority | Feature | Effort | Impact |
|----------|---------|--------|--------|
| 1 | Run Again button | Low | High |
| 2 | Latency display | Low | Medium |
| 3 | Input history | Medium | Medium |
| 4 | FloatingBanner | Low | Low |
| 5 | Context-aware scroll | Medium | Medium |

---

## Code Reference Locations

### Our Files
- `ChatMessageComponents.kt` - Message rendering (run again, latency)
- `ChatMessageList.kt` - List scroll behavior
- `ChatComposer.kt` - Input + attachments
- `AdminChatViewModel.kt` - State management
- `ChatUiStateProvider.kt` - Preview states

### Design System Files
- `MessageBubbleShape.kt` - Already exists
- `MessageSender.kt` - Already exists
- `MessageActionButton.kt` - Already exists

---

## Testing Checklist

- [ ] Run Again button appears on user messages and re-sends correctly
- [ ] Input history shows past prompts in bottom sheet
- [ ] FloatingBanner slides in/out for limit warnings
- [ ] Latency shows on assistant messages after completion
- [ ] Auto-scroll only fires when user is near bottom
- [ ] Existing functionality not broken