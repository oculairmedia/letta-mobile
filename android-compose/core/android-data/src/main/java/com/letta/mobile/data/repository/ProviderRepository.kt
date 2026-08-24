package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProviderApi

/**
 * Android binding for [CachedProviderRepository]: HTTP [ProviderApi] + optional Iroh list source.
 *
 * Phase 5g — cache/refresh/Iroh list routing live in sharedLogic; this type keeps
 * the historical constructor for session wiring and existing unit tests.
 */
class ProviderRepository(
    providerApi: ProviderApi,
    irohProviderSource: IrohAdminRpcProviderSource? = null,
) : CachedProviderRepository(
    remote = providerApi,
    irohProviderSource = irohProviderSource,
)
