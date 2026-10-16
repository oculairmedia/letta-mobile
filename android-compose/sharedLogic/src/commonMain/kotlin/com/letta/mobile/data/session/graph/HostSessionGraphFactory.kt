package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.session.ANDROID_REMOTE_LETTA_ID_PREFIX
import com.letta.mobile.data.session.DESKTOP_REMOTE_LETTA_ID_PREFIX
import com.letta.mobile.data.session.DefaultSessionRepositoryGraphProvider
import com.letta.mobile.data.session.SessionRepositoryGraphFactory
import com.letta.mobile.data.session.WEB_REMOTE_LETTA_ID_PREFIX
import com.letta.mobile.data.session.remoteLettaBackendDescriptor
import com.letta.mobile.runtime.BackendDescriptor
import kotlinx.atomicfu.atomic

/**
 * letta-mobile-ckmub.17.1: the session graph factory desktop and the browser share. Each [create]
 * asks [bindingProvider] for the generation's backend and channel, binds [HostRepositoryAdapters]
 * over it and wraps them in the one [SharedSessionRepositoryGraph].
 */
class HostSessionGraphFactory(
    private val bindingProvider: () -> HostSessionBinding,
) : SessionRepositoryGraphFactory<SharedSessionRepositoryGraph> {
    private val nextId = atomic(0L)

    override fun create(): SharedSessionRepositoryGraph {
        val binding = bindingProvider()
        val adapters = HostRepositoryAdapters(binding)
        return SharedSessionRepositoryGraph(
            identity = SessionGraphIdentity(
                id = nextId.incrementAndGet(),
                backendDescriptor = hostRemoteLettaDescriptor(binding.platform, binding.config),
                channelTransport = binding.channelTransport,
                capabilities = adapters.capabilities,
            ),
            host = adapters.hostRepositories(),
            transportBound = adapters.transportBound,
            onClose = {
                adapters.closeables.forEach { closeable ->
                    runCatching { closeable.close() }
                }
            },
        )
    }
}

/** The graph provider desktop and the browser share; a rebuild closes the previous generation. */
class HostSessionGraphProvider(
    factory: SessionRepositoryGraphFactory<SharedSessionRepositoryGraph>,
    platform: SessionPlatform,
) : DefaultSessionRepositoryGraphProvider<SharedSessionRepositoryGraph>(
    factory = factory,
    sessionSwitchMessage = "${platform.label} session switched during operation",
)

fun hostRemoteLettaDescriptor(platform: SessionPlatform, config: LettaConfig?): BackendDescriptor =
    remoteLettaBackendDescriptor(config = config, idPrefix = platform.remoteLettaIdPrefix)

private val SessionPlatform.remoteLettaIdPrefix: String
    get() = when (this) {
        SessionPlatform.ANDROID -> ANDROID_REMOTE_LETTA_ID_PREFIX
        SessionPlatform.DESKTOP -> DESKTOP_REMOTE_LETTA_ID_PREFIX
        SessionPlatform.WEB -> WEB_REMOTE_LETTA_ID_PREFIX
    }

private val SessionPlatform.label: String
    get() = when (this) {
        SessionPlatform.ANDROID -> "Android"
        SessionPlatform.DESKTOP -> "Desktop"
        SessionPlatform.WEB -> "Web"
    }
