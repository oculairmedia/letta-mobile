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
        val normalized = BackendConfigPolicy.normalize(
            config = defaultFallback.copy(
                id = " ",
                serverUrl = " https://api.letta.com/ ",
                accessToken = " token ",
            ),
            fallback = defaultFallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals(BackendConfigPolicy.stableConfigId("desktop", "https://api.letta.com/"), normalized.id)
        assertEquals("https://api.letta.com/", normalized.serverUrl)
        assertEquals("token", normalized.accessToken)
    }

    @Test
    fun normalizeUsesFallbackUrlAndDropsBlankToken() {
        val normalized = BackendConfigPolicy.normalize(
            config = defaultFallback.copy(id = "", serverUrl = "", accessToken = " "),
            fallback = defaultFallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals(BackendConfigPolicy.stableConfigId("desktop", "http://localhost:8283"), normalized.id)
        assertEquals("http://localhost:8283", normalized.serverUrl)
        assertNull(normalized.accessToken)
    }

    private fun sampleStaleIrohConfig(token: String? = null, id: String = "desktop-361c792e") = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = STALE_LOCAL_IROH_URL,
        accessToken = token,
    )

    @Test
    fun normalizeMigratesStaleIrohServerUrlOnLocalModeConfig() {
        val normalized = BackendConfigPolicy.normalize(
            config = sampleStaleIrohConfig(),
            fallback = localFallback,
            generatedIdPrefix = "desktop",
        )

        assertEquals("", normalized.serverUrl)
        assertEquals(LettaConfig.Mode.LOCAL, normalized.mode)
    }

    @Test
    fun migrateStaleLocalServerUrlClearsIrohUrlOnlyForLocalMode() {
        val migrated = BackendConfigPolicy.migrateStaleLocalServerUrl(sampleStaleIrohConfig())

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
    fun migrateStaleLocalServerUrlParksRemoteDetailsInsteadOfDiscardingThem() {
        val migrated = BackendConfigPolicy.migrateStaleLocalServerUrl(sampleStaleIrohConfig(token = "remote-token"))

        assertEquals("", migrated.serverUrl)
        assertEquals(STALE_LOCAL_IROH_URL, migrated.parkedServerUrl)
        assertEquals("remote-token", migrated.parkedAccessToken)
        // The 9v9nu regression must stay dead: parked fields are not read by
        // routing, so classification is unaffected by them.
        assertEquals(BackendKind.LOCAL_RUNTIME, migrated.backendKind())
    }

    @Test
    fun restoreParkedRemoteBackendFillsBlankRemoteServerUrlAndToken() {
        val parked = BackendConfigPolicy.migrateStaleLocalServerUrl(
            sampleStaleIrohConfig(token = "remote-token", id = "desktop-local"),
        )

        // Switching back to a remote mode with no serverUrl typed in yet.
        val switchedBack = parked.copy(mode = LettaConfig.Mode.SELF_HOSTED)
        val restored = BackendConfigPolicy.restoreParkedRemoteBackend(switchedBack)

        assertEquals(STALE_LOCAL_IROH_URL, restored.serverUrl)
        assertEquals("remote-token", restored.accessToken)
    }

    @Test
    fun restoreParkedRemoteBackendPreservesConfigsThatDoNotRequireRestoration() {
        val explicit = LettaConfig(
            id = "desktop-explicit",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "https://explicit.example.com",
            parkedServerUrl = STALE_LOCAL_IROH_URL,
            parkedAccessToken = "parked-token",
        )
        val local = LettaConfig(
            id = "desktop-local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "",
            parkedServerUrl = STALE_LOCAL_IROH_URL,
            parkedAccessToken = "parked-token",
        )

        assertEquals(explicit, BackendConfigPolicy.restoreParkedRemoteBackend(explicit))
        assertEquals(local, BackendConfigPolicy.restoreParkedRemoteBackend(local))
    }

    private fun normalizeConfig(config: LettaConfig, fallback: LettaConfig = defaultFallback): LettaConfig =
        BackendConfigPolicy.normalize(config = config, fallback = fallback, generatedIdPrefix = "desktop")

    @Test
    fun normalizeRoundTripsRemoteToLocalToRemoteWithNoReEntry() {
        val remote = normalizeConfig(
            sampleStaleIrohConfig(token = "remote-token", id = "").copy(mode = LettaConfig.Mode.SELF_HOSTED),
        )
        assertEquals(STALE_LOCAL_IROH_URL, remote.serverUrl)
        assertEquals(BackendKind.IROH, remote.backendKind())

        // User flips to Local without editing the URL/token fields.
        val switchedToLocal = normalizeConfig(
            config = remote.copy(mode = LettaConfig.Mode.LOCAL),
            fallback = localFallback,
        )
        assertEquals("", switchedToLocal.serverUrl)
        assertEquals(BackendKind.LOCAL_RUNTIME, switchedToLocal.backendKind())
        assertEquals(STALE_LOCAL_IROH_URL, switchedToLocal.parkedServerUrl)
        assertEquals("remote-token", switchedToLocal.parkedAccessToken)

        // User flips back to a remote mode with a blank serverUrl field.
        val switchedBackToRemote = normalizeConfig(
            config = switchedToLocal.copy(mode = LettaConfig.Mode.SELF_HOSTED),
        )
        assertEquals(STALE_LOCAL_IROH_URL, switchedBackToRemote.serverUrl)
        assertEquals("remote-token", switchedBackToRemote.accessToken)
        assertEquals(BackendKind.IROH, switchedBackToRemote.backendKind())

        // And back to Local again — still parked, still not bound as Iroh.
        val switchedToLocalAgain = normalizeConfig(
            config = switchedBackToRemote.copy(mode = LettaConfig.Mode.LOCAL),
            fallback = localFallback,
        )
        assertEquals("", switchedToLocalAgain.serverUrl)
        assertEquals(BackendKind.LOCAL_RUNTIME, switchedToLocalAgain.backendKind())

        val switchedBackToRemoteAgain = normalizeConfig(
            config = switchedToLocalAgain.copy(mode = LettaConfig.Mode.SELF_HOSTED),
        )
        assertEquals(STALE_LOCAL_IROH_URL, switchedBackToRemoteAgain.serverUrl)
        assertEquals("remote-token", switchedBackToRemoteAgain.accessToken)
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

    private companion object {
        const val STALE_LOCAL_IROH_URL =
            "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"

        val defaultFallback = LettaConfig(
            id = "fallback",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        val localFallback = LettaConfig(
            id = "fallback",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "",
        )
    }
}
