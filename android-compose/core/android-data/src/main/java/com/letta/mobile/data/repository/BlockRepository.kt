package com.letta.mobile.data.repository

import com.letta.mobile.data.api.BlockApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedBlockRepository]: HTTP [BlockApi] + optional Iroh source.
 *
 * Phase 5e — HTTP/Iroh routing lives in sharedLogic; this type keeps the historical
 * constructor for Hilt and existing unit tests.
 */
@Singleton
class BlockRepository @Inject constructor(
    blockApi: BlockApi,
    irohBlockSource: IrohAdminRpcBlockSource? = null,
) : CachedBlockRepository(
    remote = blockApi,
    irohBlockSource = irohBlockSource,
)
