package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Statistics schema. */
@Serializable
data class Statistics(
    @SerialName("total_issues")
    val totalIssues: Int,
    @SerialName("open_issues")
    val openIssues: Int,
    @SerialName("in_progress_issues")
    val inProgressIssues: Int,
    @SerialName("closed_issues")
    val closedIssues: Int,
    @SerialName("deferred_issues")
    val deferredIssues: Int,
    @SerialName("blocked_issues")
    val blockedIssues: Int? = null,
    @SerialName("ready_issues")
    val readyIssues: Int? = null,
    @SerialName("pinned_issues")
    val pinnedIssues: Int,
    @SerialName("epics_eligible_for_closure")
    val epicsEligibleForClosure: Int,
    @SerialName("average_lead_time_hours")
    val averageLeadTimeHours: Double,
)
