package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.desktop.runtime.DesktopLocalBackendDirectorySettings
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.security.DesktopSecretVaults
import com.letta.mobile.desktop.security.EncryptingSecureSettingsStore

/**
 * Config store, session graph bindings, and bootstrap state for the desktop shell.
 * [irohAgentDirectorySlot] / [channelTransportSlot] are updated from
 * [LettaDesktopApp] once the live Iroh/WS transport is available; a transport
 * change rebuilds the session graph so Cron/SelfTodo/Subagent and Iroh admin
 * reads bind to the real channel.
 */
internal data class DesktopConfigBootstrap(
    val secureSettingsStore: com.letta.mobile.data.storage.SecureSettingsStore,
    val dataBindings: DesktopDataBindings,
    val activeConfig: LettaConfig,
    val bootstrapState: DesktopBootstrapState,
    val applyConfig: (LettaConfig) -> Unit,
    val irohAgentDirectorySlot: MutableState<IrohAdminRpcAgentDirectory?>,
    val channelTransportSlot: MutableState<IChannelTransport?>,
    val publishChannelTransport: (IChannelTransport?) -> Unit,
)

/** The vault-encrypted settings, read once per process by whichever thread asks first. */
internal val desktopSecureSettingsStore: EncryptingSecureSettingsStore by lazy {
    EncryptingSecureSettingsStore(
        delegate = DesktopFileSecureSettingsStore(),
        vault = DesktopSecretVaults.forCurrentOs(defaultDesktopStateDirectory()),
    ).also { store ->
        // Before the first local-runtime spawn, so a saved directory override applies to it.
        DesktopLocalBackendDirectorySettings.applyStoredOverride(store)
        // F05: stable instance id and the default working directory for the child.
        DesktopRuntimeLaunchSettings.applyStored(store)
    }
}

/** The backend config store over [desktopSecureSettingsStore]; constructing it reads the config. */
internal val desktopConfigStore: DesktopLettaConfigStore by lazy { DesktopLettaConfigStore(desktopSecureSettingsStore) }

@Composable
internal fun rememberDesktopConfigBootstrap(): DesktopConfigBootstrap {
    // Sensitive settings (access token) are vault-encrypted at rest: DPAPI on Windows,
    // keyfile-AES-GCM elsewhere. Read once per process, usually already off the EDT by
    // DesktopStartupPrewarm; the config store's construction read is the first active config.
    val secureSettingsStore = remember { desktopSecureSettingsStore }
    val configStore = remember(secureSettingsStore) { desktopConfigStore }
    var activeConfig by remember { mutableStateOf(configStore.activeConfig.value ?: configStore.load()) }
    val irohAgentDirectorySlot = remember { mutableStateOf<IrohAdminRpcAgentDirectory?>(null) }
    val channelTransportSlot = remember { mutableStateOf<IChannelTransport?>(null) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
            irohAgentDirectoryProvider = { irohAgentDirectorySlot.value },
            channelTransportProvider = { channelTransportSlot.value },
        )
    }
    var bootstrapState by remember(dataBindings) {
        mutableStateOf(defaultDesktopBootstrapState(dataBindings, activeConfig))
    }
    val applyConfig: (LettaConfig) -> Unit = remember(configStore, dataBindings) {
        { nextConfig ->
            configStore.save(nextConfig)
            activeConfig = configStore.load()
            dataBindings.sessionGraphProvider.rebuild()
            bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
        }
    }
    val publishChannelTransport: (IChannelTransport?) -> Unit = remember(dataBindings, activeConfig) {
        { transport ->
            if (channelTransportSlot.value !== transport) {
                channelTransportSlot.value = transport
                dataBindings.sessionGraphProvider.rebuild()
                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
            }
        }
    }
    return DesktopConfigBootstrap(
        secureSettingsStore = secureSettingsStore,
        dataBindings = dataBindings,
        activeConfig = activeConfig,
        bootstrapState = bootstrapState,
        applyConfig = applyConfig,
        irohAgentDirectorySlot = irohAgentDirectorySlot,
        channelTransportSlot = channelTransportSlot,
        publishChannelTransport = publishChannelTransport,
    )
}

/**
 * The chat controller's lifecycle. The library controllers' lifecycles and their following of the
 * focused agent are the shared shell's (letta-mobile-ckmub.17.5: rememberShellLibraryControllers).
 */
@Composable
internal fun DesktopChatControllerLifecycle(chatController: DesktopChatController) {
    LaunchedEffect(chatController) { chatController.start() }
    DisposableEffect(chatController) {
        onDispose { chatController.close() }
    }
}

/** Typed agent id for desktop library/panel operations. */
@JvmInline
internal value class DesktopAgentId(val value: String)

/** The focused agent's server slash commands for the composer palette. */
internal suspend fun loadAgentSlashCommands(
    api: SlashCommandsApi?,
    agentId: DesktopAgentId?,
): List<AgentSlashCommand> = if (api != null && agentId != null) {
    runCatching { api.listAgentSlashCommands(agentId.value) }.getOrDefault(emptyList())
} else {
    emptyList()
}
