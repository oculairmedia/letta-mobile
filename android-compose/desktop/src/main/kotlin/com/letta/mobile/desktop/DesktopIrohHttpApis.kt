package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.repository.iroh.IrohSlashCommandApi
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi

/** Management bindings for the memory panel and the graph's shared slash-command adapter. */
internal class DesktopHttpApis(
    val blockApi: DesktopBlockApi?,
    val slashCommandApi: SlashCommandsApi?,
)

private fun createDesktopHttpApis(
    irohAgentDirectory: IrohAdminRpcAgentDirectory?, slashCommands: SlashCommandsApi?,
): DesktopHttpApis =
    DesktopHttpApis(
        blockApi = irohAgentDirectory?.let(::DesktopIrohBlockApi),
        slashCommandApi = slashCommands ?: irohAgentDirectory?.let(::IrohSlashCommandApi),
    )

@Composable
internal fun rememberDesktopHttpApis(
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    slashCommands: SlashCommandsApi,
): DesktopHttpApis {
    val directory = irohAgentDirectory.takeIf { irohMode }
    return remember(directory, slashCommands) { desktopHttpApisFor(irohMode, directory, slashCommands) }
}

/** Standalone probes use the Iroh directory when no graph adapter is supplied. */
internal fun desktopHttpApisFor(
    irohMode: Boolean, irohAgentDirectory: IrohAdminRpcAgentDirectory?, slashCommands: SlashCommandsApi? = null,
): DesktopHttpApis = createDesktopHttpApis(irohAgentDirectory.takeIf { irohMode }, slashCommands)
