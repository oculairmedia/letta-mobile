package com.letta.mobile.data.repository

import com.letta.mobile.data.api.RunApi

/** Android binding for [CachedRunRepository]. Phase 5k. */
class RunRepository(
    runApi: RunApi,
    irohRunSource: IrohAdminRpcRunSource? = null,
) : CachedRunRepository(
    remote = runApi,
    irohRunSource = irohRunSource,
)
