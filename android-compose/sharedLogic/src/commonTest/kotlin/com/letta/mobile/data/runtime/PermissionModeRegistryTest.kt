package com.letta.mobile.data.runtime

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.13 (F13): the default stays Unrestricted; the machinery to change it is in place. */
class PermissionModeRegistryTest {
    private val runtime = AppServerRuntimeScope("agent", "conv")
    private val elsewhere = AppServerRuntimeScope("agent", "elsewhere")
    private val otherConversation = AppServerRuntimeScope("agent", "other-conv")

    @Test
    fun anInstallThatNeverTouchedTheSettingRunsUnrestricted() {
        val settings = PermissionModeSettings(MapSettingsStore())

        assertEquals(AppServerPermissionMode.Unrestricted, settings.defaultMode.value)
        assertEquals(AppServerPermissionMode.Unrestricted, RuntimePermissionDefaults.DEFAULT_MODE)
        assertEquals(
            AppServerPermissionMode.Unrestricted,
            PermissionModeRegistry(settings.defaultMode).modeFor(runtime),
        )
    }

    @Test
    fun anUnreadableStoredValueFallsBackToTheProductDefault() {
        val store = MapSettingsStore().apply { putString(PermissionModeSettings.KEY, "not-a-mode") }

        assertEquals(AppServerPermissionMode.Unrestricted, PermissionModeSettings(store).defaultMode.value)
    }

    @Test
    fun theDefaultSurvivesARestartAndEveryRuntimeFollowsIt() {
        val store = MapSettingsStore()
        val first = PermissionModeSettings(store)
        first.setDefaultMode(AppServerPermissionMode.Standard)

        val restarted = PermissionModeSettings(store)
        assertEquals(AppServerPermissionMode.Standard, restarted.defaultMode.value)
        val registry = PermissionModeRegistry(restarted.defaultMode)
        assertEquals(AppServerPermissionMode.Standard, registry.modeFor(runtime))

        restarted.setDefaultMode(AppServerPermissionMode.AcceptEdits)
        assertEquals(AppServerPermissionMode.AcceptEdits, registry.modeFor(otherConversation))
    }

    @Test
    fun everyModeRoundTripsThroughTheStore() {
        AppServerPermissionMode.entries.forEach { mode ->
            val store = MapSettingsStore()
            PermissionModeSettings(store).setDefaultMode(mode)
            assertEquals(mode, PermissionModeSettings(store).defaultMode.value)
        }
    }

    @Test
    fun theConfirmedModeMovesOnlyAfterTheServerEchoesIt() = runTest {
        val registry = PermissionModeRegistry(PermissionModeSettings(MapSettingsStore()).defaultMode)
        var whileApplying: PermissionModeState? = null
        var modeWhileApplying: AppServerPermissionMode? = null

        val confirmed = registry.change(runtime, AppServerPermissionMode.Standard) {
            whileApplying = registry.observe(runtime).first()
            modeWhileApplying = registry.modeFor(runtime)
            true
        }

        assertTrue(confirmed)
        assertEquals(
            PermissionModeState(AppServerPermissionMode.Unrestricted, pending = AppServerPermissionMode.Standard),
            whileApplying,
            "the chip shows the request as pending",
        )
        assertEquals(AppServerPermissionMode.Unrestricted, modeWhileApplying, "turns still run the confirmed mode")
        assertEquals(PermissionModeState(AppServerPermissionMode.Standard), registry.observe(runtime).first())
        assertEquals(AppServerPermissionMode.Unrestricted, registry.modeFor(elsewhere))
    }

    @Test
    fun aChangeTheServerDoesNotConfirmLeavesTheModeAndSaysSo() = runTest {
        val registry = PermissionModeRegistry(PermissionModeSettings(MapSettingsStore()).defaultMode)

        val confirmed = registry.change(runtime, AppServerPermissionMode.Strict) { false }

        assertFalse(confirmed)
        assertEquals(AppServerPermissionMode.Unrestricted, registry.modeFor(runtime))
        val state = registry.observe(runtime).first()
        assertNull(state.pending)
        assertTrue(state.failed)
    }

    @Test
    fun aFailingApplyIsAnUnconfirmedChangeNotACrash() = runTest {
        val registry = PermissionModeRegistry(PermissionModeSettings(MapSettingsStore()).defaultMode)

        val confirmed = registry.change(runtime, AppServerPermissionMode.Standard) { error("socket closed") }

        assertFalse(confirmed)
        assertEquals(AppServerPermissionMode.Unrestricted, registry.modeFor(runtime))
    }

    @Test
    fun aModeTheServerReportsOnItsOwnIsAdopted() {
        val registry = PermissionModeRegistry(PermissionModeSettings(MapSettingsStore()).defaultMode)

        registry.observed(runtime, AppServerPermissionMode.Strict)

        assertEquals(AppServerPermissionMode.Strict, registry.modeFor(runtime))
    }
}
