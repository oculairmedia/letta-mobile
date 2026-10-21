package com.letta.mobile.ui.screens.config

import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** A host that is never acquired: Settings only reads its health. */
@Suppress("NoDetachedCoroutineLifecycle") // Nothing is ever launched on it.
internal fun idleLocalAppServerHost(): LocalAppServerHost = LocalAppServerHost(
    launcher = LocalAppServerLauncher { _, _, _ -> error("the local runtime does not start in tests") },
    launch = { error("the local runtime does not start in tests") },
    readyz = { false },
    scope = CoroutineScope(Dispatchers.Unconfined),
)
