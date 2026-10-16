package com.letta.mobile.runtime.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import com.letta.mobile.data.runtime.host.LocalAppServerLauncher
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 *
 * letta-mobile-by4vm.110.6: [prepareModel] points the runtime's `lc-lmstudio` provider at the
 * configured model before each dial: the on-device LiteRT model through [OnDeviceOpenAiBridge]'s
 * loopback OpenAI endpoint, or the "local provider" server Settings names. New agents are created
 * on [EmbeddedLettaCodeModelSelection.lettaCodeModelHandle].
 */
@Singleton
class AndroidLocalAppServerLaunchSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val networkBridge: AndroidNetworkBridge,
    private val onDeviceBridge: OnDeviceOpenAiBridge,
    /** Where the host's lifecycle lines go too (letta-mobile-by4vm.121.11). */
    val lifecycleLog: RuntimeLifecycleLog,
) {
    private val lock = Mutex()
    private var bridge: AndroidNetworkBridgeSession? = null
    private var model: ActiveModel? = null

    suspend fun launch(): LocalAppServerLaunch {
        check(LocalLettaCodeService.start(context)) { "Embedded LettaCode foreground service could not start." }
        val extractBegan = SystemClock.elapsedRealtime()
        val project = assetExtractor.prepare()
        lifecycleLog.line("[lifecycle] runtime assets ready in ${SystemClock.elapsedRealtime() - extractBegan} ms")
        val session = lock.withLock { bridge ?: networkBridge.start().also { bridge = it } }
        return project.androidLocalAppServerLaunch(
            networkBridge = AndroidNetworkBridgeEndpoint(session.baseUrl, session.authToken),
            toolInstaller = AndroidContextEmbeddedToolInstaller(context),
        )
    }

    /**
     * Writes `providers/auth.json` for [config]'s model selection, starting (or swapping) the
     * on-device bridge when the model runs on this phone. Returns the backend directory.
     */
    suspend fun prepareModel(config: LettaConfig?): File {
        val project = assetExtractor.prepare()
        val selection = config?.let(EmbeddedLettaCodeModelSelection::from) ?: return project.storageDirectory
        lock.withLock {
            val endpoint = if (selection.routesToOpenAiCompatibleProvider) {
                model?.close()
                model = null
                ProviderEndpoint(
                    baseUrl = requireNotNull(selection.effectiveProviderBaseUrl),
                    apiKey = selection.effectiveProviderApiKey ?: EmbeddedLettaCodeModelSelection.DEFAULT_LM_STUDIO_API_KEY,
                )
            } else {
                onDeviceEndpoint(selection)
            }
            project.storageDirectory.writeLocalProviderAuth(endpoint)
        }
        Log.i(TAG, "local provider: model=${selection.lettaCodeModelHandle} custom=${selection.isCustomProvider}")
        return project.storageDirectory
    }

    /** Caller holds [lock]. Keeps the running bridge while the selection is unchanged. */
    private fun onDeviceEndpoint(selection: EmbeddedLettaCodeModelSelection): ProviderEndpoint {
        val current = model?.takeIf { it.startKey == selection.startKey }
            ?: run {
                model?.close()
                ActiveModel(selection.startKey, onDeviceBridge.start(selection)).also { model = it }
            }
        return ProviderEndpoint(current.session.baseUrl, current.session.authToken)
    }

    private class ActiveModel(val startKey: String, val session: OnDeviceOpenAiBridgeSession) {
        fun close() = session.close()
    }

    private companion object {
        const val TAG = "LocalAppServer"
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
    logLine = launchSource.lifecycleLog::line,
)
