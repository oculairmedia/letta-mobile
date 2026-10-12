package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.dreaming.DreamFinishTracker
import com.letta.mobile.data.dreaming.DreamFinished
import com.letta.mobile.data.dreaming.DreamNotifyPrefs
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.dreaming.DreamingStrings

/** letta-mobile-iczm7: one "dream finished" notification's text. */
internal data class DreamToast(val title: String, val message: String) {
    companion object {
        fun of(finished: DreamFinished, agentName: String?): DreamToast = DreamToast(
            title = agentName ?: DreamingStrings.TITLE,
            message = if (finished.succeeded) {
                "Finished dreaming. Its memory changes are in the Dreaming history."
            } else {
                "A dream did not finish."
            },
        )
    }
}

/** Where finished-dream notifications go: the native notifier, agent names, and what a click does. */
internal class DreamNotificationSink(
    val controller: DesktopNucleusController,
    val agentName: (String) -> String?,
    val onActivate: () -> Unit,
)

/**
 * letta-mobile-iczm7: a native notification when a dream finishes, for agents whose owner opted in
 * on the Dreaming surface. It watches the registry entries the desktop already follows (the
 * selected conversation's), so a dream in a conversation that is not open is not seen.
 */
@Composable
internal fun DreamFinishedNotificationsEffect(entries: List<SubagentEntry>, prefs: DreamNotifyPrefs, sink: DreamNotificationSink) {
    val tracker = remember { DreamFinishTracker() }
    LaunchedEffect(entries) {
        tracker.update(entries).filter { prefs.isEnabled(it.agentId) }.forEach { finished ->
            sink.controller.notifyDreamFinished(DreamToast.of(finished, sink.agentName(finished.agentId)), sink.onActivate)
        }
    }
}
