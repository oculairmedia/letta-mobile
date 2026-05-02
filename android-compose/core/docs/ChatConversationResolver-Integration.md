# ChatConversationResolver Integration Guide

## Overview

`ChatConversationResolver` extracts the conversation resolution logic from `AdminChatViewModel` into a reusable, testable component. This document describes how to integrate it into the ViewModel.

## Current State (AdminChatViewModel)

The ViewModel currently performs conversation resolution inline in its `init` block (~lines 140-240):

```kotlin
init {
    val explicitConversationId = requestedConversationArg?.takeIf { it.isNotBlank() }
    val isFreshRoute = freshRouteKey != null || requestedConversationArg?.isBlank() == true
    
    if (shouldUseClientModeForCurrentRoute) {
        // Client mode resolution path
        when {
            explicitConversationId != null -> { /* ... */ }
            currentClientModeConversationId() != null -> { /* ... */ }
            isFreshRoute -> { /* ... */ }
            else -> { /* resolve most recent */ }
        }
    } else {
        // Non-client mode resolution path
        when {
            explicitConversationId != null -> { /* ... */ }
            conversationManager.getActiveConversationId(agentId) != null -> { /* ... */ }
            else -> { /* resolve most recent */ }
        }
    }
}
```

## Proposed Integration

### 1. Inject ChatConversationResolver

Add the resolver to the ViewModel constructor:

```kotlin
@HiltViewModel
class AdminChatViewModel @Inject constructor(
    // ... existing dependencies
    private val conversationResolver: ChatConversationResolver,
) : ViewModel() {
    // ...
}
```

### 2. Replace init block resolution logic

Replace the inline resolution logic with a resolver call:

```kotlin
init {
    viewModelScope.launch {
        val resolutionState = conversationResolver.resolve(
            agentId = agentId,
            routeConversationId = requestedConversationArg,
            freshRouteKey = freshRouteKey,
            isClientMode = shouldUseClientModeForCurrentRoute,
            savedClientModeConversationId = if (shouldUseClientModeForCurrentRoute) {
                currentClientModeConversationId()
            } else null,
            activeConversationId = if (!shouldUseClientModeForCurrentRoute) {
                conversationManager.getActiveConversationId(agentId)
            } else null,
        )
        
        when (resolutionState) {
            is ChatConversationResolver.ResolutionState.Ready -> {
                val conversationId = resolutionState.conversationId
                
                if (shouldUseClientModeForCurrentRoute) {
                    setClientModeConversationId(conversationId)
                }
                
                _conversationState.value = ConversationState.Ready(conversationId)
                startTimelineObserver(conversationId)
            }
            
            is ChatConversationResolver.ResolutionState.NoConversation -> {
                if (shouldUseClientModeForCurrentRoute) {
                    setClientModeConversationId(null)
                }
                _conversationState.value = ConversationState.NoConversation
            }
            
            is ChatConversationResolver.ResolutionState.Error -> {
                // Handle error case
                _conversationState.value = ConversationState.NoConversation
            }
        }
    }
}
```

### 3. Benefits

**Testability:**
- Resolution logic is now unit-testable in isolation
- ViewModel tests can mock the resolver and focus on state management

**Maintainability:**
- Single source of truth for resolution precedence
- Easier to understand and modify resolution rules
- Clear separation between resolution logic and ViewModel state management

**Reusability:**
- Can be used by other ViewModels that need conversation resolution
- Consistent resolution behavior across the app

## Resolution Precedence Reference

### Client Mode
1. Explicit route `conversationId` (non-blank)
2. Saved `conversationId` from `SavedStateHandle`
3. Fresh route intent (`freshRouteKey != null` or `conversationId.isBlank()`)
4. Resolve most recent conversation (30s cache tolerance)

### Non-Client Mode
1. Explicit route `conversationId` (also sets as active)
2. Active conversation for agent
3. Resolve most recent conversation (30s cache tolerance)

## Testing Strategy

The resolver includes comprehensive unit tests covering:
- All resolution precedence paths for both modes
- Precedence ordering (explicit > saved/active > fresh > resolve)
- NoConversation state when resolution returns null
- Custom `maxAgeMs` parameter handling
- Side effects (setActiveConversation calls)

ViewModel integration tests should mock the resolver and verify:
- State transitions based on resolution results
- Timeline observer lifecycle
- SavedStateHandle updates
- Error handling

## Migration Checklist

- [ ] Add `ChatConversationResolver` to `AdminChatViewModel` constructor
- [ ] Replace inline resolution logic in `init` block with resolver call
- [ ] Update ViewModel tests to mock resolver
- [ ] Verify conversation selection behavior in manual testing
- [ ] Remove old inline resolution code
- [ ] Update documentation if resolution precedence changes
