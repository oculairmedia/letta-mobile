# Slice 3 Analysis: Big Zero-Coupling Renderers

## Target Files (from specification)
- MessageContentFactory.kt (~733 lines)
- ToolOutputRenderer.kt (~637 lines)  
- ToolOutputSyntaxHighlighter.kt (~643 lines)

## Blocking Dependencies Found

### 1. MessageContentFactory.kt
**Status**: ❌ BLOCKED - Cannot move

**Reason**: Depends on feature-chat screen composables not yet in shared module:
```kotlin
import com.letta.mobile.feature.chat.screen.MessageToolCalls
import com.letta.mobile.feature.chat.screen.SubagentNotificationCard
```

These are defined in `ChatToolCallCards.kt` in feature-chat's screen package and have not been moved to sharedLogic. MessageContentFactory references them in its rendering logic.

**Resolution Path**: These screen composables would need to move first (or MessageContentFactory would need to be refactored to accept them as parameters).

---

### 2. ToolOutputRenderer.kt
**Status**: ❌ BLOCKED - Cannot move

**Reason**: Uses Android resource system via stringResource:
```kotlin
import androidx.compose.ui.res.stringResource
import com.letta.mobile.feature.chat.R

// Usage at lines 187, 411, 414, 444, 457, 510:
text = stringResource(R.string.screen_chat_tool_output_chars_omitted, ...)
text = stringResource(R.string.screen_chat_tool_output_diff_file)
```

While `stringResource` is from `androidx.compose.ui` (not `android.*`), it accesses Android's resource system (`R.string.*`) which is platform-specific and not available in multiplatform code.

**Resolution Path**: Would need expect/actual pattern with platform-specific string resources (slice 4 seam work).

---

### 3. ToolOutputSyntaxHighlighter.kt
**Status**: ❌ BLOCKED - Cannot move

**Reason**: Depends on `customColors` from designsystem module:
```kotlin
import com.letta.mobile.ui.theme.customColors

// Usage at lines 162, 175-176:
val customColors = MaterialTheme.customColors
success = customColors.successColor,
warning = customColors.warningTextColor,
```

**Module Dependency Issue**:
- `customColors` is defined in `android-compose/designsystem/src/main/java/com/letta/mobile/ui/theme/CustomColors.kt`
- `designsystem` is an Android-only module (`com.android.library`)
- `sharedLogic/jvmAndAndroid` is multiplatform and does NOT depend on designsystem
- Cannot add Android-only module as dependency to multiplatform source set

**Resolution Path**: Either:
1. Move customColors to sharedLogic (requires making it multiplatform)
2. Use expect/actual pattern for color access
3. Refactor to accept colors as parameters

---

## Conclusion

**All three files are blocked** and cannot be moved cleanly in slice 3. Each has legitimate coupling that prevents the pure MOVE operation specified in the instructions:

- MessageContentFactory: graph dependency on unmoved types
- ToolOutputRenderer: Android resource system coupling  
- ToolOutputSyntaxHighlighter: Android-only module dependency

Per instruction: "If a file genuinely can't move cleanly, SKIP it and report why."

## Recommendation

These files should be addressed in **slice 4** (the expect/actual seam slice) where platform-specific concerns can be properly abstracted, OR after prerequisite work:

1. Move MessageToolCalls/SubagentNotificationCard to shared (if they're zero-coupling)
2. Create expect/actual for string resources
3. Create expect/actual for custom colors OR move theme to multiplatform
