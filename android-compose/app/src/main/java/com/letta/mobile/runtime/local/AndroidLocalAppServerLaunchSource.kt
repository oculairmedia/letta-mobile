package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import com.letta.mobile.data.runtime.host.LocalAppServerLauncher
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prepares each on-device App Server launch (letta-mobile-by4vm.110.5): keeps the app process in
 * the foreground, extracts the bundled runtime (first run per asset revision), and opens the
 * network bridge letta.js fetches through. The bridge lives as long as node does: the whole app
 * process.
 */
@Singleton
class AndroidLocalAppServerLaunchSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val networkBridge: AndroidNetworkBridge,
) {
    private val bridgeLock = Mutex()
    private var bridge: AndroidNetworkBridgeSession? = null

    suspend fun launch(): LocalAppServerLaunch {
        check(LocalLettaCodeService.start(context)) { "Embedded LettaCode foreground service could not start." }
        val project = assetExtractor.prepare()
        val session = bridgeLock.withLock { bridge ?: networkBridge.start().also { bridge = it } }
        return project.androidLocalAppServerLaunch(
            networkBridge = AndroidNetworkBridgeEndpoint(session.baseUrl, session.authToken),
            toolInstaller = AndroidContextEmbeddedToolInstaller(context),
        )
    }
}

/**
 * The Android [LocalAppServerHost]. With [LibnodeAppServerLauncher] it is single launch (no idle
 * stop, no in-process restart; a dead server means "restart the app"). Lazy: nothing starts until the first
 * `acquire()`.
 */
fun androidLocalAppServerHost(
    launcher: LocalAppServerLauncher,
    launchSource: AndroidLocalAppServerLaunchSource,
    discovery: AppServerDiscovery,
    scope: CoroutineScope,
): LocalAppServerHost = LocalAppServerHost(
    launcher = launcher,
    launch = launchSource::launch,
    readyz = discovery::readyz,
    scope = scope,
    logLine = { line -> Log.i(LOCAL_APP_SERVER_TAG, line) },
)

private const val LOCAL_APP_SERVER_TAG = "LocalAppServer"
