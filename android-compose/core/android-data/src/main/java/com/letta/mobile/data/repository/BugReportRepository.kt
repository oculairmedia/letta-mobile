package com.letta.mobile.data.repository

import com.letta.mobile.data.local.RoomBugReportLocalStore
import javax.inject.Inject
import javax.inject.Singleton

/** Android binding for [CachedBugReportRepository]. Phase 5o. */
@Singleton
class BugReportRepository @Inject constructor(
    localStore: RoomBugReportLocalStore,
) : CachedBugReportRepository(
    localStore = localStore,
)
