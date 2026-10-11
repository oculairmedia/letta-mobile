@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInState
import com.letta.mobile.data.repository.modelcontrol.SignInFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** The in-app subscription sign-in sheet in each state (letta-mobile-zxk2n.3). */
class ProviderSignInDialogUiTest {
    private val xai = ConnectableProvider(
        id = "xai",
        displayName = "xAI (Grok/X subscription)",
        description = "",
        providerType = "xai",
        providerName = "xai",
        isOauth = true,
        requiresApiKey = true,
        authMethods = emptyList(),
        connections = emptyList(),
        oauthProviderId = "xai",
    )
    private val claude = xai.copy(id = "anthropic-oauth", displayName = "Anthropic (Claude Pro/Max)", oauthProviderId = "anthropic")

    private class Recorder {
        val opened = mutableListOf<String>()
        val typed = mutableListOf<String>()
        var submits = 0
        var retries = 0
        var closes = 0
        val actions = ProviderSignInActions(
            onCodeChange = { typed += it },
            onSubmitCode = { submits++ },
            onRetry = { retries++ },
            onClose = { closes++ },
        )
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
    }

    @Composable
    private fun Frame(recorder: Recorder, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalUriHandler provides recorder.uriHandler) {
            MaterialTheme(colorScheme = lightColorScheme()) { Box(Modifier.size(600.dp, 800.dp)) { content() } }
        }
    }

    @Test
    fun aDeviceCodeShowsTheCodeAndOpensTheVerificationPage() = runComposeUiTest {
        val recorder = Recorder()
        val state = ProviderSignInState.AwaitingApproval(xai, "ABCD-1234", "https://accounts.x.ai/device?code=ABCD-1234", 600_000)
        setContent { Frame(recorder) { ProviderSignInDialog(state, recorder.actions) } }

        onNodeWithText(ModelControlStrings.signInTitle(xai.displayName)).assertIsDisplayed()
        onNodeWithTag(ProviderSignInTags.USER_CODE).assertTextContains("ABCD-1234")
        onNodeWithTag(ProviderSignInTags.COPY_CODE).assertIsDisplayed()
        onNodeWithText(ModelControlStrings.SIGN_IN_WAITING).assertIsDisplayed()
        onNodeWithTag(ProviderSignInTags.OPEN_PAGE).performClick()
        assertEquals(listOf("https://accounts.x.ai/device?code=ABCD-1234"), recorder.opened)
        onNodeWithTag(ProviderSignInTags.CLOSE).assertTextContains(ModelControlStrings.CANCEL).performClick()
        assertEquals(1, recorder.closes)
    }

    @Test
    fun slowDownIsExplained() = runComposeUiTest {
        val recorder = Recorder()
        val state = ProviderSignInState.AwaitingApproval(xai, "ABCD-1234", "https://accounts.x.ai/device", 600_000, slowedDown = true)
        setContent { Frame(recorder) { ProviderSignInDialog(state, recorder.actions) } }

        onNodeWithText(ModelControlStrings.SIGN_IN_SLOWED).assertIsDisplayed()
    }

    @Test
    fun aPastedCodeIsTypedAndSubmitted() = runComposeUiTest {
        val recorder = Recorder()
        val empty = ProviderSignInState.AwaitingCode(claude, "https://claude.ai/oauth/authorize?client_id=x")
        var state: ProviderSignInState by mutableStateOf(empty)
        setContent { Frame(recorder) { ProviderSignInDialog(state, recorder.actions) } }

        onNodeWithTag(ProviderSignInTags.SUBMIT).assertIsNotEnabled()
        onNodeWithTag(ProviderSignInTags.OPEN_PAGE).performClick()
        assertEquals(listOf("https://claude.ai/oauth/authorize?client_id=x"), recorder.opened)
        onNodeWithTag(ProviderSignInTags.CODE_FIELD).performTextInput("abc#st")
        assertEquals("abc#st", recorder.typed.last())

        state = empty.copy(input = "abc#st")
        onNodeWithTag(ProviderSignInTags.SUBMIT).assertIsEnabled().performClick()
        assertEquals(1, recorder.submits)

        state = empty.copy(input = "abc#other", error = "That code is from a different sign-in; start again")
        onNodeWithText("That code is from a different sign-in; start again").assertIsDisplayed()
    }

    @Test
    fun progressFailureAndSuccessStates() = runComposeUiTest {
        val recorder = Recorder()
        var state: ProviderSignInState by mutableStateOf(ProviderSignInState.Starting(xai))
        setContent { Frame(recorder) { ProviderSignInDialog(state, recorder.actions) } }

        onNodeWithText(ModelControlStrings.SIGN_IN_STARTING).assertIsDisplayed()

        state = ProviderSignInState.Connecting(xai)
        onNodeWithText(ModelControlStrings.SIGN_IN_CONNECTING).assertIsDisplayed()

        state = ProviderSignInState.Failed(xai, SignInFailure.DENIED, "Sign-in was declined on the xAI page")
        onNodeWithText("Sign-in was declined on the xAI page").assertIsDisplayed()
        onNodeWithTag(ProviderSignInTags.RETRY).performClick()
        assertEquals(1, recorder.retries)
        onNodeWithTag(ProviderSignInTags.CLOSE).assertTextContains(ModelControlStrings.CLOSE)

        state = ProviderSignInState.Connected(xai)
        onNodeWithText(ModelControlStrings.signInConnected(xai.displayName)).assertIsDisplayed()
        onNodeWithText(ModelControlStrings.SIGN_IN_DONE).performClick()
        assertEquals(1, recorder.closes)
    }

    @Test
    fun idleShowsNothing() = runComposeUiTest {
        val recorder = Recorder()
        setContent { Frame(recorder) { ProviderSignInDialog(ProviderSignInState.Idle, recorder.actions) } }

        onNodeWithTag(ProviderSignInTags.SHEET).assertDoesNotExist()
    }
}
