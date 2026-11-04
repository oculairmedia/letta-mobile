@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

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
}
