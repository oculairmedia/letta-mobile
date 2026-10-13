package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.ProviderRemoteSource

/**
 * Android binding for [CachedProviderRepository]. Providers over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ProviderRepository(
    remote: ProviderRemoteSource = noAppServerRoute(),
    irohProviderSource: IrohAdminRpcProviderSource? = null,
) : CachedProviderRepository(
    remote = remote,
    irohProviderSource = irohProviderSource,
)
