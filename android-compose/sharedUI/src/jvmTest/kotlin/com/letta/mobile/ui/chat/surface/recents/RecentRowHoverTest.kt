@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.recents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.requestFocus
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.LettaListRowDefaults
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.sidebar.ShellConversationListUi
import com.letta.mobile.ui.shell.sidebar.ShellConversationRow
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowActions
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecentRowHoverTest {
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun pointerHostReceivesHandCursorForRecentsAndRoster() {
        var cursor: PointerIcon = PointerIcon.Default
        val owners = mutableListOf<SemanticsOwner>()
        val platform = object : PlatformContext.Empty() {
            override fun setPointerIcon(pointerIcon: PointerIcon) { cursor = pointerIcon }
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners.add(semanticsOwner) }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners.remove(semanticsOwner) }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
            }
        }
        val frames = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frames, size = IntSize(400, 600), platformContext = platform)
        try {
            scene.setContent { HoverFixture() }
            repeat(SETTLE_FRAMES) { index ->
                frames.performFrame(index * FRAME_NANOS)
                scene.measureAndLayout()
            }
            val nodes = owners.flatMap { it.unmergedRootSemanticsNode.flatten().toList() }
            val recents = nodes.filter {
                it.config.getOrNull(SemanticsProperties.TestTag) in setOf(recentRowTag(IDLE_KEY), recentRowTag(OPEN_KEY))
            }
            val roster = nodes.filter { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text in setOf("Roster", "Roster open") } == true
            }
            assertEquals(4, recents.size + roster.size)
            for (node in recents + roster) {
                scene.sendPointerEvent(PointerEventType.Move, node.boundsInRoot.center)
                assertEquals(PointerIcon.Hand, cursor)
                scene.sendPointerEvent(PointerEventType.Exit, node.boundsInRoot.center)
                assertEquals(PointerIcon.Default, cursor)
            }
        } finally {
            scene.close()
            frames.close()
        }
    }

    @Test
    fun focusIsVisibleAndDisabledRowsHaveNoHoverSurface() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    Column(Modifier.width(400.dp)) {
                        LettaListRow(LettaListRowSpec("Focusable"), {}, Modifier.testTag(FOCUS_TAG))
                        LettaListRow(LettaListRowSpec("Disabled", enabled = false), {}, Modifier.testTag(DISABLED_TAG))
                    }
                }
            }
        }
        val focusable = onNodeWithTag(FOCUS_TAG)
        val bounds = focusable.fetchSemanticsNode().boundsInRoot
        focusable.requestFocus()
        waitForIdle()
        assertEquals(lightColorScheme().surfaceContainer, focusable.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y])
        assertEquals(bounds, focusable.fetchSemanticsNode().boundsInRoot)
        val disabled = onNodeWithTag(DISABLED_TAG).assertIsNotEnabled()
        val before = disabled.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
        disabled.performMouseInput { enter(center) }
        waitForIdle()
        assertEquals(before, disabled.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y])
    }

    @Test
    fun recentsUseThePointerHostsTitleTooltip() = runComposeUiTest {
        setContent { HoverFixture() }
        onNodeWithTag(recentRowTag(IDLE_KEY)).performMouseInput { enter(center) }
        mainClock.advanceTimeBy(TOOLTIP_SETTLE_MILLIS)
        waitForIdle()
        onNodeWithText("Idle").assertExists()
    }

    @Test
    fun recentsAndRosterHoverWithoutMoving() = runComposeUiTest {
        setContent { HoverFixture() }
        val rows = listOf(
            onNodeWithTag(recentRowTag(IDLE_KEY)) to false,
            onNodeWithTag(recentRowTag(OPEN_KEY)) to true,
            onNodeWithText("Roster") to false,
            onNodeWithText("Roster open") to true,
        )
        for ((node, isSelected) in rows) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val before = node.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
            if (isSelected) assertEquals(lightColorScheme().surfaceContainerHigh, before)
            node.performMouseInput { enter(center) }
            waitForIdle()
            val after = node.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
            val expected = if (isSelected) lightColorScheme().surfaceContainerHighest else lightColorScheme().surfaceContainer
            assertEquals(expected, after, "Hover surface; selected=$isSelected; before=$before")
            assertNotEquals(before, after, "Hover must change the rendered row surface")
            assertEquals(bounds, node.fetchSemanticsNode().boundsInRoot)
            node.performMouseInput { exit() }
        }
        onAllNodesWithTag(LettaListRowDefaults.TitleTestTag, useUnmergedTree = true).assertCountEquals(4)
    }
}

@Composable
private fun HoverFixture() {
    val idle = ShellConversationRowModel(IDLE_KEY, "Idle", "", "now")
    val selected = idle.copy(id = OPEN_KEY, title = "Open", selected = true)
    MaterialTheme {
        ProvideLettaHost(LettaHost.Pointer) {
            Column(Modifier.width(400.dp)) {
                RecentInteractionsList(
                    RecentInteractionsContent(null, "Agent", listOf(idle, selected)),
                    RecentInteractionsActions({}, {}), 300.dp,
                )
                RosterList(
                    RosterSpec(
                        listOf(RosterSection("roster", listOf(idle.copy(title = "Roster"), selected.copy(title = "Roster open")))),
                        ShellConversationRowModel::id,
                    ),
                    modifier = Modifier.height(200.dp),
                ) { model, row ->
                    ShellConversationRow(model, ShellConversationRowActions({}, {}), row, ShellConversationListUi())
                }
            }
        }
    }
}

private fun SemanticsNode.flatten(): Sequence<SemanticsNode> = sequence {
    yield(this@flatten)
    for (child in children) yieldAll(child.flatten())
}

private const val TOOLTIP_SETTLE_MILLIS = 200L
private const val SETTLE_FRAMES = 3
private const val FRAME_NANOS = 16_000_000L
private const val IDLE_KEY = "idle"
private const val OPEN_KEY = "open"
private const val FOCUS_TAG = "focus-row"
private const val DISABLED_TAG = "disabled-row"
private const val SAMPLE_X = 200
private const val SAMPLE_Y = 2
