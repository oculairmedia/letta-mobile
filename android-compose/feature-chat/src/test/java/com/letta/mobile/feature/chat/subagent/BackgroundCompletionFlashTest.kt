package com.letta.mobile.feature.chat.subagent

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackgroundCompletionFlashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completedRingExpiresOnceWhileTerminalRemainsInSource() {
        val time = mutableLongStateOf(10_000L)
        val task = mutableStateOf(probe("general"))
        compose.setContent {
            LettaChatTheme {
                ActiveSubagentRings(persistentListOf(task.value), now = time.longValue)
            }
        }
        compose.onNode(hasContentDescription("Completion probe", substring = true)).assertIsDisplayed()
        compose.runOnIdle { task.value = task.value.copy(status = ActiveSubagent.Status.COMPLETED) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasContentDescription("Completion probe", substring = true)).assertIsDisplayed()

        // The production parent ticks every second and retains terminal entries.
        repeat(3) {
            compose.runOnIdle { time.longValue += 1_000L }
            compose.mainClock.advanceTimeBy(600)
            compose.onNode(hasContentDescription("Completion probe", substring = true)).assertDoesNotExist()
        }
    }

    @Test
    fun reflectionNeverRendersDuringRunningCompletionOrExpiry() {
        val time = mutableLongStateOf(10_000L)
        val task = mutableStateOf(probe("reflection"))
        compose.setContent {
            LettaChatTheme {
                ActiveSubagentRings(persistentListOf(task.value), now = time.longValue)
            }
        }
        compose.onNode(hasContentDescription("Completion probe", substring = true)).assertDoesNotExist()
        compose.runOnIdle { task.value = task.value.copy(status = ActiveSubagent.Status.COMPLETED) }
        repeat(3) {
            compose.runOnIdle { time.longValue += 1_000L }
            compose.mainClock.advanceTimeBy(600)
            compose.onNode(hasContentDescription("Completion probe", substring = true)).assertDoesNotExist()
        }
    }

    private fun probe(type: String) = ActiveSubagent(
        id = "completion-probe",
        description = "Completion probe",
        subagentType = type,
        status = ActiveSubagent.Status.RUNNING,
    )

    @Test
    fun completedHiddenReflectionDoesNotKeepParentUiTicking() {
        compose.mainClock.autoAdvance = false
        val source = object : ActiveSubagentSource {
            override val activeSubagents = MutableStateFlow<ImmutableList<ActiveSubagent>>(
                persistentListOf(probe("reflection").copy(
                    status = ActiveSubagent.Status.COMPLETED,
                    terminalAt = System.currentTimeMillis(),
                )),
            )
        }
        val self = object : SelfTodoSource {
            override fun selfEntry(conversationId: String) = flowOf<ActiveSubagent?>(null)
            override fun todos(conversationId: String) = emptyList<com.letta.mobile.data.model.SubagentTodo>()
        }
        val ticks = mutableListOf<Long>()
        compose.setContent {
            val state = rememberChatScreenSubagentBarState(source, self, null)
            SideEffect { ticks += state.lingerTick }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val baseline = ticks.distinct().size
        repeat(3) {
            // Production reads wall time; ensure each virtual timer observes a distinct value.
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(1_100)
            compose.waitForIdle()
        }
        assertEquals("Hidden completed work must not produce recurring parent UI updates", baseline, ticks.distinct().size)
    }
}
