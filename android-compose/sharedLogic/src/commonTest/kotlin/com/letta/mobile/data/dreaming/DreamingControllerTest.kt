package com.letta.mobile.data.dreaming

import com.letta.mobile.data.memory.memfs.MemfsCommit
import com.letta.mobile.data.memory.memfs.MemfsCommitRef
import com.letta.mobile.data.memory.memfs.MemfsFileRef
import com.letta.mobile.data.memory.memfs.MemfsHistoryScope
import com.letta.mobile.data.memory.memfs.MemfsListing
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.memory.memfs.MemfsUpdate
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.reflection.ReflectionLastOutcome
import com.letta.mobile.data.model.reflection.ReflectionLastOutcomeStatus
import com.letta.mobile.data.model.reflection.ReflectionModelChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-iczm7: the Dreaming surface's state over fake letta-code answers. */
class DreamingControllerTest {
    private val agent = DreamTarget(AgentId("agent-1"))
    private val other = DreamTarget(AgentId("agent-2"))

    private class FakeRepository : DreamingRepository {
        var stored: DreamOutcome<DreamSettings> = DreamOutcome.Done(DreamSettings.DEFAULT)
        var saveAnswer: DreamOutcome<DreamSettings>? = null
        var runAnswer: DreamOutcome<DreamRunStart> = DreamOutcome.Done(DreamRunStart(DreamStartStatus.Started, "Started a reflection pass for this conversation."))
        var gate: CompletableDeferred<Unit>? = null
        var settingsGate: CompletableDeferred<DreamOutcome<DreamSettings>>? = null
        val saved = mutableListOf<DreamSettingsChange>()
        val runs = mutableListOf<String?>()

        override suspend fun settings(target: DreamTarget): DreamOutcome<DreamSettings> {
            settingsGate?.let { return it.await() }
            gate?.await()
            return stored
        }

        override suspend fun save(target: DreamTarget, change: DreamSettingsChange): DreamOutcome<DreamSettings> {
            saved += change
            gate?.await()
            return saveAnswer ?: DreamOutcome.Done(change.settings)
        }

        override suspend fun runNow(target: DreamTarget, instruction: String?): DreamOutcome<DreamRunStart> {
            runs += instruction
            return runAnswer
        }
    }

    private class FakeMemfs : MemfsSource {
        var commits = listOf(
            MemfsCommit("c3", "feat(reflection): Learned build commands 🔮", "2026-10-11T10:00:00Z", "Reflection Subagent"),
            MemfsCommit("b2", "chore: edit persona", "2026-10-10T10:00:00Z", "user"),
        )
        var historyCalls = 0

        override suspend fun history(scope: MemfsHistoryScope): List<MemfsCommit> {
            historyCalls++
            return commits
        }

        override suspend fun commitDiff(commit: MemfsCommitRef): String = """
            diff --git a/system/project.md b/system/project.md
            --- a/system/project.md
            +++ b/system/project.md
            @@ -1,1 +1,2 @@
             # Project
            +Build with ./gradlew --no-daemon
        """.trimIndent()

        override suspend fun list(agentId: String) = MemfsListing(enabled = true, files = emptyList())
        override suspend fun read(file: MemfsFileRef) = ""
        override suspend fun write(file: MemfsFileRef, content: String): String? = null
        override suspend fun fileAtRef(file: MemfsFileRef, ref: String) = ""
        override suspend fun enable(agentId: String) = Unit
        override val updates: Flow<MemfsUpdate> = emptyFlow()
    }

    private class Harness(val controller: DreamingController, val repository: FakeRepository, val memfs: FakeMemfs, val live: MutableStateFlow<List<SubagentEntry>>)

    private fun TestScope.harness(repository: FakeRepository = FakeRepository(), withHistory: Boolean = true): Harness {
        val memfs = FakeMemfs()
        val live = MutableStateFlow<List<SubagentEntry>>(emptyList())
        val controller = DreamingController(
            repository = repository,
            history = if (withHistory) DreamHistorySource(memfs) else null,
            scope = backgroundScope,
            activity = { live },
        )
        return Harness(controller, repository, memfs, live)
    }

    @Test
    fun focusingLoadsTheSettingsAndTheDreamLog() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val state = h.controller.state.value
        assertEquals(DreamSettings.DEFAULT, state.settings.settings)
        assertFalse(state.settings.loading)
        assertEquals(listOf("c3"), assertIs<DreamHistoryState.Loaded>(state.history).runs.map { it.sha })
        assertEquals("Learned build commands", state.lastRun?.summary)
    }

    @Test
    fun anUnsupportedBackendSaysWhyOnce() = runTest {
        val repository = FakeRepository().apply { stored = DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld) }
        val h = harness(repository, withHistory = false)
        h.controller.focus(agent)
        runCurrent()
        assertEquals(DreamUnsupportedReason.RuntimeTooOld, h.controller.state.value.unsupported)
        assertEquals(DreamHistoryState.Idle, h.controller.state.value.history)
    }

    @Test
    fun aSaveIsOptimisticAndARefusalPutsThePreviousSettingsBack() = runTest {
        val repository = FakeRepository()
        val h = harness(repository)
        h.controller.focus(agent)
        runCurrent()
        repository.gate = CompletableDeferred()
        repository.saveAnswer = DreamOutcome.Failed("step_count must be a positive integer")
        val change = DreamSettingsChange(DreamSettings(DreamTrigger.StepCount, 5))
        h.controller.save(change)
        runCurrent()
        assertEquals(DreamTrigger.StepCount, h.controller.state.value.settings.settings?.trigger)
        assertTrue(h.controller.state.value.settings.saving)
        repository.gate!!.complete(Unit)
        runCurrent()
        val settings = h.controller.state.value.settings
        assertEquals(DreamSettings.DEFAULT, settings.settings)
        assertEquals("step_count must be a positive integer", settings.error)
        assertFalse(settings.saving)
    }

    @Test
    fun runNowKeepsLettaCodesAnswerAndIgnoresADoubleTap() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        h.controller.runNow("tooling")
        h.controller.runNow("again")
        runCurrent()
        assertEquals(listOf<String?>("tooling"), h.repository.runs)
        assertEquals(DreamStartStatus.Started, h.controller.state.value.runNow.lastStart?.status)
    }

    @Test
    fun liveRunsShowAndTheLogReloadsWhenTheLastOneEnds() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val before = h.memfs.historyCalls
        h.live.value = listOf(SubagentEntry(toolCallId = "t1", description = "Reflect on recent conversations", subagentType = "Reflection", status = "running"))
        runCurrent()
        assertTrue(h.controller.state.value.dreamingNow)
        h.memfs.commits = listOf(MemfsCommit("d4", "merge(reflection): New lessons", "2026-10-11T11:00:00Z", null)) + h.memfs.commits
        h.live.value = emptyList()
        runCurrent()
        assertFalse(h.controller.state.value.dreamingNow)
        assertEquals(before + 1, h.memfs.historyCalls)
        assertEquals("New lessons", h.controller.state.value.lastRun?.summary)
    }

    @Test
    fun modelOnlySaveRefreshesLegacyFieldsBeforeSending() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val stale = h.controller.state.value.settings.settings!!
        h.repository.stored = DreamOutcome.Done(stale.copy(trigger = DreamTrigger.Off, stepCount = 99))
        h.controller.save(DreamSettingsChange(stale.copy(model = ReflectionModelChoice.Handle("model/new")), modelChanged = true))
        runCurrent()
        val saved = h.repository.saved.single().settings
        assertEquals(DreamTrigger.Off, saved.trigger)
        assertEquals(99, saved.stepCount)
        assertEquals(ReflectionModelChoice.Handle("model/new"), saved.model)
    }

    @Test
    fun focusOnlySaveRefreshesLegacyFieldsBeforeSending() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val stale = h.controller.state.value.settings.settings!!
        h.repository.stored = DreamOutcome.Done(stale.copy(merge = DreamMerge.Explicit, mergeInstructions = "newer"))
        h.controller.save(DreamSettingsChange(stale.copy(focusInstructions = "focus"), focusInstructionsChanged = true))
        runCurrent()
        val saved = h.repository.saved.single().settings
        assertEquals(DreamMerge.Explicit, saved.merge)
        assertEquals("newer", saved.mergeInstructions)
        assertEquals("focus", saved.focusInstructions)
    }

    @Test
    fun failedRefreshDoesNotWriteStaleSettings() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val stale = h.controller.state.value.settings.settings!!
        h.repository.stored = DreamOutcome.Failed("refresh failed")
        h.controller.save(DreamSettingsChange(stale.copy(model = ReflectionModelChoice.Inherit), modelChanged = true))
        runCurrent()
        assertTrue(h.repository.saved.isEmpty())
        assertEquals("refresh failed", h.controller.state.value.settings.error)
    }

    @Test
    fun endingLiveDreamReloadsSettingsEvenWithoutHistory() = runTest {
        val h = harness(withHistory = false)
        h.controller.focus(agent)
        runCurrent()
        h.live.value = listOf(SubagentEntry(toolCallId = "t1", description = "Reflection", subagentType = "Reflection", status = "running"))
        runCurrent()
        val outcome = ReflectionLastOutcome(ReflectionLastOutcomeStatus.Skipped, "nothing new", null, null, "now")
        h.repository.stored = DreamOutcome.Done(DreamSettings.DEFAULT.copy(lastOutcome = outcome))
        h.live.value = emptyList()
        runCurrent()
        assertEquals(outcome, h.controller.state.value.settings.settings?.lastOutcome)
    }

    @Test
    fun oldOutcomeReloadCannotOverwriteACompletedSave() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        h.live.value = listOf(SubagentEntry(toolCallId = "t1", description = "Reflection", subagentType = "Reflection", status = "running"))
        runCurrent()
        val stale = h.controller.state.value.settings.settings!!
        val saveGate = CompletableDeferred<Unit>()
        val reloadGate = CompletableDeferred<DreamOutcome<DreamSettings>>()
        h.repository.gate = saveGate
        h.repository.settingsGate = reloadGate
        h.controller.save(DreamSettingsChange(stale.copy(stepCount = 99)))
        runCurrent()
        h.live.value = emptyList()
        runCurrent()
        saveGate.complete(Unit)
        runCurrent()
        reloadGate.complete(DreamOutcome.Done(stale))
        runCurrent()
        assertEquals(99, h.controller.state.value.settings.settings?.stepCount)
    }

    @Test
    fun failedSaveStillRefreshesTheCompletedOutcomeAndKeepsItsError() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        h.live.value = listOf(SubagentEntry(toolCallId = "t1", description = "Reflection", subagentType = "Reflection", status = "running"))
        runCurrent()
        val stale = h.controller.state.value.settings.settings!!
        val saveGate = CompletableDeferred<Unit>()
        val oldReload = CompletableDeferred<DreamOutcome<DreamSettings>>()
        val freshReload = CompletableDeferred<DreamOutcome<DreamSettings>>()
        h.repository.gate = saveGate
        h.repository.settingsGate = oldReload
        h.repository.saveAnswer = DreamOutcome.Failed("save failed")
        h.controller.save(DreamSettingsChange(stale.copy(stepCount = 99)))
        runCurrent()
        h.live.value = emptyList()
        runCurrent()
        h.repository.settingsGate = freshReload
        saveGate.complete(Unit)
        runCurrent()
        val outcome = ReflectionLastOutcome(ReflectionLastOutcomeStatus.Skipped, "nothing new", null, null, "now")
        freshReload.complete(DreamOutcome.Done(stale.copy(lastOutcome = outcome)))
        runCurrent()
        assertEquals(outcome, h.controller.state.value.settings.settings?.lastOutcome)
        assertEquals("save failed", h.controller.state.value.settings.error)
    }

    @Test
    fun openingADreamLoadsTheFilesItTouched() = runTest {
        val h = harness()
        h.controller.focus(agent)
        runCurrent()
        val run = h.controller.state.value.lastRun!!
        h.controller.openRun(run)
        runCurrent()
        val detail = assertIs<DreamDetailState.Loaded>(h.controller.state.value.detail).detail
        assertEquals(listOf("system/project.md"), detail.files.map { it.path })
        assertEquals(1, detail.added)
        h.controller.closeRun()
        assertNull(h.controller.state.value.detail)
    }

    @Test
    fun aLateAnswerForThePreviousAgentIsDropped() = runTest {
        val repository = FakeRepository().apply { gate = CompletableDeferred() }
        val h = harness(repository)
        h.controller.focus(agent)
        runCurrent()
        h.controller.focus(other)
        repository.stored = DreamOutcome.Done(DreamSettings(DreamTrigger.Off))
        repository.gate!!.complete(Unit)
        runCurrent()
        val state = h.controller.state.value
        assertEquals(other, state.target)
        assertEquals(DreamTrigger.Off, state.settings.settings?.trigger)
    }
}
