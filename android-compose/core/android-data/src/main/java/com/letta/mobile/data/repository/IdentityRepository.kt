package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IdentityApi

/** Android binding for [CachedIdentityRepository]. Phase 5j. */
class IdentityRepository(
    identityApi: IdentityApi,
    irohIdentitySource: IrohAdminRpcIdentitySource? = null,
) : CachedIdentityRepository(
    remote = identityApi,
    irohIdentitySource = irohIdentitySource,
)
