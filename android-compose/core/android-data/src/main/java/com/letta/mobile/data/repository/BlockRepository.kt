package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.BlockRemoteSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedBlockRepository]: memory blocks over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
@Singleton
class BlockRepository internal constructor(
    remote: RemoteRoute<BlockRemoteSource>,
    irohBlockSource: IrohAdminRpcBlockSource? = null,
) : CachedBlockRepository(
    remote = remote,
    irohBlockSource = irohBlockSource,
) {
    @Inject
    constructor(irohBlockSource: IrohAdminRpcBlockSource?) : this(RemoteRoute.Absent(SessionRoute.BLOCKS), irohBlockSource)

    /** A repository over a bound [remote] (tests bind a fake). */
    internal constructor(remote: BlockRemoteSource, irohBlockSource: IrohAdminRpcBlockSource? = null) : this(remote.bound(), irohBlockSource)
}
