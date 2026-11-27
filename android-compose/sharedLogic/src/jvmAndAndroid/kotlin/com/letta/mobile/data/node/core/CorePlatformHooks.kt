package com.letta.mobile.data.node.core

import com.letta.mobile.core.ffi.HostException
import com.letta.mobile.core.ffi.PlatformEvents
import com.letta.mobile.core.ffi.PlatformHooks
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** What the platform reports while a core node runs. */
class PlatformSignals(
    /** True while the app is in the foreground. */
    val foreground: Flow<Boolean> = emptyFlow(),
    /** One value per connectivity change the platform sees. */
    val networkChanges: Flow<Unit> = emptyFlow(),
)

/**
 * The core's [PlatformHooks]: the node key comes from [keys] (the same store the Kotlin node's
 * Iroh endpoint uses, so both are one identity), and while the node runs [signals] are forwarded to
 * it from [scope].
 */
class CorePlatformHooks(
    private val keys: IrohSecretKeyStore,
    private val signals: PlatformSignals,
    private val scope: CoroutineScope,
) : PlatformHooks {
    private var forwarding: Job? = null

    override suspend fun nodeSecretKey(): ByteArray = try {
        keys.loadOrCreate()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("SwallowedException") e: IOException) {
        throw HostException.Failed("node key: ${e.message}")
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        throw HostException.Failed("node key: ${e.message}")
    }

    @Synchronized
    override fun attach(events: PlatformEvents) {
        forwarding?.cancel()
        forwarding = scope.launch {
            launch { signals.foreground.collect { events.foregroundChanged(it) } }
            launch { signals.networkChanges.collect { events.networkChanged() } }
        }
    }

    @Synchronized
    override fun detach() {
        forwarding?.cancel()
        forwarding = null
    }
}
