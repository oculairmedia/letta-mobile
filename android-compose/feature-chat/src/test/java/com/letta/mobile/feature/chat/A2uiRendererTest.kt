package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiDataModel
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiProtocolJson
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiUpdateDataModelPayload
import com.letta.mobile.data.a2ui.decodeA2uiMessages
import com.letta.mobile.ui.a2ui.A2uiAction
import com.letta.mobile.ui.a2ui.A2uiRenderer
import com.letta.mobile.ui.a2ui.A2uiTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun pathBoundTextProgressesFromSkeletonToContentWhenDataArrivesLater() {
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                A2uiProtocolJson.Default,
                A2uiProtocolJson.Default.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"title","components":[{"id":"title","component":"Text","text":{"path":"/title"}}]}}
                    ]
                    """.trimIndent(),
                ),
            )
        )

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.MissingText).assertIsDisplayed()
        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/title",
                        value = JsonPrimitive("Booking confirmed"),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("Booking confirmed").assertIsDisplayed()
    }

    @Test
    fun dataModelUpdatesChangedPointerWithoutChangingOtherPointerValue() {
        val model = A2uiDataModel()
        model.applyPatch("/title", JsonPrimitive("Pending"))
        model.applyPatch("/body", JsonPrimitive("Stable"))
        var titleCompositions = 0
        var bodyCompositions = 0

        composeRule.setLettaTestContent(useChatTheme = false) {
            Column {
                ObservedPointerText(
                    model = model,
                    path = "/title",
                    onComposed = { titleCompositions++ },
                )
                ObservedPointerText(
                    model = model,
                    path = "/body",
                    onComposed = { bodyCompositions++ },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            titleCompositions = 0
            bodyCompositions = 0
        }
        composeRule.runOnIdle {
            model.applyPatch("/title", JsonPrimitive("Confirmed"))
        }
        composeRule.waitForIdle()

        assertTrue(titleCompositions > 0)
        assertTrue(bodyCompositions <= 1)
        composeRule.onNodeWithText("Confirmed").assertIsDisplayed()
        composeRule.onNodeWithText("Stable").assertIsDisplayed()
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

    @Test
    fun rendersPhase4Widgets() {
        val manager = phase4WidgetsSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Booking details").assertIsDisplayed()
        composeRule.onNodeWithText("Window").assertIsDisplayed()
        composeRule.onNodeWithText("Aisle").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("Reservation time").assertIsDisplayed()
        composeRule.onNodeWithText("2026-05-17T18:30").assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Divider).assertIsDisplayed()
    }

    @Test
    fun imageWithResolvedUrlExposesContentDescription() {
        val manager = imageSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithContentDescription("Hotel room").assertIsDisplayed()
    }

    @Test
    fun textFieldWritesBoundDataModelPath() {
        val manager = textFieldSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.TextField).performTextInput("4")
        composeRule.runOnIdle {
            assertEquals(
                "4",
                manager.surface(SurfaceId)!!.dataModel.resolve("/partySize")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun dateTimeInputOpensDatePicker() {
        val manager = dateTimeSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.DateTimeInput).performClick()
        composeRule.onAllNodesWithText("Select date").assertCountEquals(2)
    }
}

@Composable
private fun ObservedPointerText(
    model: A2uiDataModel,
    path: String,
    onComposed: () -> Unit,
) {
    val value by model.observe(path)
    SideEffect(onComposed)
    Text(value?.let(A2uiBindingResolver::displayText).orEmpty())
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

internal fun phase4WidgetsSurfaceManager(
    imageUrl: String? = "https://example.com/room.png",
): A2uiSurfaceManager {
    val imagePatch = imageUrl?.let {
        """,
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/imageUrl","value":"$it"}}
        """.trimIndent()
    }.orEmpty()
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"https://a2ui.org/specification/v0_9/basic_catalog.json"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"card","components":[
                    {"id":"card","component":"Card","child":"content","cornerRadius":16,"elevation":1},
                    {"id":"content","component":"Column","children":["title","seats","partySize","reservationTime","divider","image"],"spacing":"sm"},
                    {"id":"title","component":"Text","variant":"h5","text":{"literalString":"Booking details"}},
                    {"id":"seats","component":"Row","children":["windowSeat","aisleSeat"],"spacing":"sm","align":"center"},
                    {"id":"windowSeat","component":"Text","text":{"literalString":"Window"}},
                    {"id":"aisleSeat","component":"Text","text":{"literalString":"Aisle"}},
                    {"id":"partySize","component":"TextField","label":{"literalString":"Party size"},"value":{"path":"/partySize"},"textFieldType":"number"},
                    {"id":"reservationTime","component":"DateTimeInput","label":{"literalString":"Reservation time"},"value":{"path":"/reservationTime"},"enableDate":true,"enableTime":true},
                    {"id":"divider","component":"Divider"},
                    {"id":"image","component":"Image","url":{"path":"/imageUrl"},"alt":{"literalString":"Hotel room"},"height":48,"fit":"cover"}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/partySize","value":2}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/reservationTime","value":"2026-05-17T18:30"}}
                  $imagePatch
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun imageSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"image","components":[
                    {"id":"image","component":"Image","url":{"path":"/imageUrl"},"alt":{"literalString":"Hotel room"},"height":48,"fit":"cover"}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/imageUrl","value":"https://example.com/room.png"}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun textFieldSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"partySize","components":[
                    {"id":"partySize","component":"TextField","label":{"literalString":"Party size"},"value":{"path":"/partySize"},"textFieldType":"number"}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun dateTimeSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"reservationTime","components":[
                    {"id":"reservationTime","component":"DateTimeInput","label":{"literalString":"Reservation time"},"value":{"path":"/reservationTime"},"enableDate":true,"enableTime":false}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}
