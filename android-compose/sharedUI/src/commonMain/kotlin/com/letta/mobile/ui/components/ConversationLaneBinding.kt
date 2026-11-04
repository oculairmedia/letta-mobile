package com.letta.mobile.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.lanes.ConversationLane
import com.letta.mobile.data.lanes.ConversationLaneProjection

/** A view reads the session's domain owner; disposing this binding never disposes that owner. */
val LocalConversationLanes = staticCompositionLocalOf<ConversationLaneProjection?> { null }

/** The lane of each conversation the shell lists, by id, so any chat view can tell a work record. */
val LocalConversationPurposes = compositionLocalOf<Map<String, ConversationLane>> { emptyMap() }
