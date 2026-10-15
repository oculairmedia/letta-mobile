package com.letta.mobile.data.controller

/**
 * letta-mobile-by4vm.50: whether [RuntimeSyncScheduler] may send a cadence (periodic) `sync` right
 * now. Asked only when a periodic sync is due; recovery syncs (an `event_seq` gap, the app coming
 * back to the foreground) never ask, because they repair a view that is known to be stale.
 *
 * A suppressed periodic sync is not dropped: it stays due and goes out on the first tick the
 * budget allows it.
 */
fun interface SyncBudgetPolicy {
    fun allowsPeriodicSync(): Boolean

    companion object {
        /** Desktop and direct sockets: the cadence always runs. */
        val Unlimited: SyncBudgetPolicy = SyncBudgetPolicy { true }
    }
}

/** What a phone's cadence budget looks at; read fresh on every check. */
data class DeviceSyncConditions(
    val unmeteredNetwork: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
)

/**
 * The phone budget: periodic syncs only on an unmetered network, and only while the device is
 * charging or its screen is on. A metered connection, or an unplugged phone with its screen off,
 * gets gap-driven and foreground resyncs only.
 */
class DeviceConditionsSyncBudget(private val conditions: () -> DeviceSyncConditions) : SyncBudgetPolicy {
    override fun allowsPeriodicSync(): Boolean {
        val now = conditions()
        return now.unmeteredNetwork && (now.charging || now.screenOn)
    }
}
