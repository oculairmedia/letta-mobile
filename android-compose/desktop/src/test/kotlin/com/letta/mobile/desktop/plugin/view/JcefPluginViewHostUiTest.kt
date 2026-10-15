package com.letta.mobile.desktop.plugin.view

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop [JcefPluginViewHost] as the shared live renderer calls it (letta-mobile-cdgpr.3),
 * without a browser: the first-run notice is drawn inside the view, and a runtime that is not
 * available here hands the element back to its card through `onFailure`.
 */
@OptIn(ExperimentalTestApi::class)
class JcefPluginViewHostUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val release = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        release.countDown()
        scope.cancel()
    }

    @Test
    fun theFirstRunProgressIsShownInsideTheView() = runComposeUiTest {
        val runtime = BrowserRuntime(
            BrowserStarter { progress ->
                progress.report("downloading", DOWNLOADED)
                release.await()
                APP
            },
        )
        val failures = CopyOnWriteArrayList<String>()
        val host = hostOf(runtime)
        val session = PluginViewTestFixtures.session()
        setContent { host.View(session, Modifier.fillMaxSize()) { failures += it } }

        val notice = PluginViewTestTags.notice(PluginViewTestFixtures.element.id)
        waitUntil(timeoutMillis = WAIT_MS) { runtime.state.value == BrowserRuntimeState.Preparing("downloading", DOWNLOADED) }
        waitForIdle()
        onNodeWithTag(notice).assertExists()
        onNodeWithText("Downloading the web view 42% (first run)").assertExists()
        assertEquals(emptyList(), failures.toList())
    }

    @Test
    fun anUnavailableRuntimeHandsTheElementBackToItsCard() = runComposeUiTest {
        val runtime = BrowserRuntime<String>(BrowserStarter { error("never started") }, disabledReason = "No display for web views")
        val failures = CopyOnWriteArrayList<String>()
        val host = hostOf(runtime)
        val session = PluginViewTestFixtures.session()
        setContent { host.View(session, Modifier.fillMaxSize()) { failures += it } }

        waitUntil(timeoutMillis = WAIT_MS) { failures.isNotEmpty() }
        assertEquals(listOf("No display for web views"), failures.toList())
    }

    private fun hostOf(runtime: BrowserRuntime<String>): JcefPluginViewHost<String> =
        JcefPluginViewHost(JcefLiveViews(runtime, PluginViewWork(scope), PluginBrowserFactory { _, _ -> FakeBrowserHandle() }))

    private companion object {
        const val APP = "app"
        const val DOWNLOADED = 0.42f
        const val WAIT_MS = 5_000L
    }
}
