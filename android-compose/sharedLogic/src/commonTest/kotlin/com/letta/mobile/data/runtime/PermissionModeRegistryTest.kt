package com.letta.mobile.data.runtime

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Standard
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Strict
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Unrestricted
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.13 (F13): the default stays Unrestricted; a runtime's mode is the one it really runs. */
class PermissionModeRegistryTest {
    private val runtime = AppServerRuntimeScope("agent", "conv")
    private val elsewhere = AppServerRuntimeScope("agent", "elsewhere")
    private val otherConversation = AppServerRuntimeScope("agent", "other-conv")

    private fun registry(settings: PermissionModeSettings = PermissionModeSettings(MapSettingsStore())) = settings.modes

    @Test
    fun anInstallThatNeverTouchedTheSettingRunsUnrestricted() {
        val settings = PermissionModeSettings(MapSettingsStore())

        assertEquals(Unrestricted, settings.defaultMode.value)
        assertEquals(Unrestricted, RuntimePermissionDefaults.DEFAULT_MODE)
        assertEquals(Unrestricted, settings.modes.modeFor(runtime))
    }

    @Test
    fun anUnreadableStoredValueFallsBackToTheProductDefault() {
        val store = MapSettingsStore().apply { putString(PermissionModeSettings.KEY, "not-a-mode") }

        assertEquals(Unrestricted, PermissionModeSettings(store).defaultMode.value)
    }

    @Test
    fun theDefaultSurvivesARestartAndNotYetStartedRuntimesFollowIt() {
        val store = MapSettingsStore()
        PermissionModeSettings(store).setDefaultMode(Standard)

        val restarted = PermissionModeSettings(store)
        assertEquals(Standard, restarted.defaultMode.value)
        assertEquals(Standard, restarted.modes.modeFor(runtime))

        restarted.setDefaultMode(AppServerPermissionMode.AcceptEdits)
        assertEquals(AppServerPermissionMode.AcceptEdits, restarted.modes.modeFor(otherConversation))
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
    fun aFailedWriteLeavesTheDefaultUnchangedInsteadOfClaimingOne() {
        val settings = PermissionModeSettings(FailingStore())

        assertFalse(settings.setDefaultMode(Strict))

        assertEquals(Unrestricted, settings.defaultMode.value)
    }

    @Test
    fun changingTheDefaultNeverReachesARuntimeThatAlreadyStarted() = runTest {
        val settings = PermissionModeSettings(MapSettingsStore())
        val modes = settings.modes
        // The engine reports the mode its runtime_start carried.
        modes.observed(runtime, Unrestricted)

        settings.setDefaultMode(Strict)

        assertEquals(Unrestricted, modes.modeFor(runtime), "what the server runs is not what the default now says")
        assertEquals(PermissionModeState(Unrestricted), modes.observe(runtime).first())
        assertEquals(Strict, modes.modeFor(elsewhere), "a runtime that has not started follows the default")
        assertEquals(PermissionModeState(Strict), modes.observe(elsewhere).first())
    }

    @Test
    fun theConfirmedModeMovesOnlyAfterTheServerEchoesIt() = runTest {
        val modes = registry()
        modes.observed(runtime, Unrestricted)
        var whileApplying: PermissionModeState? = null

        val confirmed = modes.change(runtime, Standard) {
            whileApplying = modes.observe(runtime).first()
            ModeChangeResult.Confirmed
        }

        assertTrue(confirmed)
        assertEquals(PermissionModeState(Unrestricted, pending = Standard), whileApplying, "the chip shows the request as pending")
        assertEquals(PermissionModeState(Standard), modes.observe(runtime).first())
        assertEquals(Unrestricted, modes.modeFor(elsewhere))
    }

    @Test
    fun aStricterRequestGovernsAtOnceButALooserOneOnlyOnceConfirmed() = runTest {
        val modes = registry()
        modes.observed(runtime, Unrestricted)
        var tightening: AppServerPermissionMode? = null
        modes.change(runtime, Strict) {
            tightening = modes.modeFor(runtime)
            ModeChangeResult.Confirmed
        }
        assertEquals(Strict, tightening, "Unrestricted -> Strict is enforced while it is still pending")

        var loosening: AppServerPermissionMode? = null
        modes.change(runtime, Unrestricted) {
            loosening = modes.modeFor(runtime)
            ModeChangeResult.Confirmed
        }
        assertEquals(Strict, loosening, "Strict -> Unrestricted does not relax before the server confirms")
        assertEquals(Unrestricted, modes.modeFor(runtime))
    }

    @Test
    fun anUnconfirmedLoosenedChangeIsShownAsUnconfirmedNotAsTheOldMode() = runTest {
        val modes = registry()
        modes.observed(runtime, Strict)

        // The server applied it but the echo was lost (or the send failed): the outcome is unknown.
        val confirmed = modes.change(runtime, Unrestricted) { ModeChangeResult.Unconfirmed }

        assertFalse(confirmed)
        val state = modes.observe(runtime).first()
        assertEquals(Unrestricted, state.unconfirmed)
        assertNull(state.pending)
        assertTrue(state.failed)
        assertEquals(Strict, modes.modeFor(runtime), "until the server says, the stricter mode is enforced")
    }

    @Test
    fun aFailingApplyIsAnUnconfirmedChangeNotACrash() = runTest {
        val modes = registry()
        modes.observed(runtime, Unrestricted)

        val confirmed = modes.change(runtime, Standard) { error("socket closed") }

        assertFalse(confirmed)
        assertEquals(Standard, modes.observe(runtime).first().unconfirmed)
        assertEquals(Standard, modes.modeFor(runtime), "a tightening that may have applied is enforced")
    }

    @Test
    fun theServerReportingAModeSettlesAnUnconfirmedChange() = runTest {
        val modes = registry()
        modes.observed(runtime, Strict)
        modes.change(runtime, Unrestricted) { ModeChangeResult.Unconfirmed }

        modes.observed(runtime, Unrestricted)

        assertEquals(PermissionModeState(Unrestricted), modes.observe(runtime).first())
        assertEquals(Unrestricted, modes.modeFor(runtime))
    }

    @Test
    fun aModeTheServerReportsOnItsOwnIsAdopted() {
        val modes = registry()

        modes.observed(runtime, Strict)

        assertEquals(Strict, modes.modeFor(runtime))
    }

    @Test
    fun aChoiceForARuntimeThatHasNotStartedIsNotConfirmedByAnythingUntilItStarts() = runTest {
        val modes = registry()

        val confirmed = modes.change(runtime, Strict) { ModeChangeResult.AppliesOnStart }

        assertFalse(confirmed, "nothing was sent, so nothing confirmed it")
        assertEquals(PermissionModeState(Strict, appliesOnStart = true), modes.observe(runtime).first())
        assertEquals(Strict, modes.modeFor(runtime), "the runtime_start that creates it carries the choice")

        modes.observed(runtime, Strict) // the post-runtime_start device status
        assertEquals(PermissionModeState(Strict), modes.observe(runtime).first())
    }

    @Test
    fun aChoiceSurvivesARebuiltRegistryAndNeverFallsBackToAnUnrestrictedDefault() = runTest {
        val store = MapSettingsStore()
        PermissionModeSettings(store).modes.change(runtime, Strict) { ModeChangeResult.AppliesOnStart }

        // A rebuilt gateway or an app restart: new settings, new registry, same store.
        val rebuilt = PermissionModeSettings(store)
        assertEquals(Unrestricted, rebuilt.defaultMode.value)
        assertEquals(Strict, rebuilt.modes.modeFor(runtime), "the runtime_start must not downgrade it to Unrestricted")
        assertEquals(PermissionModeState(Strict, appliesOnStart = true), rebuilt.modes.observe(runtime).first())
        assertEquals(Unrestricted, rebuilt.modes.modeFor(elsewhere))
    }

    @Test
    fun aStricterModeTheServerRunsIsRememberedAcrossARestart() {
        val store = MapSettingsStore()
        PermissionModeSettings(store).modes.observed(runtime, Standard)

        assertEquals(Standard, PermissionModeSettings(store).modes.modeFor(runtime))
    }

    @Test
    fun overlappingChangesLetOnlyTheLatestSettleTheEntry() = runTest {
        val modes = registry()
        modes.observed(runtime, Unrestricted)
        val firstGate = CompletableDeferred<ModeChangeResult>()
        val first = async { modes.change(runtime, Standard) { firstGate.await() } }
        runCurrent()

        assertTrue(modes.change(runtime, Strict) { ModeChangeResult.Confirmed })
        firstGate.complete(ModeChangeResult.Confirmed)
        first.await()

        assertEquals(PermissionModeState(Strict), modes.observe(runtime).first(), "the first to finish must not clear or overwrite the other")
    }

    @Test
    fun forgettingARuntimeDropsItsEntryAndItsStoredChoice() = runTest {
        val store = MapSettingsStore()
        val modes = PermissionModeSettings(store).modes
        modes.change(runtime, Strict) { ModeChangeResult.AppliesOnStart }

        modes.forget(runtime)

        assertEquals(Unrestricted, modes.modeFor(runtime))
        assertEquals(Unrestricted, PermissionModeSettings(store).modes.modeFor(runtime))
    }

    private class FailingStore : SecureSettingsStore {
        override fun getString(key: String, defaultValue: String?): String? = defaultValue
        override fun putString(key: String, value: String) = error("disk full")
        override fun remove(key: String) = Unit
        override fun clear() = Unit
    }
}
