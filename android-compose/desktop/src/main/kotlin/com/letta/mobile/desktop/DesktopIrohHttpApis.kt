package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.desktop.commands.DesktopIrohSlashCommandApi
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi

/**
 * The management APIs behind the desktop's memory and slash-command panels.
 *
 * letta-mobile-ckmub.4: these used to fall back to a Letta REST server for non-Iroh backends. That
 * backend is gone, so today they are served over an Iroh host's `admin_rpc` relay only; on the
 * bundled runtime or a direct App Server they are null and the panels say so. Skills and schedules
 * are the shared pages' (letta-mobile-ckmub.17.5), over the session graph. The name is kept until
 * the shared pages replace the panels.
 */
internal class DesktopHttpApis(
    val blockApi: DesktopBlockApi?,
    val slashCommandApi: SlashCommandsApi?,
)

private fun createDesktopHttpApis(irohAgentDirectory: IrohAdminRpcAgentDirectory?): DesktopHttpApis =
    DesktopHttpApis(
        blockApi = irohAgentDirectory?.let(::DesktopIrohBlockApi),
        slashCommandApi = irohAgentDirectory?.let(::DesktopIrohSlashCommandApi),
    )

internal fun loadArchivedConversationIds(store: com.letta.mobile.data.storage.SecureSettingsStore): Set<String> =
    store.getString(ARCHIVED_CONVERSATION_IDS_KEY)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

internal fun persistArchivedConversationIds(
    store: com.letta.mobile.data.storage.SecureSettingsStore,
    ids: Set<String>,
) {
    store.putString(ARCHIVED_CONVERSATION_IDS_KEY, ids.joinToString(","))
}

@Composable
internal fun rememberDesktopHttpApis(
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis {
    val directory = irohAgentDirectory.takeIf { irohMode }
    return remember(directory) { desktopHttpApisFor(irohMode, directory) }
}

/** The panels' APIs for a session: only an Iroh session's relays serve them (the matrix test's probe). */
internal fun desktopHttpApisFor(irohMode: Boolean, irohAgentDirectory: IrohAdminRpcAgentDirectory?): DesktopHttpApis =
    createDesktopHttpApis(irohAgentDirectory.takeIf { irohMode })
