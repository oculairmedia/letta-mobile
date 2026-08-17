package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BackendConfigPolicyTest {
    @Test
    fun normalizeTrimsUrlTokenAndGeneratesStableId() {
        val fallback = LettaConfig(
            id = "fallback",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        val normalized = BackendConfigPolicy.normalize(
            config = fallback.copy(
                id = " ",
                serverUrl = " https://api.letta.com/ ",
                accessToken = " token ",
            ),
            fallback = fallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals(BackendConfigPolicy.stableConfigId("desktop", "https://api.letta.com/"), normalized.id)
        assertEquals("https://api.letta.com/", normalized.serverUrl)
        assertEquals("token", normalized.accessToken)
    }

    @Test
    fun normalizeUsesFallbackUrlAndDropsBlankToken() {
        val fallback = LettaConfig(
            id = "fallback",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        val normalized = BackendConfigPolicy.normalize(
            config = fallback.copy(id = "", serverUrl = "", accessToken = " "),
            fallback = fallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals(BackendConfigPolicy.stableConfigId("desktop", "http://localhost:8283"), normalized.id)
        assertEquals("http://localhost:8283", normalized.serverUrl)
        assertNull(normalized.accessToken)
    }

    @Test
    fun normalizeMigratesStaleIrohServerUrlOnLocalModeConfig() {
        val fallback = LettaConfig(
            id = "fallback",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "",
        )

        val normalized = BackendConfigPolicy.normalize(
            config = LettaConfig(
                id = "desktop-361c792e",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
            ),
            fallback = fallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals("", normalized.serverUrl)
        assertEquals(LettaConfig.Mode.LOCAL, normalized.mode)
    }

    @Test
    fun migrateStaleLocalServerUrlClearsIrohUrlOnlyForLocalMode() {
        val staleIroh = LettaConfig(
            id = "desktop-361c792e",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
        )

        val migrated = BackendConfigPolicy.migrateStaleLocalServerUrl(staleIroh)

        assertEquals("", migrated.serverUrl)
        assertEquals(LettaConfig.Mode.LOCAL, migrated.mode)
        // Same classification pinned as BackendKind's own KDoc promises: the
        // routing decision must never disagree with what this migration produces.
        assertEquals(BackendKind.LOCAL_RUNTIME, migrated.backendKind())
    }

    @Test
    fun migrateStaleLocalServerUrlIgnoresNonLocalModes() {
        val remoteConfig = LettaConfig(
            id = "remote",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://abc@host:4501",
        )

        val migrated = BackendConfigPolicy.migrateStaleLocalServerUrl(remoteConfig)

        assertEquals(remoteConfig.serverUrl, migrated.serverUrl)
    }

    @Test
    fun migrateStaleLocalServerUrlLeavesBlankAndPlaceholderLocalUrlsAlone() {
        val blank = LettaConfig(id = "local-blank", mode = LettaConfig.Mode.LOCAL, serverUrl = "")
        val placeholder = LettaConfig(
            id = "local-placeholder",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local-lettacode://device",
        )

        assertEquals(blank, BackendConfigPolicy.migrateStaleLocalServerUrl(blank))
        assertEquals(placeholder, BackendConfigPolicy.migrateStaleLocalServerUrl(placeholder))
    }

    @Test
    fun secureTokenStoreReadsTrimsClearsAndPublishesTokenState() = runTest {
        val configStore = FakeBackendConfigStore(
            LettaConfig(
                id = "config-1",
                mode = LettaConfig.Mode.CLOUD,
                serverUrl = "https://api.letta.com",
                accessToken = "old",
            ),
        )
        val tokenStore = BackendConfigSecureTokenStore(configStore)

        assertTrue(tokenStore.observeHasToken().first())
        assertEquals("old", tokenStore.loadToken())

        tokenStore.saveToken(" new ")

        assertEquals("new", configStore.activeConfig.value?.accessToken)

        tokenStore.clearToken()

        assertFalse(tokenStore.observeHasToken().first())
        assertNull(configStore.activeConfig.value?.accessToken)
    }

    @Test
    fun secureTokenStoreRejectsSavingTokenWithoutActiveConfig() = runTest {
        val tokenStore = BackendConfigSecureTokenStore(FakeBackendConfigStore(null))

        assertFailsWith<IllegalArgumentException> {
            tokenStore.saveToken("secret")
        }

        tokenStore.clearToken()
    }

    private class FakeBackendConfigStore(
        initialConfig: LettaConfig?,
    ) : BackendConfigStore {
        private val state = MutableStateFlow(initialConfig)
        override val activeConfig = state.asStateFlow()

        override suspend fun loadActiveConfig(): LettaConfig? = state.value

        override suspend fun saveActiveConfig(config: LettaConfig) {
            state.value = config
        }
    }
}
