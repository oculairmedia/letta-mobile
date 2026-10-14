package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.data.plugin.catalog.PluginsPageEvent.ConsentClosed
import com.letta.mobile.data.plugin.catalog.PluginsPageEvent.Done
import com.letta.mobile.data.plugin.catalog.PluginsPageEvent.Failed
import com.letta.mobile.data.plugin.catalog.PluginsPageEvent.InstallRefused
import com.letta.mobile.data.plugin.catalog.PluginsPageEvent.Started
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What the Plugins page asks for; [PluginsPageController] answers over a [PluginCatalog]. */
interface PluginsPageActions {
    /** The host's file picker hands over the chosen package; its sha256 is shown before anything is uploaded. */
    fun pick(file: PluginPackageFile) = Unit
    fun discardPicked() = Unit
    fun uploadPicked() = Unit
    fun decide(item: PluginConsentItem, allow: Boolean) = Unit
    fun installConsented() = Unit
    fun cancelConsent() = Unit
    fun setEnabled(id: PluginId, on: Boolean) = Unit
    fun askRemove(id: PluginId?) = Unit
    fun confirmRemove() = Unit
    fun askDisableAll(open: Boolean) = Unit
    fun confirmDisableAll() = Unit
    fun checkHealth(id: PluginId) = Unit
    fun setLiveViews(on: Boolean) = Unit
    fun dismissError() = Unit
}

/** The SHA-256 of [bytes] as 64 lowercase hex characters: how a package is pinned. */
fun pluginPackageSha256(bytes: ByteArray): String = Sha256.digest(bytes).joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }

private const val BYTE_MASK = 0xff
private const val HEX = 16

/**
 * letta-mobile-cdgpr.9: the Plugins page over a [PluginCatalog]. It follows the catalog's list (and
 * this device's [liveViews] switch) while [scope] lives, runs one catalog call at a time, and never
 * sends a consent that denies anything: a Deny refuses the install here and drops the staged package.
 * [hashing] is where a picked package is hashed (off the UI thread by default).
 */
class PluginsPageController(
    private val catalog: PluginCatalog,
    private val scope: CoroutineScope,
    private val liveViews: SettingValue<Boolean>? = null,
    private val hashing: CoroutineContext = Dispatchers.Default,
) : PluginsPageActions {
    private val stateFlow = MutableStateFlow(PluginsPageState(plugins = catalog.plugins.value, liveViews = liveViews?.current?.value == true))
    val state: StateFlow<PluginsPageState> = stateFlow
    private var pickedBytes: ByteArray? = null

    init {
        scope.launch { catalog.plugins.collect { send(PluginsPageEvent.Listed(it)) } }
        liveViews?.let { setting -> scope.launch { setting.current.collect { send(PluginsPageEvent.LiveViews(it)) } } }
    }

    private fun send(event: PluginsPageEvent) = stateFlow.update { PluginsPageModel.reduce(it, event) }

    override fun pick(file: PluginPackageFile) {
        val (fileName, bytes) = file.name to file.bytes
        if (bytes.size > MAX_PACKAGE_BYTES) return send(Failed("$fileName is larger than ${MAX_PACKAGE_BYTES / MIB} MiB; a plugin package must be smaller."))
        scope.launch {
            val sha256 = withContext(hashing) { pluginPackageSha256(bytes) }
            pickedBytes = bytes
            send(PluginsPageEvent.Picked(PickedPluginPackage(fileName, bytes.size, sha256)))
        }
    }

    override fun discardPicked() {
        pickedBytes = null
        send(PluginsPageEvent.PickDiscarded)
    }

    override fun uploadPicked() {
        val picked = stateFlow.value.picked ?: return
        val bytes = pickedBytes ?: return
        if (stateFlow.value.busy) return
        send(Started)
        scope.launch { staged(picked, call { catalog.install(bytes, picked.sha256) }) }
    }

    /** The host's answer to an upload: the consent sheet, unless it hashed a different package. */
    private suspend fun staged(picked: PickedPluginPackage, result: PluginCatalogResult<PluginInstallRequest>) {
        when (result) {
            is PluginCatalogResult.Refused -> send(Failed(result.reason))
            is PluginCatalogResult.Ok -> if (result.value.sha256 == picked.sha256) {
                pickedBytes = null
                send(PluginsPageEvent.Staged(result.value))
            } else {
                catalog.cancelInstall(result.value.id)
                send(InstallRefused("Install refused: the host received a package with a different sha256 than ${picked.fileName}."))
            }
        }
    }

    override fun decide(item: PluginConsentItem, allow: Boolean) = send(PluginsPageEvent.Decided(item, allow))

    override fun installConsented() {
        val sheet = stateFlow.value.consent ?: return
        if (stateFlow.value.busy) return
        val id = sheet.request.id
        when (val verdict = PluginsPageModel.verdict(sheet)) {
            PluginConsentVerdict.Undecided -> Unit
            is PluginConsentVerdict.Refused -> {
                send(InstallRefused(verdict.reason))
                scope.launch { call { catalog.cancelInstall(id) } }
            }
            is PluginConsentVerdict.Granted -> {
                send(Started)
                scope.launch { send(call { catalog.consent(id, verdict.grants) }.fold(ConsentClosed, ::InstallRefused)) }
            }
        }
    }

    override fun cancelConsent() {
        val sheet = stateFlow.value.consent ?: return
        send(ConsentClosed)
        scope.launch { call { catalog.cancelInstall(sheet.request.id) } }
    }

    override fun setEnabled(id: PluginId, on: Boolean) = launchCall { if (on) catalog.enable(id) else catalog.disable(id) }

    override fun askRemove(id: PluginId?) = send(PluginsPageEvent.RemoveAsked(id))

    override fun confirmRemove() {
        val id = stateFlow.value.pendingRemove ?: return
        launchCall { catalog.remove(id) }
    }

    override fun askDisableAll(open: Boolean) = send(PluginsPageEvent.DisableAllAsked(open))

    override fun confirmDisableAll() = launchCall { catalog.disableAll() }

    override fun checkHealth(id: PluginId) = launchCall { catalog.health(id) }

    override fun setLiveViews(on: Boolean) {
        liveViews?.set(on)
        send(PluginsPageEvent.LiveViews(on))
    }

    override fun dismissError() = send(PluginsPageEvent.ErrorDismissed)

    /** One catalog call at a time: the page waits, then shows the refusal if there is one. */
    private fun launchCall(block: suspend () -> PluginCatalogResult<*>) {
        if (stateFlow.value.busy) return
        send(Started)
        scope.launch { send(call(block).fold(Done, ::Failed)) }
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val MAX_PACKAGE_BYTES = 16 * MIB
        const val CALL_FAILED = "The plugin host did not answer."
    }

    /** [block]'s result, a thrown failure read as a refusal. */
    private suspend fun <T> call(block: suspend () -> PluginCatalogResult<T>): PluginCatalogResult<T> =
        runCatchingCancellable { block() }.getOrElse { PluginCatalogResult.Refused(it.message ?: CALL_FAILED) }
}

private fun PluginCatalogResult<*>.fold(ok: PluginsPageEvent, refused: (String) -> PluginsPageEvent): PluginsPageEvent = when (this) {
    is PluginCatalogResult.Ok -> ok
    is PluginCatalogResult.Refused -> refused(reason)
}
