package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiDataModel
import com.letta.mobile.data.a2ui.A2uiDeleteSurfacePayload
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiProtocolJson
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiUpdateDataModelPayload
import com.letta.mobile.data.a2ui.decodeA2uiMessages
import com.letta.mobile.ui.a2ui.A2uiAction
import com.letta.mobile.ui.a2ui.A2uiRenderer
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.a2ui.A2uiTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        assertEquals("req-1", action.context["requestId"]!!.jsonPrimitive.content)
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
    fun buttonResolvesChildIdToSiblingTextLabel() {
        // letta-mobile-njzb regression guard: Button.child is the
        // canonical A2UI v0.9 Basic Catalog field for the label-by-id
        // reference. The renderer must look the id up in the
        // per-surface registry and render the sibling Text's content
        // inside the button slot. A renderer that ignores `child`
        // (the original njzb bug) produces an empty pill.
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                A2uiProtocolJson.Default,
                A2uiProtocolJson.Default.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"ok-btn","components":[
                        {"id":"ok-btn","component":"Button","child":"ok-label","action":{"name":"ok"}},
                        {"id":"ok-label","component":"Text","text":"It works"}
                      ]}}
                    ]
                    """.trimIndent(),
                ),
            )
        )

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("It works").assertIsDisplayed()
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
    fun toolApprovalCardRendersAndMasksSensitiveArguments() {
        val manager = toolApprovalSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.ToolApprovalCard).assertIsDisplayed()
        composeRule.onNodeWithText("bash").assertIsDisplayed()
        composeRule.onNodeWithText("Run a shell command").assertIsDisplayed()
        composeRule.onNodeWithText("Destructive").assertIsDisplayed()
        composeRule.onNodeWithText("Needed to clear generated files.").assertIsDisplayed()
        composeRule.onNodeWithText("command").assertIsDisplayed()
        composeRule.onNodeWithText("rm -rf /tmp/build").assertIsDisplayed()
        composeRule.onNodeWithText("token").assertIsDisplayed()
        composeRule.onAllNodesWithText("sk-test-secret").assertCountEquals(0)
        composeRule.onNodeWithText("********").assertIsDisplayed()
        composeRule.onNodeWithText("Once").assertIsDisplayed()
        composeRule.onNodeWithText("This chat").assertIsDisplayed()
        composeRule.onNodeWithText("Always").assertIsDisplayed()
        composeRule.onNodeWithText("Deny").assertIsDisplayed()
    }

    @Test
    fun toolApprovalSensitiveArgumentRevealsOnTap() {
        val manager = toolApprovalSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.ToolApprovalSensitiveValue).performClick()
        composeRule.onNodeWithText("sk-test-secret").assertIsDisplayed()
    }

    @Test
    fun toolApprovalLocalStateDefaultsCanStartArgumentsCollapsed() {
        val manager = toolApprovalSurfaceManager(argumentsExpanded = false)

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Show").assertIsDisplayed()
        composeRule.onAllNodesWithText("rm -rf /tmp/build").assertCountEquals(0)
        composeRule.onAllNodesWithText("********").assertCountEquals(0)
    }

    @Test
    fun toolApprovalLocalStateSurvivesDataModelRecomposition() {
        val manager = toolApprovalSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Hide").performClick()
        composeRule.onNodeWithText("Show").assertIsDisplayed()

        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/unrelated",
                        value = JsonPrimitive("updated"),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("Show").assertIsDisplayed()
        composeRule.onAllNodesWithText("rm -rf /tmp/build").assertCountEquals(0)
    }

    @Test
    fun toolApprovalLocalStateResetsWhenSurfaceIsDeletedAndRecreated() {
        val manager = toolApprovalSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Hide").performClick()
        composeRule.onNodeWithText("Show").assertIsDisplayed()

        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.DeleteSurface(
                    deleteSurface = A2uiDeleteSurfacePayload(surfaceId = SurfaceId),
                )
            )
        }
        composeRule.onNodeWithTag(A2uiTestTags.SurfaceMissing).assertIsDisplayed()

        composeRule.runOnIdle {
            manager.applyToolApprovalSurface()
        }

        composeRule.onNodeWithText("Hide").assertIsDisplayed()
        composeRule.onNodeWithText("rm -rf /tmp/build").assertIsDisplayed()
    }

    @Test
    fun toolApprovalOnceAffordanceDispatchesAction() {
        assertToolApprovalAffordance(
            label = "Once",
            affordance = "once",
            callId = "call-once",
            decision = "approve",
            scope = "once",
        )
    }

    @Test
    fun toolApprovalSessionAffordanceDispatchesAction() {
        assertToolApprovalAffordance(
            label = "This chat",
            affordance = "session",
            callId = "call-session",
            decision = "approve",
            scope = "session",
        )
    }

    @Test
    fun toolApprovalForeverAffordanceDispatchesAction() {
        assertToolApprovalAffordance(
            label = "Always",
            affordance = "forever",
            callId = "call-forever",
            decision = "approve",
            scope = "forever",
        )
    }

    @Test
    fun toolApprovalDenyAffordanceDispatchesAction() {
        assertToolApprovalAffordance(
            label = "Deny",
            affordance = "deny",
            callId = "call-deny",
            decision = "deny",
            scope = "deny",
        )
    }

    @Test
    fun toolApprovalTimeoutDispatchesAction() {
        composeRule.mainClock.autoAdvance = false
        val manager = toolApprovalSurfaceManager(timeoutSeconds = 1)
        val actions = mutableListOf<A2uiAction>()

        try {
            composeRule.setLettaTestContent(useChatTheme = false) {
                A2uiRenderer(
                    surfaceId = SurfaceId,
                    surfaceManager = manager,
                    onAction = actions::add,
                )
            }

            composeRule.onNodeWithText("Auto-denies in 1s").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(1_100)
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Timed out").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(1, actions.size)
                actions.assertToolApprovalAction("call-approval-1", decision = "timeout", scope = "timeout")
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
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
    fun listViewRendersTemplateForEachItemWithPerItemScope() {
        val manager = listViewSurfaceManager()
        val actions = mutableListOf<A2uiAction>()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.ListView).assertIsDisplayed()
        composeRule.onNodeWithText("Alpha issue").assertIsDisplayed()
        composeRule.onNodeWithText("Needs review").assertIsDisplayed()
        composeRule.onNodeWithText("Beta issue").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to merge").assertIsDisplayed()

        composeRule.onNodeWithText("Open Beta").performClick()
        composeRule.runOnIdle {
            val action = actions.single()
            assertEquals("issue.open", action.name)
            assertEquals("issue-2", action.context["issueId"]!!.jsonPrimitive.content)
            assertEquals("Beta issue", action.context["title"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun listViewProgressesFromSkeletonToItemsWhenDataArrivesLater() {
        val manager = listViewSurfaceManager(includeItems = false)

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.MissingComponent).assertIsDisplayed()
        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/issues",
                        value = A2uiProtocolJson.Default.parseToJsonElement(
                            """
                            [
                              {"id":"issue-1","title":"Alpha issue","subtitle":"Needs review","actionLabel":"Open Alpha"},
                              {"id":"issue-2","title":"Beta issue","subtitle":"Ready to merge","actionLabel":"Open Beta"}
                            ]
                            """.trimIndent(),
                        ),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("Alpha issue").assertIsDisplayed()
        composeRule.onNodeWithText("Beta issue").assertIsDisplayed()
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
    fun checkboxWritesBoundDataModelPath() {
        val manager = formWidgetSurfaceManager(root = "acceptTerms")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Checkbox).assertIsOff().performClick()
        composeRule.runOnIdle {
            assertEquals(
                "true",
                manager.surface(SurfaceId)!!.dataModel.resolve("/accepted")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun switchUsesLocalStateWhenUnbound() {
        val manager = formWidgetSurfaceManager(root = "notifications")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Switch).assertIsOn().performClick()
        composeRule.onNodeWithTag(A2uiTestTags.Switch).assertIsOff()
        composeRule.runOnIdle {
            assertEquals(null, manager.surface(SurfaceId)!!.dataModel.resolve("/notifications"))
        }
    }

    @Test
    fun radioWritesStaticOptionToBoundDataModelPath() {
        val manager = formWidgetSurfaceManager(root = "seatChoice")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Radio).assertIsDisplayed()
        composeRule.onNodeWithText("Aisle").performClick()
        composeRule.runOnIdle {
            assertEquals(
                "aisle",
                manager.surface(SurfaceId)!!.dataModel.resolve("/seat")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun radioRendersItemsPathOptionsAndKeepsUnboundSelectionLocal() {
        val manager = formWidgetSurfaceManager(root = "mealChoice")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Pasta").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Pasta").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, manager.surface(SurfaceId)!!.dataModel.resolve("/meal"))
        }
    }

    @Test
    fun formWidgetsAreDisabledWhileSiblingActionIsSubmitting() {
        val manager = formWidgetSurfaceManager(root = "form")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Checkbox).assertIsNotEnabled()
        composeRule.onNodeWithTag(A2uiTestTags.Switch).assertIsNotEnabled()

        composeRule.onNodeWithText("Aisle").performClick()
        composeRule.runOnIdle {
            assertEquals(
                "window",
                manager.surface(SurfaceId)!!.dataModel.resolve("/seat")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun sliderWritesIntegralStepAsLongToBoundDataModelPath() {
        val manager = numericWidgetSurfaceManager(root = "volumeSlider")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Slider).assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Slider)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(8f) }

        composeRule.runOnIdle {
            assertEquals(
                "8",
                manager.surface(SurfaceId)!!.dataModel.resolve("/volume")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun sliderWritesFractionalStepAsDoubleToBoundDataModelPath() {
        val manager = numericWidgetSurfaceManager(root = "temperatureSlider")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Slider).assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Slider)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.5f) }

        composeRule.runOnIdle {
            assertEquals(
                "1.5",
                manager.surface(SurfaceId)!!.dataModel.resolve("/temperature")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun stepperWritesIntegralAndFractionalStepsToBoundDataModelPaths() {
        val manager = numericWidgetSurfaceManager(root = "numericForm")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onAllNodesWithTag(A2uiTestTags.StepperIncrement)[0].performClick()
        composeRule.onAllNodesWithTag(A2uiTestTags.StepperIncrement)[1].performClick()

        composeRule.runOnIdle {
            assertEquals(
                "3",
                manager.surface(SurfaceId)!!.dataModel.resolve("/quantity")!!.jsonPrimitive.content,
            )
            assertEquals(
                "1.5",
                manager.surface(SurfaceId)!!.dataModel.resolve("/rating")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun stepperUsesLocalStateWhenUnbound() {
        val manager = numericWidgetSurfaceManager(root = "localStepper")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.StepperIncrement).performClick()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, manager.surface(SurfaceId)!!.dataModel.resolve("/localCount"))
        }
    }

    @Test
    fun numericWidgetsAreDisabledWhileSiblingActionIsSubmitting() {
        val manager = numericWidgetSurfaceManager(root = "submitForm")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Slider).assertIsNotEnabled()
        composeRule.onAllNodesWithTag(A2uiTestTags.StepperIncrement)[0].assertIsNotEnabled()
    }

    @Test
    fun dropdownWritesStaticOptionToBoundDataModelPath() {
        val manager = dropdownSurfaceManager(root = "seatDropdown")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Dropdown).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Aisle").performClick()
        composeRule.runOnIdle {
            assertEquals(
                "aisle",
                manager.surface(SurfaceId)!!.dataModel.resolve("/seat")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun selectRendersItemsPathOptionsAndWritesSelection() {
        val manager = dropdownSurfaceManager(root = "mealSelect")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Dropdown).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Pasta").performClick()
        composeRule.runOnIdle {
            assertEquals(
                "pasta",
                manager.surface(SurfaceId)!!.dataModel.resolve("/meal")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun dropdownUsesLocalStateWhenUnbound() {
        val manager = dropdownSurfaceManager(root = "localDropdown")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithText("Low").assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Dropdown).performClick()
        composeRule.onNodeWithText("High").performClick()
        composeRule.onNodeWithText("High").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, manager.surface(SurfaceId)!!.dataModel.resolve("/priority"))
        }
    }

    @Test
    fun dropdownIsDisabledWhileSiblingActionIsSubmitting() {
        val manager = dropdownSurfaceManager(root = "dropdownForm")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.Dropdown).assertIsNotEnabled()
    }

    @Test
    fun chipDispatchesUserActionWithContext() {
        val manager = chipSurfaceManager(root = "tagChip")
        val actions = mutableListOf<A2uiAction>()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.Chip).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            val action = actions.single()
            assertEquals("filter_tag", action.name)
            assertEquals("urgent", action.context["tag"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun filterChipPatchesBoundBooleanPath() {
        val manager = chipSurfaceManager(root = "doneFilter")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.FilterChip).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(
                "true",
                manager.surface(SurfaceId)!!.dataModel.resolve("/filters/done")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun badgeReflectsDataModelUpdates() {
        val manager = chipSurfaceManager(root = "countBadge")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Badge).assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/unreadCount",
                        value = JsonPrimitive(5),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun tabsSwitchVisibleChildUsingLocalState() {
        val manager = tabsAccordionSurfaceManager(root = "projectTabs")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Tabs).assertIsDisplayed()
        composeRule.onNodeWithText("Overview body").assertIsDisplayed()
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Activity body").assertIsDisplayed()

        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/unrelated",
                        value = JsonPrimitive("updated"),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("Activity body").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, manager.surface(SurfaceId)!!.dataModel.resolve("/selectedTab"))
        }
    }

    @Test
    fun accordionItemsExpandIndependentlyAndSurviveRecomposition() {
        val manager = tabsAccordionSurfaceManager(root = "faqAccordion")

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(surfaceId = SurfaceId, surfaceManager = manager)
        }

        composeRule.onNodeWithTag(A2uiTestTags.Accordion).assertIsDisplayed()
        composeRule.onNodeWithText("Summary body").assertIsDisplayed()
        composeRule.onNodeWithText("Details body").assertDoesNotExist()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Details body").assertIsDisplayed()
        composeRule.onNodeWithText("Summary body").assertIsDisplayed()

        composeRule.runOnIdle {
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = SurfaceId,
                        path = "/unrelated",
                        value = JsonPrimitive("updated"),
                    ),
                )
            )
        }

        composeRule.onNodeWithText("Details body").assertIsDisplayed()
    }

    @Test
    fun buttonActionResolvesBoundContextAgainstCurrentDataModel() {
        val manager = bookingFormSurfaceManager()
        val actions = mutableListOf<A2uiAction>()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.TextField).performTextInput("4")
        composeRule.onNodeWithText("Submit").performClick()

        composeRule.runOnIdle {
            val action = actions.single()
            assertEquals("submit_booking", action.name)
            assertEquals(SurfaceId, action.surfaceId)
            assertEquals("4", action.context["partySize"]!!.jsonPrimitive.content)
            assertEquals("2026-05-17T18:30", action.context["reservationTime"]!!.jsonPrimitive.content)
            assertEquals("window", action.context["seat"]!!.jsonPrimitive.content)
            assertEquals("submit_booking", action.raw["actionName"]!!.jsonPrimitive.content)
            assertEquals(SurfaceId, action.raw["surfaceId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun buttonTapShowsLocalSubmittingStateAndCoalescesRepeatedTaps() {
        val manager = bookingFormSurfaceManager()
        val actions = mutableListOf<A2uiAction>()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()
        composeRule.onNodeWithText("submitting...").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, actions.size)
        }
    }

    @Test
    fun siblingTextFieldIsReadOnlyWhileButtonActionIsSubmitting() {
        val manager = bookingFormSurfaceManager()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = {},
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.TextField).performTextInput("4")
        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithText("submitting...").assertIsDisplayed()
        assertThrows(AssertionError::class.java) {
            composeRule.onNodeWithTag(A2uiTestTags.TextField).performTextInput("2")
        }

        composeRule.runOnIdle {
            assertEquals(
                "4",
                manager.surface(SurfaceId)!!.dataModel.resolve("/partySize")!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun buttonLocalSubmittingStateClearsAfterTimeout() {
        val manager = bookingFormSurfaceManager()

        try {
            composeRule.setLettaTestContent(useChatTheme = false) {
                A2uiRenderer(
                    surfaceId = SurfaceId,
                    surfaceManager = manager,
                    onAction = {},
                )
            }

            composeRule.onNodeWithText("Submit").performClick()
            composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
            composeRule.onNodeWithText("Submit").assertIsNotEnabled()

            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(10_100)
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Submit").assertIsEnabled()
            composeRule.onNodeWithText("submitting...").assertDoesNotExist()
            composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertDoesNotExist()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun buttonLocalSubmittingStateClearsWhenActionResolutionTokenChanges() {
        val manager = bookingFormSurfaceManager()
        val resolutionToken = mutableIntStateOf(0)

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface(SurfaceId),
                actionResolutionToken = resolutionToken.intValue,
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Submit").performClick()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()

        composeRule.runOnIdle {
            resolutionToken.intValue += 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Submit").assertIsEnabled()
        composeRule.onNodeWithText("submitting...").assertDoesNotExist()
        composeRule.onNodeWithTag(A2uiTestTags.ButtonProgress).assertDoesNotExist()
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

    private fun assertToolApprovalAffordance(
        label: String,
        affordance: String,
        callId: String,
        decision: String,
        scope: String,
    ) {
        val manager = toolApprovalSurfaceManager(
            affordances = listOf(affordance),
            callId = callId,
            riskLevel = "medium",
        )
        val actions = mutableListOf<A2uiAction>()

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiRenderer(
                surfaceId = SurfaceId,
                surfaceManager = manager,
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithText(label).performClick()
        composeRule.runOnIdle {
            assertEquals(1, actions.size)
            actions.assertToolApprovalAction(callId, decision = decision, scope = scope)
        }
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

private fun List<A2uiAction>.assertToolApprovalAction(
    callId: String,
    decision: String,
    scope: String,
) {
    val action = single { it.context["callId"]!!.jsonPrimitive.content == callId }
    assertEquals("tool_approval_response", action.name)
    assertEquals("tool_approval_response", action.raw["actionName"]!!.jsonPrimitive.content)
    assertEquals(SurfaceId, action.raw["surfaceId"]!!.jsonPrimitive.content)
    assertEquals(decision, action.context["decision"]!!.jsonPrimitive.content)
    assertEquals(scope, action.context["scope"]!!.jsonPrimitive.content)
}

internal fun confirmationSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
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

internal fun toolApprovalSurfaceManager(
    timeoutSeconds: Int = 30,
    affordances: List<String> = listOf("once", "session", "forever", "deny"),
    callId: String = "call-approval-1",
    riskLevel: String = "destructive",
    argumentsExpanded: Boolean? = null,
): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyToolApprovalSurface(
        timeoutSeconds = timeoutSeconds,
        affordances = affordances,
        callId = callId,
        riskLevel = riskLevel,
        argumentsExpanded = argumentsExpanded,
    )
    return manager
}

private fun A2uiSurfaceManager.applyToolApprovalSurface(
    timeoutSeconds: Int = 30,
    affordances: List<String> = listOf("once", "session", "forever", "deny"),
    callId: String = "call-approval-1",
    riskLevel: String = "destructive",
    argumentsExpanded: Boolean? = null,
) {
    val affordanceJson = affordances.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    val localStateJson = argumentsExpanded?.let {
        """
                      "localState":{"argumentsExpanded":$it},
        """.trimIndent()
    }.orEmpty()
    applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"com.letta.mobile:tool-approval/v1"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"approval","components":[
                    {"id":"approval","component":"ToolApprovalCard",
                      "toolName":"bash",
                      "toolDescription":"Run a shell command",
                      $localStateJson
                      "arguments":[
                       {"key":"command","value":"rm -rf /tmp/build","isSensitive":false},
                       {"key":"token","value":"sk-test-secret","isSensitive":true}
                     ],
                     "riskLevel":"$riskLevel",
                     "rationale":"Needed to clear generated files.",
                     "affordances":$affordanceJson,
                     "timeoutSeconds":$timeoutSeconds,
                     "callId":"$callId"}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
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
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
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

private fun formWidgetSurfaceManager(root: String): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"$root","components":[
                    {"id":"form","component":"Column","children":["acceptTerms","notifications","seatChoice","submit"],"spacing":"sm"},
                    {"id":"acceptTerms","component":"Checkbox","label":{"literalString":"Accept terms"},"value":{"path":"/accepted"}},
                    {"id":"notifications","component":"Switch","label":{"literalString":"Notifications"},"value":true},
                    {"id":"seatChoice","component":"Radio","label":{"literalString":"Seat preference"},"value":{"path":"/seat"},"options":[
                      {"key":"window","label":{"literalString":"Window"}},
                      {"key":"aisle","label":{"literalString":"Aisle"}}
                    ]},
                    {"id":"mealChoice","component":"Radio","label":{"literalString":"Meal preference"},"value":{"literalString":"salad"},"items":{"path":"/meals"}},
                    {"id":"submit","component":"Button","label":{"literalString":"Submit"},"action":{"name":"submit_form"}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/seat","value":"window"}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/meals","value":[
                    {"key":"salad","label":"Salad"},
                    {"key":"pasta","label":"Pasta"}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun numericWidgetSurfaceManager(root: String): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"$root","components":[
                    {"id":"numericForm","component":"Column","children":["quantityStepper","ratingStepper"],"spacing":"sm"},
                    {"id":"submitForm","component":"Column","children":["volumeSlider","quantityStepper","submit"],"spacing":"sm"},
                    {"id":"volumeSlider","component":"Slider","label":{"literalString":"Volume"},"value":{"path":"/volume"},"min":0,"max":10,"step":1},
                    {"id":"temperatureSlider","component":"Slider","label":{"literalString":"Temperature"},"value":{"path":"/temperature"},"min":0,"max":2,"step":0.5},
                    {"id":"quantityStepper","component":"Stepper","label":{"literalString":"Quantity"},"value":{"path":"/quantity"},"min":0,"max":5,"step":1},
                    {"id":"ratingStepper","component":"Stepper","label":{"literalString":"Rating"},"value":{"path":"/rating"},"min":0,"max":2,"step":0.5},
                    {"id":"localStepper","component":"Stepper","label":{"literalString":"Local count"},"value":1,"min":0,"max":3,"step":1},
                    {"id":"submit","component":"Button","label":{"literalString":"Submit"},"action":{"name":"submit_numeric"}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/volume","value":4}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/temperature","value":1.0}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/quantity","value":2}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/rating","value":1.0}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun dropdownSurfaceManager(root: String): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"$root","components":[
                    {"id":"dropdownForm","component":"Column","children":["seatDropdown","submit"],"spacing":"sm"},
                    {"id":"seatDropdown","component":"Dropdown","label":{"literalString":"Seat preference"},"placeholder":{"literalString":"Pick a seat"},"value":{"path":"/seat"},"options":[
                      {"key":"window","label":{"literalString":"Window"}},
                      {"key":"aisle","label":{"literalString":"Aisle"}}
                    ]},
                    {"id":"mealSelect","component":"Select","label":{"literalString":"Meal preference"},"value":{"path":"/meal"},"items":{"path":"/meals"}},
                    {"id":"localDropdown","component":"Dropdown","label":{"literalString":"Priority"},"value":{"literalString":"low"},"options":[
                      {"key":"low","label":"Low"},
                      {"key":"high","label":"High"}
                    ]},
                    {"id":"submit","component":"Button","label":{"literalString":"Submit"},"action":{"name":"submit_dropdown"}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/seat","value":"window"}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/meal","value":"salad"}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/meals","value":[
                    {"key":"salad","label":"Salad"},
                    {"key":"pasta","label":"Pasta"}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun chipSurfaceManager(root: String): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"$root","components":[
                    {"id":"tagChip","component":"Chip","label":{"literalString":"Urgent"},"action":{"name":"filter_tag","context":{"tag":{"literalString":"urgent"}}}},
                    {"id":"doneFilter","component":"FilterChip","label":{"literalString":"Done"},"value":{"path":"/filters/done"}},
                    {"id":"countBadge","component":"Badge","count":{"path":"/unreadCount"}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/filters/done","value":false}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/unreadCount","value":2}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun tabsAccordionSurfaceManager(root: String): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"$root","components":[
                    {"id":"projectTabs","component":"Tabs","default":"overview","items":[
                      {"key":"overview","label":{"literalString":"Overview"},"child":"overviewBody"},
                      {"key":"activity","label":{"literalString":"Activity"},"child":"activityBody"}
                    ]},
                    {"id":"overviewBody","component":"Text","text":{"literalString":"Overview body"}},
                    {"id":"activityBody","component":"Text","text":{"literalString":"Activity body"}},
                    {"id":"faqAccordion","component":"Accordion","localState":{"expanded_summary":true},"items":[
                      {"key":"summary","title":{"literalString":"Summary"},"child":"summaryBody"},
                      {"key":"details","title":{"literalString":"Details"},"child":"detailsBody"}
                    ]},
                    {"id":"summaryBody","component":"Text","text":{"literalString":"Summary body"}},
                    {"id":"detailsBody","component":"Text","text":{"literalString":"Details body"}}
                  ]}}
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}

private fun bookingFormSurfaceManager(): A2uiSurfaceManager {
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"form","components":[
                    {"id":"form","component":"Column","children":["partySize","submit"],"spacing":"sm"},
                    {"id":"partySize","component":"TextField","label":{"literalString":"Party size"},"value":{"path":"/partySize"},"textFieldType":"number"},
                    {"id":"submit","component":"Button","label":{"literalString":"Submit"},"action":{"name":"submit_booking","context":[
                      {"key":"partySize","value":{"path":"/partySize"}},
                      {"key":"reservationTime","value":{"path":"/reservationTime"}},
                      {"key":"seat","value":{"literalString":"window"}}
                    ]}}
                  ]}},
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/reservationTime","value":"2026-05-17T18:30"}}
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

private fun listViewSurfaceManager(includeItems: Boolean = true): A2uiSurfaceManager {
    val itemsPatch = if (includeItems) {
        """,
                  {"version":"v0.9","updateDataModel":{"surfaceId":"$SurfaceId","path":"/issues","value":[
                    {"id":"issue-1","title":"Alpha issue","subtitle":"Needs review","actionLabel":"Open Alpha"},
                    {"id":"issue-2","title":"Beta issue","subtitle":"Ready to merge","actionLabel":"Open Beta"}
                  ]}}
        """.trimIndent()
    } else {
        ""
    }
    val manager = A2uiSurfaceManager()
    manager.applyMessages(
        decodeA2uiMessages(
            A2uiProtocolJson.Default,
            A2uiProtocolJson.Default.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"$SurfaceId","catalogId":"basic"}},
                  {"version":"v0.9","updateComponents":{"surfaceId":"$SurfaceId","root":"issuesList","components":[
                    {"id":"issuesList","component":"ListView","itemTemplate":"issueCard","items":{"path":"/issues"},"itemKey":"id","spacing":"sm"},
                    {"id":"issueCard","component":"Card","child":"issueContent","cornerRadius":12,"elevation":1},
                    {"id":"issueContent","component":"Column","children":["issueTitle","issueSubtitle","openIssue"],"spacing":"xs"},
                    {"id":"issueTitle","component":"Text","variant":"h5","text":{"path":"title"}},
                    {"id":"issueSubtitle","component":"Text","text":{"path":"subtitle"}},
                    {"id":"openIssue","component":"Button","label":{"path":"actionLabel"},"action":{"name":"issue.open","context":[
                      {"key":"issueId","value":{"path":"id"}},
                      {"key":"title","value":{"path":"title"}}
                    ]}}
                  ]}}
                  $itemsPatch
                ]
                """.trimIndent(),
            ),
        )
    )
    return manager
}
