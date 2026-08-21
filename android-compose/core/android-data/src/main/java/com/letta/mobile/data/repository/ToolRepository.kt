package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ToolApi
import javax.inject.Inject

/**
 * Android binding for [CachedToolRepository]: HTTP [ToolApi] + optional Iroh source.
 *
 * Phase 5c — caching / refresh / transport routing live in sharedLogic; this type
 * keeps the historical constructor for Hilt and existing unit tests.
 */
open class ToolRepository @Inject constructor(
    toolApi: ToolApi,
    irohToolSource: IrohAdminRpcToolSource? = null,
) : CachedToolRepository(
    remote = toolApi,
    irohToolSource = irohToolSource,
)
