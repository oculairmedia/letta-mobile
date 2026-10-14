package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.RunRemoteSource

/**
 * Android binding for [CachedRunRepository]. Runs over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class RunRepository(
    remote: RemoteRoute<RunRemoteSource> = RemoteRoute.Absent(SessionRoute.RUNS),
    irohRunSource: IrohAdminRpcRunSource? = null,
) : CachedRunRepository(
    remote = remote,
    irohRunSource = irohRunSource,
) {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: RunRemoteSource, irohRunSource: IrohAdminRpcRunSource? = null) : this(remote.bound(), irohRunSource)
}
