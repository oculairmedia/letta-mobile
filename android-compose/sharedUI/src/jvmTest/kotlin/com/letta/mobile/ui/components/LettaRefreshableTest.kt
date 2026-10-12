@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pull-to-refresh on touch, a refresh button on pointer - never both (letta-mobile-ckmub.3.21). */
class LettaRefreshableTest {
    private var refreshes = 0
    private var refreshing by mutableStateOf(false)

    private fun ComposeUiTest.show(host: LettaHost) {
        setContent {
            MaterialTheme {
                ProvideLettaHost(host) {
                    Column(Modifier.size(360.dp, 640.dp)) {
                        LettaRefreshAction(refreshing = refreshing, onRefresh = { refreshes++ })
                        LettaRefreshable(refreshing = refreshing, onRefresh = { refreshes++; refreshing = true }, modifier = Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize().testTag(LIST)) {
                                items((1..5).toList()) { Text("Row $it") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.pull() {
        onNodeWithTag(LIST).performTouchInput { swipeDown(startY = top, endY = bottom, durationMillis = 600) }
        waitForIdle()
    }

    @Test
    fun onTouchAPullRefreshesAndShowsTheIndicatorWithNoRefreshButton() = runComposeUiTest {
        show(LettaHost.Touch)
        onNodeWithContentDescription(REFRESH).assertDoesNotExist()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()

        pull()

        assertEquals(1, refreshes)
        onNodeWithTag(LettaRefreshTags.INDICATOR).assertExists()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
    }

    @Test
    fun onPointerTheButtonRefreshesAndAPullDoesNothing() = runComposeUiTest {
        show(LettaHost.Pointer)
        onNodeWithTag(LettaRefreshTags.PULL).assertDoesNotExist()

        pull()
        assertEquals(0, refreshes)

        onNodeWithContentDescription(REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    private companion object {
        const val LIST = "refreshable_list"
        const val REFRESH = "Refresh"
    }
}
