package com.letta.mobile.desktop.plugin

import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.plugin.remote.PluginHostConnection
import com.letta.mobile.data.plugin.remote.hostPluginViewsModel
import com.letta.mobile.data.plugin.remote.pluginAdminLinkOver
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.desktop.canvas.DesktopCanvasHostSync
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * letta-mobile-cdgpr.8: the desktop's [PluginViewsModel]. It works over whichever channel transport
 * is current ([transport] is read per call, and its state drives refreshes), and packages go up
 * through the canvas relay. The "Live plugin views" switch is in [store], this device's own
 * properties file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like the canvas relay.
internal fun desktopPluginViewsModel(
    transport: () -> IChannelTransport?,
    store: DesktopLivePluginViewsStore = DesktopLivePluginViewsStore(),
): PluginViewsModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return hostPluginViewsModel(
        host = PluginHostConnection(
            link = pluginAdminLinkOver(transport),
            changes = snapshotFlow { transport() }.flatMapLatest { it?.state ?: flowOf(null) },
        ),
        relay = DesktopCanvasHostSync.relay,
        liveViews = store.setting(scope),
        scope = scope,
    )
}

/**
 * This device's "Live plugin views" switch, in `live-plugin-views.properties` (key
 * [LIVE_VIEWS_KEY]). It is not a secret and not tied to a backend, the same as the other desktop
 * preference files. Missing or unreadable reads as off, the default.
 */
internal class DesktopLivePluginViewsStore(private val path: Path = defaultDesktopStateDirectory().resolve("live-plugin-views.properties")) {
    @Synchronized
    fun load(): Boolean = try {
        Properties().also { props -> Files.newInputStream(path).use(props::load) }.getProperty(LIVE_VIEWS_KEY).toBoolean()
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Blocking, written atomically; callers keep it off the UI thread. */
    @Synchronized
    fun save(enabled: Boolean) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "live-plugin-views", ".tmp")
        try {
            Files.newOutputStream(tmp).use { Properties().apply { setProperty(LIVE_VIEWS_KEY, enabled.toString()) }.store(it, null) }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** The switch: applied at once, saved on [scope] off the UI thread. A failed save keeps it for this session. */
    fun setting(scope: CoroutineScope): SettingValue<Boolean> {
        val value = MutableStateFlow(load())
        return SettingValue(value.asStateFlow()) { on ->
            value.value = on
            scope.launch(Dispatchers.IO) {
                try {
                    save(on)
                } catch (_: IOException) {
                    // Kept in memory for this session; the next successful save persists it.
                }
            }
        }
    }

    companion object {
        const val LIVE_VIEWS_KEY = "plugins.liveViews"
    }
}
