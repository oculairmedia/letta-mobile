package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.letta.mobile.runtime.BackendKind as RuntimeBackendKind

class SessionBackendSelectionTest {

    @Test
    fun localModeWinsOverLeftoverIrohUrl() {
        val config = LettaConfig(
            id = "local-stale-iroh",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "iroh://deadbeef@127.0.0.1:1122",
        )

        assertEquals(SessionBackendBinding.LocalRuntime, config.sessionBackendBinding())
        assertEquals(
            SessionBackendBinding.LocalRuntime,
            config.sessionBackendBinding(forceIroh = true),
        )
        assertTrue(config.sessionBackendBinding().bindsLocalRuntime())
        assertFalse(config.sessionBackendBinding().bindsIroh())
    }

    @Test
    fun selfHostedIrohUrlBindsIroh() {
        val config = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://node@127.0.0.1:1122",
        )

        assertEquals(SessionBackendBinding.Iroh, config.sessionBackendBinding())
        assertTrue(config.sessionBackendBinding().bindsIroh())
    }

    @Test
    fun forceIrohBindsIrohForNonLocalHttpConfig() {
        val config = LettaConfig(
            id = "http",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        assertEquals(SessionBackendBinding.RemoteHttpOrWs, config.sessionBackendBinding())
        assertEquals(SessionBackendBinding.Iroh, config.sessionBackendBinding(forceIroh = true))
    }

    @Test
    fun nullConfigBindsRemoteHttpOrWs() {
        assertEquals(SessionBackendBinding.RemoteHttpOrWs, null.sessionBackendBinding())
    }
}

class RemoteLettaBackendDescriptorsTest {

    @Test
    fun androidAndDesktopPrefixesStayDistinctWithSharedCapabilities() {
        val config = LettaConfig(
            id = "cfg-1",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://api.example.test",
        )

        val android = remoteLettaBackendDescriptor(config, ANDROID_REMOTE_LETTA_ID_PREFIX)
        val desktop = remoteLettaBackendDescriptor(config, DESKTOP_REMOTE_LETTA_ID_PREFIX)

        assertEquals("remote-letta:cfg-1", android.backendId.value)
        assertEquals("desktop-remote-letta:cfg-1", desktop.backendId.value)
        assertEquals(RuntimeBackendKind.RemoteLetta, android.kind)
        assertEquals(android.capabilities, desktop.capabilities)
        assertEquals("https://api.example.test", android.label)
    }

    @Test
    fun blankConfigFallsBackToDefaultIdAndLabel() {
        val descriptor = remoteLettaBackendDescriptor(
            config = null,
            idPrefix = ANDROID_REMOTE_LETTA_ID_PREFIX,
        )

        assertEquals("remote-letta:default", descriptor.backendId.value)
        assertEquals(DEFAULT_REMOTE_LETTA_URL, descriptor.label)
    }
}

class BackendConnectionKeyTest {

    @Test
    fun localModelHandleChangeProducesDistinctKey() {
        val base = LettaConfig(
            id = "same-id",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "",
            localModelPath = null,
            localModelHandle = null,
        )
        val withModel = base.copy(
            localModelPath = "/models/gemma.litertlm",
            localModelHandle = "google/gemma",
        )

        assertFalse(base.backendConnectionKey() == withModel.backendConnectionKey())
        assertEquals(base.backendConnectionKey(), base.copy(id = "other-id").backendConnectionKey())
    }
}
