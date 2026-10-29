package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.timeline.TimelineBoundedStore

/** Keeps the coordinator's dispatch ownership with every session sharing its storage. */
internal class PendingDispatchTimelineStore(
    delegate: TimelineBoundedStore,
    val dispatchOwners: PendingDispatchOwners,
) : TimelineBoundedStore by delegate
