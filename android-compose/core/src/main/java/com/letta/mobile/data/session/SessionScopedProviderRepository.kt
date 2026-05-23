package com.letta.mobile.data.session

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedProviderRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedProviderRepository internal constructor(
    private val sessionManager: SessionManager,
    proxyScope: CoroutineScope,
) : IProviderRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedProviderRepositoryScope(),
    )

    private val _providers = MutableStateFlow(sessionManager.current.providerRepository.providers.value)
    override val providers: StateFlow<List<Provider>> = _providers

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.providerRepository.providers }
            .onEach { _providers.value = it }
            .launchIn(proxyScope)
    }

    private val current: IProviderRepository
        get() = sessionManager.current.providerRepository

    override suspend fun refreshProviders(name: String?, providerType: String?) =
        current.refreshProviders(name, providerType)

    override suspend fun getProvider(providerId: String): Provider = current.getProvider(providerId)

    override suspend fun createProvider(params: ProviderCreateParams): Provider = current.createProvider(params)

    override suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider =
        current.updateProvider(providerId, params)

    override suspend fun checkProvider(params: ProviderCheckParams) = current.checkProvider(params)

    override suspend fun checkExistingProvider(providerId: String) = current.checkExistingProvider(providerId)

    override suspend fun deleteProvider(providerId: String) = current.deleteProvider(providerId)
}
