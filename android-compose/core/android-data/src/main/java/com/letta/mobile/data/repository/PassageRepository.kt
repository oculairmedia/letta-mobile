package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.PassageRemoteSource

/**
 * Android binding for [CachedPassageRepository]. Archival passages over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class PassageRepository(
    remote: PassageRemoteSource = noAppServerRoute(),
    irohPassageSource: IrohAdminRpcPassageSource? = null,
) : CachedPassageRepository(
    remote = remote,
    irohPassageSource = irohPassageSource,
)
