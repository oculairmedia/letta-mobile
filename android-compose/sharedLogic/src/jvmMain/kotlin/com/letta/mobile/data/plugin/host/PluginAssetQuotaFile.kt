package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path

/**
 * Where [PluginAssetQuota] keeps each plugin's asset charges of the current window
 * `<pluginsDir>/asset-quota.json`, beside the registry, replaced
 * atomically on every write ([AtomicJsonFile]). A file that does not read or decode is reported
 * ([PluginHostEvent.QUOTA_STORE_FAILED]) and treated as empty; a write that fails is reported and the
 * in-memory window keeps counting, so storage trouble never fails an upload.
 */
class PluginAssetQuotaFile(val path: Path) {
    @Serializable
    private data class Saved(val version: Int = VERSION, val plugins: Map<String, List<WindowTake>> = emptyMap())

    internal fun load(): Map<String, List<WindowTake>> = try {
        AtomicJsonFile.readOrNull(path)?.let { LettaJson.persisted.decodeFromString(Saved.serializer(), it).plugins }.orEmpty()
    } catch (e: SerializationException) {
        emptyMap<String, List<WindowTake>>().also { report("load", e) }
    } catch (e: IOException) {
        emptyMap<String, List<WindowTake>>().also { report("load", e) }
    }

    internal fun save(usage: Map<String, List<WindowTake>>) {
        try {
            AtomicJsonFile.write(path, LettaJson.persisted.encodeToString(Saved.serializer(), Saved(plugins = usage)))
        } catch (e: IOException) {
            report("save", e)
        }
    }

    private fun report(step: String, e: Exception) =
        PluginHostEvents.record(PluginHostEvent.QUOTA_STORE_FAILED, PluginHostEvents.ALL, "step" to step, "error" to e.message.orEmpty())

    companion object {
        const val FILE: String = "asset-quota.json"
        private const val VERSION = 1
    }
}
