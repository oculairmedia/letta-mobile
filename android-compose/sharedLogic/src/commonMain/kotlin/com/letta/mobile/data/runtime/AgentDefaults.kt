package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.storage.JsonPreference
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.workspace.directory.DevicePath
import com.letta.mobile.data.workspace.directory.devicePathOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * letta-mobile-k5nbq.9 (G96): per-agent defaults kept on the client, next to attention prefs in
 * the host's [SecureSettingsStore]. [cwd] is this bead; [permissionMode] is the slot uu3ol fills
 * so both defaults share one model and [RuntimePermissionDefaults] is no longer global-only.
 */
@Serializable
data class AgentDefaults(
    val cwd: String? = null,
    val permissionMode: AppServerPermissionMode? = null,
) {
    val isEmpty: Boolean get() = cwd == null && permissionMode == null

    /** The agent's permission mode when it has one, else the product default. */
    fun effectivePermissionMode(): AppServerPermissionMode =
        permissionMode ?: RuntimePermissionDefaults.DEFAULT_MODE
}

/** Read and change [AgentDefaults] for one agent. Platform-neutral; hosts only supply the storage. */
interface AgentDefaultsRepository {
    val entries: StateFlow<Map<String, AgentDefaults>>

    fun defaults(agentId: AgentId): AgentDefaults

    fun setCwd(agentId: AgentId, cwd: String?)

    fun setPermissionMode(agentId: AgentId, mode: AppServerPermissionMode?)
}

/** [AgentDefaults.cwd] for [agentId], or null when that agent has none. */
fun AgentDefaultsRepository.cwd(agentId: AgentId): String? = defaults(agentId).cwd

/**
 * [AgentDefaultsRepository] kept in the app's settings store, so a default cwd (and, with uu3ol, a
 * default permission mode) survives a restart. Android binds SharedPreferences and desktop its own;
 * neither holds a copy of this logic.
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
        if (cwd != null && cwd.isNotBlank() && path == null) return
        update(agentId) { it.copy(cwd = path) }
    }

    override fun setPermissionMode(agentId: AgentId, mode: AppServerPermissionMode?) {
        update(agentId) { it.copy(permissionMode = mode) }
    }

    private fun update(agentId: AgentId, change: (AgentDefaults) -> AgentDefaults) {
        if (agentId.value.isBlank()) return
        val next = (_entries.value[agentId.value] ?: AgentDefaults()).let(change)
        val agents = if (next.isEmpty) _entries.value - agentId.value else _entries.value + (agentId.value to next)
        _entries.value = agents
        persist(agents)
    }

    private fun persist(agents: Map<String, AgentDefaults>) {
        if (agents.isEmpty()) preference.save(StoredTable()) else preference.save(StoredTable(agents))
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
