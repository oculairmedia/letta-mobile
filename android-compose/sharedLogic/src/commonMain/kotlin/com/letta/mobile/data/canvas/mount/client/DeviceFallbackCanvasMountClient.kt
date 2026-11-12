package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The window's mount client: the [host]'s while the host advertises mounts ([CanvasMountClient.supported]),
 * else [device]'s, so a pick swaps the board on screen either way. This is the one place the choice
 * is made; every call goes to the client the host's support picks at that moment, and [reach] says
 * which one answers, so the screen can say when a mount is held on this device only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceFallbackCanvasMountClient(
    private val host: CanvasMountClient,
    private val device: CanvasMountClient = DeviceCanvasMountClient(),
) : CanvasMountClient {
    private val always = MutableStateFlow(true).asStateFlow()

    /** One of the two always answers. */
    override val supported: StateFlow<Boolean> get() = always

    override val reach: Flow<CanvasMountReach> = host.supported.flatMapLatest { clientFor(it).reach }.distinctUntilChanged()

    /** The client answering now. */
    val active: CanvasMountClient get() = clientFor(host.supported.value)

    override suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup = active.resolve(target)

    override suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange = active.mount(target, canvas)

    override suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange = active.unmount(target)

    override fun changes(target: ConversationCanvasTarget): Flow<CanvasId> = host.supported.flatMapLatest { clientFor(it).changes(target) }

    private fun clientFor(hostSupports: Boolean): CanvasMountClient = if (hostSupports) host else device
}
