package com.letta.mobile.desktop.commands

import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohSlashCommandApi

class DesktopIrohSlashCommandApi(
    directory: IrohAdminRpcAgentDirectory,
) : SlashCommandsApi by IrohSlashCommandApi(directory)
