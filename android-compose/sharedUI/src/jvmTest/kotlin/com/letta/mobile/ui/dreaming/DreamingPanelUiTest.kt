@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.dreaming

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.dreaming.DreamActivity
import com.letta.mobile.data.dreaming.DreamCommitKind
import com.letta.mobile.data.dreaming.DreamHistoryState
import com.letta.mobile.data.dreaming.DreamRun
import com.letta.mobile.data.dreaming.DreamRunNowState
import com.letta.mobile.data.dreaming.DreamRunStart
import com.letta.mobile.data.dreaming.DreamSettings
import com.letta.mobile.data.dreaming.DreamSettingsChange
import com.letta.mobile.data.dreaming.DreamSettingsState
import com.letta.mobile.data.dreaming.DreamStartStatus
import com.letta.mobile.data.dreaming.DreamTrigger
import com.letta.mobile.data.dreaming.DreamUnsupportedReason
import com.letta.mobile.data.dreaming.DreamingActions
import com.letta.mobile.data.dreaming.DreamingState
import com.letta.mobile.data.model.SubagentKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-iczm7: the shared Dreaming panel over fixed states. */
class DreamingPanelUiTest {
    private class RecordingActions : DreamingActions {
        val saved = mutableListOf<DreamSettingsChange>()
        val runs = mutableListOf<String?>()
        val opened = mutableListOf<DreamRun>()

        override fun refresh() = Unit
        override fun save(change: DreamSettingsChange) { saved += change }
        override fun runNow(instruction: String?) { runs += instruction }
        override fun openRun(run: DreamRun) { opened += run }
        override fun closeRun() = Unit
    }

    private val run = DreamRun("abc1234def", "Learned the build commands", "2026-10-11T10:00:00Z", DreamCommitKind.Change, "Reflection Subagent")

    private val loaded = DreamingState(
        settings = DreamSettingsState(settings = DreamSettings(DreamTrigger.StepCount, 25)),
        history = DreamHistoryState.Loaded(listOf(run)),
    )

    @Test
    fun turningDreamingOffSavesTheOffTriggerAndKeepsTheStepCount() = runComposeUiTest {
        val actions = RecordingActions()
        setContent { MaterialTheme { DreamingPanel(loaded, actions) } }
        onNodeWithTag(DreamingTags.AUTOMATIC).assertIsOn().performClick()
        assertEquals(DreamSettings(DreamTrigger.Off, 25), actions.saved.single().settings)
    }

    @Test
    fun stepsStepperSavesTheNewCount() = runComposeUiTest {
        val actions = RecordingActions()
        setContent { MaterialTheme { DreamingPanel(loaded, actions) } }
        onNodeWithTag(DreamingTags.STEPS_UP).performScrollTo().performClick()
        assertEquals(30, actions.saved.single().settings.stepCount)
    }

    @Test
    fun dreamNowSendsTheFocusAndShowsLettaCodesAnswer() = runComposeUiTest {
        val actions = RecordingActions()
        val answered = loaded.copy(runNow = DreamRunNowState(lastStart = DreamRunStart(DreamStartStatus.NothingNew, null)))
        setContent { MaterialTheme { DreamingPanel(answered, actions) } }
        onNodeWithTag(DreamingTags.RUN_NOW).performScrollTo().performClick()
        assertEquals(listOf<String?>(null), actions.runs)
        onNodeWithText(DreamingStrings.start(DreamStartStatus.NothingNew, null)).assertExists()
    }

    @Test
    fun aDreamInFlightShowsProgressAndHistoryRowsOpen() = runComposeUiTest {
        val actions = RecordingActions()
        val dreaming = loaded.copy(live = listOf(DreamActivity("t1", "Reflect on recent conversations", SubagentKind.Reflection, null)))
        setContent { MaterialTheme { DreamingPanel(dreaming, actions) } }
        onNodeWithTag(DreamingTags.PROGRESS).assertExists()
        onNodeWithText("Dreaming: Reflect on recent conversations").assertExists()
        onNodeWithTag(DreamingTags.run(run.sha)).performScrollTo().performClick()
        assertEquals(listOf(run), actions.opened)
    }

    @Test
    fun anUnsupportedHostSaysWhyInsteadOfShowingControls() = runComposeUiTest {
        val unsupported = DreamingState(settings = DreamSettingsState(unsupported = DreamUnsupportedReason.HostNeedsUpdate))
        setContent { MaterialTheme { DreamingPanel(unsupported, RecordingActions()) } }
        onNodeWithTag(DreamingTags.UNSUPPORTED).assertExists()
        onNodeWithTag(DreamingTags.AUTOMATIC).assertDoesNotExist()
    }

    @Test
    fun theWindowsGateOffersTheSwitchOnlyWhileDreamsAreAutomatic() = runComposeUiTest {
        var allowed = 0
        val notes = DreamingHostNotes(windowsAutoBlocked = true, onAllowWindowsAuto = { allowed++ })
        setContent { MaterialTheme { DreamingPanel(loaded, RecordingActions(), notes = notes) } }
        onNodeWithTag(DreamingTags.WINDOWS_ALLOW).performClick()
        assertEquals(1, allowed)
    }

    @Test
    fun theNotifySwitchAppearsOnlyWhereThePlatformDelivers() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        val notes = DreamingHostNotes(notifyOnFinish = false, onNotifyChange = { changes += it })
        setContent { MaterialTheme { DreamingPanel(loaded, RecordingActions(), notes = notes) } }
        onNodeWithTag(DreamingTags.NOTIFY).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), changes)
    }
}
