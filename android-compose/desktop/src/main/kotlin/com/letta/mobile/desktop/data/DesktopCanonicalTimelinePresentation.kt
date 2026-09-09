package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import kotlinx.coroutines.CoroutineScope

/**
 * Desktop binding onto the shared canonical presentation. Reuses the exact reducers, paging
 * lifecycle and settlement acknowledgement from [CanonicalTimelinePresentation]; Desktop owns only
 * the scope it renders in. Detaching a Desktop view closes its viewport lease and never retires the
 * conversation writer or drops indexed durable rows.
 *
 * Enabled by the host only once the canonical external writer is gated on; the centered [target]
 * falls back to a usable tail (reported via [CanonicalTimelinePresentation.missingTarget]) rather
 * than reverting to whole-history loading.
 */
suspend fun openDesktopCanonicalPresentation(
    coordinator: CanonicalTimelineCoordinator,
    owner: CanonicalTimelineCoordinator.Owner,
    uiScope: CoroutineScope,
    target: String? = null,
): CanonicalTimelinePresentation =
    CanonicalTimelinePresentation.open(coordinator, owner, uiScope, target?.let(::TimelineMessageId))
