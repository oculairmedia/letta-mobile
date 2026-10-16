package com.letta.mobile.data.session

import com.letta.mobile.data.transport.iroh.DirectAppServerDialer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * letta-mobile-by4vm.110.6: how a LOCAL_RUNTIME session's channel reaches the on-device runtime's
 * loopback App Server. Bound by :app over the one `LocalAppServerHost` (libnode in process).
 */
interface LocalRuntimeDialer : DirectAppServerDialer {
    /** The first dial starts node: asset extraction on a fresh install (~50 s), then ~3 s to listen. */
    override val readyTimeout: Duration get() = LOCAL_RUNTIME_READY_TIMEOUT
}

private val LOCAL_RUNTIME_READY_TIMEOUT: Duration = 3.minutes
