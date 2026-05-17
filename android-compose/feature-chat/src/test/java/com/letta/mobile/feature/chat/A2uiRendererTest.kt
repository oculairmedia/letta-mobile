package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiProtocolJson
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.decodeA2uiMessages
import com.letta.mobile.ui.a2ui.A2uiAction
import com.letta.mobile.ui.a2ui.A2uiRenderer
import com.letta.mobile.ui.a2ui.A2uiTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class A2uiRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersCoreWidgetsAndDispatchesButtonAction() {
        val manager = confirmationSurfaceManager()
        var clicked: A2uiAction? = null

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = { clicked = it },
            )
        }

        composeRule.onNodeWithText("Review tool call").assertIsDisplayed()
        composeRule.onNodeWithText("The agent wants to run ./gradlew test.").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsDisplayed().performClick()

        val action = requireNotNull(clicked)
        assertEquals("approve", action.name)
        assertEquals("req-1", action.context!!["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun partialComponentStreamShowsStablePlaceholders() {
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                A2uiProtocolJson.Default,
                A2uiProtocolJson.Default.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"root","components":[{"id":"root","component":"Column","children":["missingButton"]}]}}
                    ]
                    """.trimIndent(),
                ),
            )
        )

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.MissingComponent).assertIsDisplayed()
    }

    @Test
    fun buttonWithoutResolvedActionIsDisabled() {
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                A2uiProtocolJson.Default,
                A2uiProtocolJson.Default.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"button","components":[{"id":"button","component":"Button","label":{"literalString":"Waiting"}}]}}
                    ]
                    """.trimIndent(),
                ),
            )
        )

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Waiting").assertIsNotEnabled()
    }
}

internal const val SurfaceId = "confirmation-surface"

internal fun confirmationSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"https://a2ui.org/specification/v0_9/basic_catalog.json"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"card","components":[
                    {"id":"card","component":"Card","child":"content","cornerRadius":16,"elevation":2},
                    {"id":"content","component":"Column","children":["title","body","approve"],"spacing":"md"},
                    {"id":"title","component":"Text","variant":"h5","text":{"path":"/title"}},
                    {"id":"body","component":"Text","text":{"literalString":"The agent wants to run ./gradlew test."}},
                    {"id":"approveLabel","component":"Text","text":{"literalString":"Approve"}},
                    {"id":"approve","component":"Button","label":"approveLabel","action":{"name":"approve","context":{"requestId":"req-1"}}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/title","value":"Review tool call"}}
                ]
                """.trimIndent(),
            ),
        )
    )
    check(
        A2uiBindingResolver.resolvePath(
            manager.surface(SurfaceId)!!.dataModel,
            "/title",
        )!!.jsonPrimitive.content == "Review tool call"
    )
    return manager
}
