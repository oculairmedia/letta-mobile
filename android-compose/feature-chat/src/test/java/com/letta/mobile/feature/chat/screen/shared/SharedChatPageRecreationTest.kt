package com.letta.mobile.feature.chat.screen.shared

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.feature.chat.NoOpChatActions
import com.letta.mobile.feature.chat.parity.RowParityFixture
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.AndroidClientCommands
import com.letta.mobile.feature.chat.screen.ChatScreenPendingToolHapticEffect
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.LocalAndroidClientCommands
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.PendingToolCall
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.haptics.Haptics
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import java.lang.ref.WeakReference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-09m53: rotating the chat destroys and recreates the Activity while the chat's
 * ViewModel survives. Nothing the ViewModel's scope keeps alive may still reach the old screen:
 * the destroyed Activity must be collectable once the new one shows the page. And the recreated
 * page must not replay a haptic cue for something that happened before the recreation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
@Tag("integration")
class SharedChatPageRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewModel: AdminChatViewModel = openedChatViewModel(
        pagingHost(),
        TestData.agent("agent-rotate", "Rotate"),
        "conversation-rotate",
        "rotate",
    )

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
    }

    /** LEAK 1: the session port's actions held the navigation callbacks of the first screen. */
    @Test
    fun navigationCallbacksDoNotKeepTheDestroyedActivity() {
        val old = showPageThenRecreate { page(navigation = activityNavigation(), commands = null) }
        assertCollected(old)
    }

    /** LEAK 2: the client-command port held the scaffold's commands, which hold the first screen's View. */
    @Test
    fun clientCommandsDoNotKeepTheDestroyedActivity() {
        val old = showPageThenRecreate { page(navigation = ChatScreenNavigationCallbacks(), commands = viewCommands()) }
        assertCollected(old)
    }

    /** A pending approval announces itself once; the recreated page does not announce it again. */
    @Test
    fun aPendingApprovalIsNotAnnouncedAgainOnRecreation() {
        val played = mutableListOf<LettaHapticCue>()
        val port = FixturePort(RowParityFixture.state(listOf(RowParityFixture.approval)))
        showPageThenRecreate {
            CompositionLocalProvider(LocalHaptics provides Haptics { played += it }) {
                ChatSurface(
                    port = port,
                    presentation = ChatSurfacePresentation.ChatFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    appearance = ChatSurfaceAppearance(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        assertEquals(listOf(LettaHapticCue.ApprovalNeeded), played)
    }

    /** A tool call greeted while pending is not greeted again by the recreated screen. */
    @Test
    fun aPendingToolIsNotGreetedAgainOnRecreation() {
        var greeted = 0
        val pending = persistentListOf(PendingToolCall(id = "tool-1", name = "Bash", startedAt = 0L))
        showPageThenRecreate {
            ChatScreenPendingToolHapticEffect(pendingTools = pending, hapticsEnabled = true, onToolStarted = { greeted++ })
        }
        assertEquals(1, greeted)
    }

    /** Shows [content] in the rule's Activity, recreates it (a rotation), shows it again, and returns the old Activity. */
    private fun showPageThenRecreate(content: @Composable () -> Unit): WeakReference<Activity> {
        compose.setContent(content)
        compose.waitForIdle()
        var old: WeakReference<Activity>? = null
        compose.activityRule.scenario.onActivity { old = WeakReference(it) }
        compose.activityRule.scenario.recreate()
        // MainActivity sets its content in onCreate; the recreated test Activity starts empty.
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        compose.waitForIdle()
        return checkNotNull(old)
    }

    @Composable
    private fun page(navigation: ChatScreenNavigationCallbacks, commands: AndroidClientCommands?) {
        CompositionLocalProvider(LocalAndroidClientCommands provides commands) {
            SharedChatPage(
                SharedChatPageParams(
                    viewModel = viewModel,
                    navigation = navigation,
                    chatMode = "interactive",
                    fontScale = 1f,
                    hapticsEnabled = true,
                    pagingPresentation = null,
                    openOnCanvas = false,
                    subagents = SharedChatSubagentInputs(
                        source = viewModel.activeSubagentSource,
                        selfTodoSource = viewModel.selfTodoSource,
                        barState = rememberChatScreenSubagentBarState(
                            viewModel.activeSubagentSource,
                            viewModel.selfTodoSource,
                            viewModel.conversationId?.value,
                        ),
                    ),
                ),
            )
        }
    }

    /** The scaffold's callbacks close over its NavController, and so over the Activity. */
    @Composable
    private fun activityNavigation(): ChatScreenNavigationCallbacks {
        val activity = LocalContext.current as Activity
        return ChatScreenNavigationCallbacks(
            onBugCommand = { activity.title },
            onOpenConversation = { _, _ -> activity.title },
        )
    }

    /** As the scaffold's commands do (AgentScaffoldRuntimeState.view), the route reaches the screen's View. */
    @Composable
    private fun viewCommands(): AndroidClientCommands {
        val view = LocalView.current
        return AndroidClientCommands(
            offered = setOf(ClientSlashCommand.Search),
            route = { view.requestFocus() },
            recompileGateway = null,
        )
    }

    private fun assertCollected(ref: WeakReference<Activity>) {
        repeat(GC_ATTEMPTS) {
            if (ref.get() == null) return
            compose.waitForIdle()
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(GC_PAUSE_MS)
        }
        assertNull("the destroyed Activity is still reachable after recreation", ref.get())
    }

    private class FixturePort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = NoOpChatActions
        override val capabilities: StateFlow<ChatSurfaceCapabilities> = MutableStateFlow(ChatSurfaceCapabilities(approvals = true))
    }

    private fun pagingHost(): ChatPagingHost {
        val presentation = ChatPagingPresentation(
            settled = flowOf(PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = {},
        )
        return ChatPagingHost().apply { openCanonical = { _, _, _, _ -> presentation } }
    }

    private companion object {
        const val GC_ATTEMPTS = 20
        const val GC_PAUSE_MS = 50L
    }
}
