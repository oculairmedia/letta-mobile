package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.timeline.PendingLocalScope
import com.letta.mobile.data.timeline.PendingLocalStore

/** Persistence and the backend/agent scope owning every optimistic row from this sender. */
internal class TimelineOutboundPendingPersistence(
    val store: PendingLocalStore,
    val scope: PendingLocalScope,
)
