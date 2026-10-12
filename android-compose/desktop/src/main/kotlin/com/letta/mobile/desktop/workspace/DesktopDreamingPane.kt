package com.letta.mobile.desktop.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.dreaming.AdminRpcDreamingRepository
import com.letta.mobile.data.dreaming.AppServerDreamingRepository
import com.letta.mobile.data.dreaming.DreamHistorySource
import com.letta.mobile.data.dreaming.DreamNotifyPrefs
import com.letta.mobile.data.dreaming.DreamTarget
import com.letta.mobile.data.dreaming.DreamingController
import com.letta.mobile.data.dreaming.DreamingRepository
import com.letta.mobile.data.dreaming.orElse
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchPreference
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.runtime.windowsBlocksAutoReflection
import com.letta.mobile.ui.dreaming.DreamingHostNotes
import com.letta.mobile.ui.dreaming.DreamingPanelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow

/**
 * letta-mobile-iczm7: the agent editor's Dreaming view. Desktop only binds: dreaming through the
 * Iroh host's relay when connected over iroh://, else the direct App Server (the bundled runtime
 * or an App Server URL); the dream log from the same MemFS route as the memory browser; live runs
 * from the subagent registry; and the Windows automatic-dream switch of the bundled runtime.
 */
@Composable
internal fun DesktopDreamingPane(
    agentId: String,
    scope: CoroutineScope,
    store: SecureSettingsStore,
    subagents: ISubagentRepository?,
    modifier: Modifier = Modifier,
) {
    val controller = remember(agentId, scope, subagents) {
        val sources = DesktopWorkspaceSources()
        DreamingController(
            repository = desktopDreamingRepository(),
            history = DreamHistorySource(sources.memfs()),
            scope = scope,
            activity = { id ->
                subagents?.activeSubagentsFlow(SubagentParentScope(id.value, ConversationCompactRpc.DEFAULT_CONVERSATION)) ?: emptyFlow()
            },
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    DreamingPanelHost(
        controller = controller,
        target = remember(agentId) { DreamTarget(AgentId(agentId)) },
        modifier = modifier,
        notes = rememberHostNotes(agentId, store),
    )
}

/** The Windows automatic-dream switch and the per-agent finish notification opt-in. */
@Composable
private fun rememberHostNotes(agentId: String, store: SecureSettingsStore): DreamingHostNotes {
    val prefs = remember(store) { DreamNotifyPrefs(store) }
    var notify by remember(agentId, prefs) { mutableStateOf(prefs.isEnabled(agentId)) }
    return rememberWindowsNotes(store).copy(
        notifyOnFinish = notify,
        onNotifyChange = { enabled ->
            prefs.setEnabled(agentId, enabled)
            notify = enabled
        },
    )
}

/** The bundled runtime on Windows starts no automatic dream unless the user allowed it here. */
@Composable
private fun rememberWindowsNotes(store: SecureSettingsStore): DreamingHostNotes {
    val health by DesktopLocalRuntimeHost.health.collectAsState()
    var allowed by remember(store) { mutableStateOf(DesktopRuntimeLaunchSettings.readWindowsAutoReflection(store)) }
    val bundledOnWindows = health.active && DesktopIrohWorkspaceRelay.transport == null &&
        windowsBlocksAutoReflection(System.getProperty("os.name"), System.getenv())
    return DreamingHostNotes(
        windowsAutoBlocked = bundledOnWindows,
        windowsAutoAllowed = allowed || DesktopRuntimeLaunchPreference.allowWindowsAutoReflection,
        onAllowWindowsAuto = {
            DesktopRuntimeLaunchSettings.saveWindowsAutoReflection(store, allowed = true)
            allowed = true
            DesktopLocalRuntimeHost.forceRestart()
        },
    )
}

/**
 * letta-code's settings and `/dream` through the Iroh relay, else the direct App Server. The relay
 * is read per request, so a session that connects or drops Iroh later is followed; without one
 * the relay answers "not connected" and the direct client serves.
 */
private fun desktopDreamingRepository(): DreamingRepository {
    val direct = AppServerDreamingRepository(client = { DesktopLocalAppServerClientRegistry.direct.currentOrNull() })
    return AdminRpcDreamingRepository(AdminRpcInvoker.overTransport { DesktopIrohWorkspaceRelay.transport }).orElse(direct)
}
