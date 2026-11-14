package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Announces each newly pending tool ID once, trimming saved IDs to the tools still pending.
 * The greeted IDs are saved so a recreated Activity does not announce a pending tool again.
 */
@Composable
fun PendingToolAnnouncementEffect(
    pendingToolIds: List<String>,
    onToolStarted: () -> Unit,
) {
    var greetedToolIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(pendingToolIds) {
        val currentIds = pendingToolIds.distinct()
        repeat(currentIds.count { it !in greetedToolIds }) { onToolStarted() }
        greetedToolIds = currentIds
    }
}
