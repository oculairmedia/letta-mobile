@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import io.ak1.drawbox.domain.model.Viewport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeElementRegistrationTest {
    @Test
    fun registrationsAfterInitialCompositionReplaceAndRemoveTheRenderer() = runComposeUiTest {
        val registered = mutableStateOf<PluginElementRenderer?>(null)
        val view = PluginElementView(PluginCardFixtures.element("existing", type = "ext:reader/note"))
        val chrome = PluginElementChrome(Modifier, null) { error(it) }
        setContent {
            ProvidePluginElementRenderers(registrations = {
                registered.value?.let { register("ext:reader/", it) } ?: this
            }) {
                val renderer = LocalPluginElementRenderers.current.registeredFor(view.element.type)
                if (renderer == null) Text("Fallback", Modifier.testTag("runtime-element"))
                else renderer(view, chrome)
            }
        }
        onNodeWithTag("runtime-element").assertTextEquals("Fallback")
        runOnIdle { registered.value = { _, _ -> Text("First", Modifier.testTag("runtime-element")) } }
        onNodeWithTag("runtime-element").assertTextEquals("First")
        runOnIdle { registered.value = { _, _ -> Text("Second", Modifier.testTag("runtime-element")) } }
        onNodeWithTag("runtime-element").assertTextEquals("Second")
        runOnIdle { registered.value = null }
        onNodeWithTag("runtime-element").assertTextEquals("Fallback")
    }
    @Test
    fun aFaultedElementRecoversWithANewRendererAndItsNewFaultCallbackWorks() = runComposeUiTest {
        val session = PluginCardFixtures.session()
        val element = PluginCardFixtures.element("runtime-existing", type = "ext:reader/note")
        PluginCardFixtures.place(session, element)
        val originalScene = session.document.value!!.sceneJson
        val updates = MutableStateFlow("live")
        var active = 0
        fun renderer(label: String, fails: Boolean = false): PluginElementRenderer = { _, chrome ->
            val live by updates.collectAsState()
            val remembered = remember { label }
            DisposableEffect(Unit) { active++; onDispose { active-- } }
            LaunchedEffect(Unit) { if (fails) chrome.fail("renderer unavailable") }
            Text("$remembered:$live", Modifier.testTag("runtime-element"))
        }
        val registered = mutableStateOf<PluginElementRenderer?>(renderer("First"))
        setContent {
            ProvidePluginElementRenderers(registrations = {
                registered.value?.let { register("ext:reader/", it) } ?: this
            }) {
                MaterialTheme {
                    val document by session.document.collectAsState()
                    CanvasPluginLayer(rememberPluginBoard(session, document, null), Viewport())
                }
            }
        }
        onNodeWithTag("runtime-element").assertTextEquals("First:live")
        runOnIdle { registered.value = renderer("Broken", fails = true) }
        onNodeWithTag(CanvasPluginTestTags.card(element.id)).assertExists()
        runOnIdle { assertEquals(0, active); assertEquals(0, updates.subscriptionCount.value) }
        runOnIdle { registered.value = renderer("Second") }
        onNodeWithTag("runtime-element").assertTextEquals("Second:live")
        runOnIdle { registered.value = renderer("Third") }
        onNodeWithTag("runtime-element").assertTextEquals("Third:live")
        runOnIdle { registered.value = renderer("Broken again", fails = true) }
        onNodeWithTag(CanvasPluginTestTags.card(element.id)).assertExists()
        runOnIdle { registered.value = renderer("Restored") }
        onNodeWithTag("runtime-element").assertTextEquals("Restored:live")
        runOnIdle {
            assertEquals(1, active)
            assertEquals(1, updates.subscriptionCount.value)
            registered.value = null
        }
        onNodeWithTag(CanvasPluginTestTags.card(element.id)).assertExists()
        runOnIdle {
            assertEquals(0, active)
            assertEquals(0, updates.subscriptionCount.value)
            assertEquals(originalScene, session.document.value!!.sceneJson)
        }
    }

}
