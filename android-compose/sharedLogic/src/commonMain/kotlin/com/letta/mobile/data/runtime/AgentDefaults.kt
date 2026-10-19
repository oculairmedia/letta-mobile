package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.storage.JsonPreference
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.workspace.directory.DefaultWorkingDirectoryStore
import com.letta.mobile.data.workspace.directory.DevicePath
import com.letta.mobile.data.workspace.directory.devicePathOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 *  (G96): per-agent defaults kept on the client, next to attention prefs in
 * the host's [SecureSettingsStore]. [cwd] is the folder a new conversation for the agent opens in.
 */
@Serializable
data class AgentDefaults(val cwd: String? = null)

/** Read and change [AgentDefaults] for one agent. Platform-neutral; hosts only supply the storage. */
interface AgentDefaultsRepository : DefaultWorkingDirectoryStore {
    val entries: StateFlow<Map<String, AgentDefaults>>

    fun defaults(agentId: AgentId): AgentDefaults

    override fun setCwd(agentId: AgentId, cwd: String?)
}

/** [AgentDefaults.cwd] for [agentId], or null when that agent has none. */
fun AgentDefaultsRepository.cwd(agentId: AgentId): String? = defaults(agentId).cwd

/**
 * [AgentDefaultsRepository] kept in the app's settings store, so a default cwd survives a restart.
 * Android binds SharedPreferences and desktop its own; neither holds a copy of this logic.
 */
class StoredAgentDefaults(
    store: SecureSettingsStore,
    key: String = KEY,
) : AgentDefaultsRepository {
    private val preference = JsonPreference(store, key, StoredTable.serializer())
    private val _entries = MutableStateFlow(preference.load()?.agents.orEmpty())
    override val entries: StateFlow<Map<String, AgentDefaults>> = _entries.asStateFlow()

    override fun defaults(agentId: AgentId): AgentDefaults =
        _entries.value[agentId.value] ?: AgentDefaults()

    override fun setCwd(agentId: AgentId, cwd: String?) {
        val path = absoluteCwdOrNull(cwd)
        if (agentId.value.isBlank() || rejectedRelativeCwd(cwd, path)) return
        val current = _entries.value
        val agents = if (path == null) current - agentId.value else current + (agentId.value to AgentDefaults(path))
        _entries.value = agents
        preference.save(StoredTable(agents))
    }

    @Serializable
    private data class StoredTable(val agents: Map<String, AgentDefaults> = emptyMap())

    companion object {
        const val KEY = "agent.defaults"
    }
}

/** Absolute [cwd], normalized, or null when blank or not a full path. */
internal fun absoluteCwdOrNull(cwd: String?): String? =
    devicePathOrNull(cwd)?.takeIf(DevicePath::isAbsolute)?.normalized?.value

/** True when the user typed a path that is not an absolute directory. */
private fun rejectedRelativeCwd(cwd: String?, path: String?): Boolean =
    !cwd.isNullOrBlank() && path == null
