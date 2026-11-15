package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasStorageFault
import com.letta.mobile.data.canvas.affecting
import com.letta.mobile.ui.components.foundation.LettaInlineNotice
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The banner's words for [fault]: a headline the person reads, and the detail to report it with. */
internal data class CanvasStorageFaultText(val headline: String, val detail: String)

@OptIn(ExperimentalTime::class)
internal fun canvasStorageFaultText(fault: CanvasStorageFault, timeZone: TimeZone = TimeZone.currentSystemDefault()): CanvasStorageFaultText {
    val at = Instant.fromEpochMilliseconds(fault.atEpochMs).toLocalDateTime(timeZone)
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    val headline = when (fault.kind) {
        CanvasStorageFault.Kind.SAVE_FAILED -> "This board couldn't be saved; changes since $time are at risk"
        CanvasStorageFault.Kind.LOAD_FAILED -> "A notebook couldn't be read from storage at $time"
        CanvasStorageFault.Kind.QUARANTINED -> "A notebook was too large to open safely and was set aside at $time"
        CanvasStorageFault.Kind.NEAR_BUDGET -> "This board's history is growing large"
        CanvasStorageFault.Kind.COMPACTED -> "This board's older history was archived"
        CanvasStorageFault.Kind.OVER_BUDGET -> "This board's history is over its size budget and may fail to save"
        CanvasStorageFault.Kind.READ_ONLY -> "Changes since $time are not being saved; this board is read-only"
    }
    val size = fault.documentBytes?.let { bytes ->
        val tenths = bytes * 10 / (1024L * 1024L)
        " · ${tenths / 10}.${tenths % 10} MB"
    }.orEmpty()
    val detail = "Notebook ${fault.documentId ?: "unknown"}$size · ${fault.message}"
    return CanvasStorageFaultText(headline, detail)
}

/** [session]'s storage faults that concern its canvas, as a [CanvasStorageFaultBanner]; nothing without any. */
@Composable
internal fun CanvasStorageFaultOverlay(session: CanvasSession?, modifier: Modifier = Modifier) {
    if (session == null) return
    val faults by session.storageFaults.collectAsState()
    CanvasStorageFaultBanner(faults = faults.affecting(session.canvasId), modifier = modifier)
}

/**
 * A storage fault stays on the board until the app restarts: a save that did not reach disk is
 * not something to flash and forget. The newest fault leads; the count says if there are more.
 */
@Composable
internal fun CanvasStorageFaultBanner(faults: List<CanvasStorageFault>, modifier: Modifier = Modifier) {
    val fault = faults.maxByOrNull { it.atEpochMs } ?: return
    val text = canvasStorageFaultText(fault)
    // One merged node, so the live region announces the headline and detail, not nothing.
    val headline = text.headline + if (faults.size > 1) " (+${faults.size - 1} more)" else ""
    LettaInlineNotice(
        notice = LettaNotice(message = text.detail, tone = LettaPanelTone.Error, title = headline),
        modifier = modifier.widthIn(max = LettaDimens.Pane.noticeMaxWidth)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    )
}
