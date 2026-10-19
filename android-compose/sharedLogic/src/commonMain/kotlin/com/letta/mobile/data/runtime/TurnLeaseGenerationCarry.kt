package com.letta.mobile.data.runtime

/**
 * Carries every held lease into [generation] instead of cancelling it, for a
 * host that still fences frames by connection generation but whose turns survive a reconnect (the
 * iroh-wrapper's controller). Call it BEFORE the generation provider advances to [generation]:
 * leases are superseded only when they fall behind the provider ([isBehind]), so a carried lease
 * stays live across the advance, while frames the dead generation stamped still fail the
 * per-frame stamp check and are dropped.
 */
internal fun AppServerTurnEngine.carryActiveLeasesInto(generation: Long) {
    leases.snapshot().forEach { slot ->
        slot.updateLease { lease ->
            if (lease == null || lease.connectionGeneration >= generation) lease else lease.copy(connectionGeneration = generation)
        }
    }
}
