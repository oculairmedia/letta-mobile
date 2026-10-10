@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.status

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.runtime.CommandActivity
import com.letta.mobile.data.runtime.CommandState
import com.letta.mobile.data.runtime.LiveCompaction
import com.letta.mobile.data.runtime.LiveNotice
import com.letta.mobile.data.runtime.LiveRetry
import com.letta.mobile.data.runtime.LoopPhase
import com.letta.mobile.data.runtime.NoticeLevel
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-bzvro.7 / .8 (F07, F08): the status line above the composer. */
class RunStatusLineUiTest {
    @Test
    fun nothingToSayDrawsNothing() = runComposeUiTest {
        setContent { MaterialTheme { RunStatusLine(RuntimeLiveStatus.Idle) } }
        onNodeWithTag(RunStatusTestTags.LINE).assertDoesNotExist()
    }

    @Test
    fun aRunningCompactionSaysSo() = runComposeUiTest {
        setContent { MaterialTheme { RunStatusLine(RuntimeLiveStatus(compaction = LiveCompaction(running = true))) } }
        onNodeWithText("Compacting the conversation…").assertExists()
    }

    @Test
    fun aFinishedCompactionGivesItsMessageCounts() = runComposeUiTest {
        val stats = com.letta.mobile.runtime.CompactionStats(messagesCountBefore = 48, messagesCountAfter = 12)
        setContent {
            MaterialTheme { RunStatusLine(RuntimeLiveStatus(compaction = LiveCompaction(running = false, stats = stats))) }
        }
        onNodeWithText("Conversation compacted · 48 → 12 messages").assertExists()
    }

    @Test
    fun theLoopPhaseIsNamed() = runComposeUiTest {
        setContent { MaterialTheme { RunStatusLine(RuntimeLiveStatus(phase = LoopPhase.WaitingForResponse)) } }
        onNodeWithTag(RunStatusTestTags.PHASE).assertTextEquals("Waiting for the model…")
    }

    @Test
    fun aRetryCountsDownAndNamesTheProvider() = runComposeUiTest {
        val retry = LiveRetry(attempt = 2, maxAttempts = 5, delayMs = 4_000L, provider = "anthropic", atEpochMs = 10_000L)
        setContent {
            MaterialTheme { RunStatusLine(RuntimeLiveStatus(phase = LoopPhase.Retrying, retry = retry), now = { 10_000L }) }
        }
        onNodeWithTag(RunStatusTestTags.PHASE).assertTextEquals("Retrying (2/5) in 4 s · anthropic")
    }

    @Test
    fun thePhaseIsHiddenWhileTheCompanionShowsIt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RunStatusLine(RuntimeLiveStatus(phase = LoopPhase.ProcessingResponse), companionShowing = true)
            }
        }
        onNodeWithTag(RunStatusTestTags.LINE).assertDoesNotExist()
        onNodeWithTag(RunStatusTestTags.PHASE).assertDoesNotExist()
    }

    @Test
    fun aRetryCountdownStaysWhileTheCompanionShows() = runComposeUiTest {
        val retry = LiveRetry(attempt = 2, maxAttempts = 5, delayMs = 4_000L, provider = "anthropic", atEpochMs = 10_000L)
        setContent {
            MaterialTheme {
                RunStatusLine(
                    RuntimeLiveStatus(phase = LoopPhase.Retrying, retry = retry),
                    companionShowing = true,
                    now = { 10_000L },
                )
            }
        }
        onNodeWithTag(RunStatusTestTags.PHASE).assertTextEquals("Retrying (2/5) in 4 s · anthropic")
    }

    @Test
    fun aHostWithoutTheCompanionKeepsThePhaseLine() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RunStatusLine(RuntimeLiveStatus(phase = LoopPhase.ProcessingResponse), companionShowing = false)
            }
        }
        onNodeWithTag(RunStatusTestTags.PHASE).assertExists()
    }

    @Test
    fun aDueRetryReadsAsRetryingNow() {
        val retry = LiveRetry(attempt = 3, maxAttempts = 5, delayMs = 1_000L, atEpochMs = 0L)
        assertEquals(0, retrySecondsLeft(retry, nowMs = 5_000L))
        assertEquals(1, retrySecondsLeft(retry, nowMs = 1L))
        assertEquals(1, retrySecondsLeft(retry, nowMs = 0L))
    }

    @Test
    fun theLineClearsWhenTheLoopGoesBackToWaitingOnInput() = runComposeUiTest {
        var status by mutableStateOf(RuntimeLiveStatus(phase = LoopPhase.ProcessingResponse))
        setContent { MaterialTheme { RunStatusLine(status) } }
        onNodeWithTag(RunStatusTestTags.PHASE).assertTextEquals("Processing the response…")
        status = RuntimeLiveStatus.Idle
        waitForIdle()
        onNodeWithTag(RunStatusTestTags.LINE).assertDoesNotExist()
    }

    @Test
    fun aServerNoticeShowsBesideThePhase() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RunStatusLine(
                    RuntimeLiveStatus(phase = LoopPhase.ExecutingCommand, notice = LiveNotice("Compacting conversation", NoticeLevel.Warning)),
                )
            }
        }
        onNodeWithTag(RunStatusTestTags.PHASE).assertTextEquals("Running a command…")
        onNodeWithTag(RunStatusTestTags.NOTICE).assertTextEquals("Compacting conversation")
    }

    @Test
    fun aRunningCommandThenItsOutputUntilDismissed() = runComposeUiTest {
        var status by mutableStateOf(
            RuntimeLiveStatus(commands = listOf(CommandActivity("cmd-1", "/compact", slash = true, state = CommandState.Running))),
        )
        setContent { MaterialTheme { RunStatusLine(status) } }
        onNodeWithText("Running /compact…").assertExists()
        onNodeWithTag(RunStatusTestTags.COMMAND_DISMISS).assertDoesNotExist()

        status = RuntimeLiveStatus(
            commands = listOf(
                CommandActivity("cmd-1", "/compact", slash = true, state = CommandState.Succeeded, output = "Compacted 42 messages"),
            ),
        )
        waitForIdle()
        onNodeWithText("/compact finished").assertExists()
        onNodeWithTag(RunStatusTestTags.COMMAND_OUTPUT).assertTextEquals("Compacted 42 messages")
        onNodeWithTag(RunStatusTestTags.COMMAND_DISMISS).performClick()
        onNodeWithTag(RunStatusTestTags.LINE).assertDoesNotExist()
    }

    @Test
    fun anUnmatchedFailedCommandRendersOnItsOwn() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RunStatusLine(
                    RuntimeLiveStatus(
                        commands = listOf(
                            CommandActivity("cmd-9", "git status", slash = false, state = CommandState.Failed, output = "fatal: not a repo", preformatted = true),
                        ),
                    ),
                )
            }
        }
        onNodeWithText("git status failed").assertExists()
        onAllNodesWithTag(RunStatusTestTags.COMMAND).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        onNodeWithTag(RunStatusTestTags.COMMAND_OUTPUT).assertTextEquals("fatal: not a repo")
    }
}
