package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.commands.SlashCommandApi
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.skills.SkillApi
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.commands.DesktopIrohSlashCommandApi
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopHttpBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi
import com.letta.mobile.desktop.skills.DesktopIrohSkillsApi

/**
 * HTTP-only management APIs — an iroh:// backend has no HTTP base URL, so
 * their panels degrade to empty instead of dialing iroh:// as HTTP.
 */
internal class DesktopHttpApis(
    val blockApi: DesktopBlockApi?,
    val cronApi: CronApi?,
    val skillApi: SkillsApi?,
    val slashCommandApi: SlashCommandsApi?,
)

private data class DesktopApiBackend(
    val irohMode: Boolean,
    val irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    val httpConfig: LettaConfig?,
)

private fun <T> createDesktopBackendApi(
    backend: DesktopApiBackend,
    irohFactory: (IrohAdminRpcAgentDirectory) -> T,
    httpFactory: (LettaConfig) -> T,
): T? = when {
    backend.irohMode -> backend.irohAgentDirectory?.let(irohFactory)
    else -> backend.httpConfig?.let(httpFactory)
}

private fun createDesktopHttpApis(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis {
    val httpConfig = activeConfig.takeIf { it.serverUrl.isNotBlank() && !irohMode }
    val backend = DesktopApiBackend(irohMode, irohAgentDirectory, httpConfig)
    val httpClient = createDesktopLettaHttpClient()
    return DesktopHttpApis(
        blockApi = createDesktopBackendApi(backend, ::DesktopIrohBlockApi, ::DesktopHttpBlockApi),
        cronApi = httpConfig?.let { CronApi(it, httpClient) },
        skillApi = createDesktopBackendApi(backend, ::DesktopIrohSkillsApi) { SkillApi(it, httpClient) },
        slashCommandApi = createDesktopBackendApi(backend, ::DesktopIrohSlashCommandApi) {
            SlashCommandApi(it, httpClient)
        },
    )
}

internal fun loadArchivedConversationIds(store: DesktopFileSecureSettingsStore): Set<String> =
    store.getString(ARCHIVED_CONVERSATION_IDS_KEY)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

internal fun persistArchivedConversationIds(
    store: DesktopFileSecureSettingsStore,
    ids: Set<String>,
) {
    store.putString(ARCHIVED_CONVERSATION_IDS_KEY, ids.joinToString(","))
}

@Composable
internal fun rememberDesktopHttpApis(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis =
    remember(activeConfig, irohMode, irohAgentDirectory) {
        createDesktopHttpApis(activeConfig, irohMode, irohAgentDirectory)
    }
