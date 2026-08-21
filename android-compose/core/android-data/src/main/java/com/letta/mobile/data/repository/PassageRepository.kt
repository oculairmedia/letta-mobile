package com.letta.mobile.data.repository

import com.letta.mobile.data.api.PassageApi

/** Android binding for [CachedPassageRepository]. Phase 5k. */
open class PassageRepository(
    passageApi: PassageApi,
    irohPassageSource: IrohAdminRpcPassageSource? = null,
) : CachedPassageRepository(
    remote = passageApi,
    irohPassageSource = irohPassageSource,
)
