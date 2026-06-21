package com.letta.mobile.desktop.data

import com.letta.mobile.data.chat.runtime.BackendConfigPolicy
import com.letta.mobile.data.chat.runtime.BackendConfigStore
import com.letta.mobile.data.chat.runtime.SecureTokenStore
import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.SecureSettingsStore
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private const val DEFAULT_DESKTOP_LETTA_URL = "http://localhost:8283"

class DesktopInMemorySecureSettingsStore(
    initialValues: Map<String, String> = emptyMap(),
) : SecureSettingsStore {
    private val values = initialValues.toMutableMap()

    @Synchronized
    override fun getString(key: String, defaultValue: String?): String? =
        values[key] ?: defaultValue

    @Synchronized
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    override fun clear() {
        values.clear()
    }
}

/**
 * Interim JVM settings store for the Windows desktop preview.
 *
 * Values are kept out of logs and hidden in UI, but this should be replaced by
 * an OS keychain-backed implementation before handling high-sensitivity tokens.
 */
class DesktopFileSecureSettingsStore(
    private val path: Path = defaultSettingsPath(),
) : SecureSettingsStore {
    @Synchronized
    override fun getString(key: String, defaultValue: String?): String? =
        loadProperties().getProperty(key) ?: defaultValue

    @Synchronized
    override fun putString(key: String, value: String) {
        val properties = loadProperties()
        properties.setProperty(key, value)
        saveProperties(properties)
    }

    @Synchronized
    override fun remove(key: String) {
        val properties = loadProperties()
        properties.remove(key)
        saveProperties(properties)
    }

    @Synchronized
    override fun clear() {
        saveProperties(Properties())
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        if (Files.exists(path)) {
            Files.newInputStream(path).use<InputStream, Unit>(properties::load)
        }
        return properties
    }

    private fun saveProperties(properties: Properties) {
        path.parent?.let(Files::createDirectories)
        Files.newOutputStream(path).use<OutputStream, Unit> { output ->
            properties.store(output, "Letta Desktop settings")
        }
    }

    companion object {
        fun defaultSettingsPath(): Path =
            defaultDesktopStateDirectory().resolve("desktop-settings.properties")
    }
}

internal fun defaultDesktopStateDirectory(): Path =
    Path.of(
        System.getProperty("user.home"),
        ".letta-mobile",
    )

class DesktopLettaConfigStore(
    private val settingsStore: SecureSettingsStore,
) : BackendConfigStore, SecureTokenStore {
    private val activeConfigState = MutableStateFlow(readConfig())
    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()

    fun load(): LettaConfig {
        val config = readConfig()
        activeConfigState.value = config
        return config
    }

    override suspend fun loadActiveConfig(): LettaConfig = load()

    fun save(config: LettaConfig) {
        val normalized = config.normalized()
        settingsStore.putString(KEY_ID, normalized.id)
        settingsStore.putString(KEY_MODE, normalized.mode.name)
        settingsStore.putString(KEY_SERVER_URL, normalized.serverUrl)
        normalized.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { settingsStore.putString(KEY_ACCESS_TOKEN, it) }
            ?: settingsStore.remove(KEY_ACCESS_TOKEN)
        rememberBackend(normalized.serverUrl)
        activeConfigState.value = normalized
    }

    override suspend fun saveActiveConfig(config: LettaConfig) {
        save(config)
    }

    fun recentBackends(): List<String> =
        settingsStore.getString(KEY_RECENT_BACKENDS)
            ?.splitToSequence(RECENT_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?: emptyList()

    override suspend fun recentBackendUrls(): List<String> = recentBackends()

    override fun observeHasToken(): Flow<Boolean> =
        activeConfig
            .map { it?.accessToken?.isNotBlank() == true }
            .distinctUntilChanged()

    override suspend fun loadToken(): String? =
        load().accessToken?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun saveToken(token: String?) {
        save(load().copy(accessToken = token))
    }

    private fun readConfig(): LettaConfig {
        val fallback = defaultDesktopLettaConfig()
        return LettaConfig(
            id = settingsStore.getString(KEY_ID, fallback.id).orEmpty().ifBlank { fallback.id },
            mode = settingsStore.getString(KEY_MODE)
                ?.let { value -> runCatching { LettaConfig.Mode.valueOf(value) }.getOrNull() }
                ?: fallback.mode,
            serverUrl = settingsStore.getString(KEY_SERVER_URL, fallback.serverUrl).orEmpty().ifBlank {
                fallback.serverUrl
            },
            accessToken = settingsStore.getString(KEY_ACCESS_TOKEN)?.takeIf { it.isNotBlank() },
        )
    }

    private fun rememberBackend(serverUrl: String) {
        val next = (listOf(serverUrl) + recentBackends())
            .distinct()
            .take(5)
        settingsStore.putString(KEY_RECENT_BACKENDS, next.joinToString(RECENT_SEPARATOR))
    }

    private fun LettaConfig.normalized(): LettaConfig =
        BackendConfigPolicy.normalize(
            config = this,
            fallback = defaultDesktopLettaConfig(),
            generatedIdPrefix = "desktop",
        )

    private companion object {
        private const val KEY_ID = "letta.config.id"
        private const val KEY_MODE = "letta.config.mode"
        private const val KEY_SERVER_URL = "letta.config.serverUrl"
        private const val KEY_ACCESS_TOKEN = "letta.config.accessToken"
        private const val KEY_RECENT_BACKENDS = "letta.config.recentBackends"
        private const val RECENT_SEPARATOR = "\n"
    }
}

fun defaultDesktopLettaConfig(): LettaConfig =
    LettaConfig(
        id = "desktop-local",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = DEFAULT_DESKTOP_LETTA_URL,
        accessToken = null,
    )

fun desktopConfigIdFor(serverUrl: String): String =
    BackendConfigPolicy.stableConfigId("desktop", serverUrl)

class DesktopServerHealthRepository(
    initialStates: Map<String, ServerHealthState> = emptyMap(),
) : IServerHealthRepository {
    private val statesFlow = MutableStateFlow(initialStates)
    override val states: StateFlow<Map<String, ServerHealthState>> = statesFlow

    override suspend fun refreshAll() {
        statesFlow.update { current ->
            current.mapValues { (_, state) ->
                if (state == ServerHealthState.UNKNOWN) ServerHealthState.PROBING else state
            }
        }
    }

    fun setState(configId: String, state: ServerHealthState) {
        statesFlow.update { current -> current + (configId to state) }
    }
}

data class DesktopDataBindings(
    val secureSettingsStore: SecureSettingsStore,
    val healthRepository: IServerHealthRepository,
    val sessionGraphFactory: DesktopSessionGraphFactory,
    val sessionGraphProvider: DesktopSessionGraphProvider,
    val chatSessionGraphFactory: DesktopChatSessionGraphFactory,
)

fun createDefaultDesktopDataBindings(
    secureSettingsStore: SecureSettingsStore = DesktopFileSecureSettingsStore(),
    configProvider: () -> LettaConfig? = { null },
): DesktopDataBindings {
    val graphFactory = DesktopSessionGraphFactory(configProvider = configProvider)
    return DesktopDataBindings(
        secureSettingsStore = secureSettingsStore,
        healthRepository = DesktopServerHealthRepository(),
        sessionGraphFactory = graphFactory,
        sessionGraphProvider = DesktopSessionGraphProvider(graphFactory),
        chatSessionGraphFactory = defaultDesktopChatSessionGraphFactory(
            configProvider = configProvider,
            repositoryGraphFactory = graphFactory,
        ),
    )
}
