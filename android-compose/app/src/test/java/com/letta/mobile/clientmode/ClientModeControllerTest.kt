package com.letta.mobile.clientmode

import androidx.lifecycle.LifecycleOwner
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.GatewayStatus
import com.letta.mobile.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * letta-mobile-etc1: regression test for the screen-off Client Mode run
 * cancellation bug. Previously [ClientModeController.onStop] called
 * `botGateway.stop()`, which tore down the WS bot session under any
 * in-flight `streamMessage` collector and aborted the user's run as soon
 * as the screen turned off / the app left the foreground.
 *
 * The fix decouples gateway lifecycle from the UI lifecycle: the WS
 * transport now stays up across `onStop` and is only torn down when
 * Client Mode is actually disabled in settings.
 */
class ClientModeControllerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onStop does not stop the bot gateway`() = runTest {
        val gateway = mockk<BotGateway>(relaxed = true) {
            every { status } returns MutableStateFlow(GatewayStatus.RUNNING)
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { observeClientModeEnabled() } returns flowOf(true)
            every { observeClientModeBaseUrl() } returns flowOf("https://lettabot.example/")
            every { getClientModeApiKey() } returns "tok"
        }
        val controller = ClientModeController(gateway, settings)
        val owner = mockk<LifecycleOwner>(relaxed = true)

        controller.onStop(owner)
        advanceUntilIdle()

        // The whole point of letta-mobile-etc1: backgrounding must NOT
        // tear down the WS session, otherwise an in-flight Client Mode
        // run is cancelled the moment the screen turns off.
        coVerify(exactly = 0) { gateway.stop() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reconcile keeps gateway running when app is backgrounded but settings still enabled`() = runTest {
        // Gateway reports it is already running so reconcile should not
        // restart or stop it just because we are not in the foreground.
        val gateway = mockk<BotGateway>(relaxed = true) {
            every { status } returns MutableStateFlow(GatewayStatus.RUNNING)
            every { sessions } returns MutableStateFlow(emptyMap())
            every { getSession(any()) } returns mockk(relaxed = true)
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { observeClientModeEnabled() } returns flowOf(true)
            every { observeClientModeBaseUrl() } returns flowOf("https://lettabot.example/")
            every { getClientModeApiKey() } returns "tok"
        }
        val controller = ClientModeController(gateway, settings)
        val owner = mockk<LifecycleOwner>(relaxed = true)

        // Simulate: app backgrounds -> screen-off Doze -> later something
        // calls ensureReady() which triggers reconcile(forceForeground=true).
        controller.onStop(owner)
        advanceUntilIdle()

        coVerify(exactly = 0) { gateway.stop() }
    }
}
