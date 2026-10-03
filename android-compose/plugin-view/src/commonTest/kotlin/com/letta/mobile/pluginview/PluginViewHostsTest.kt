package com.letta.mobile.pluginview

import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.ui.canvas.plugin.PluginElementRenderers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which element kinds get the live renderer (letta-mobile-s416w.13). */
class PluginViewHostsTest {
    private val plugin = PluginViewPlugin(PluginViewFixtures.manifest)
    private val environment = PluginViewEnvironment(canvasId = "canvas-1", platform = ViewPlatform.ANDROID)

    @Test
    fun onlyKindsWhosePageTheManifestHasAreLive() {
        assertEquals(mapOf("widget" to "widget"), plugin.livePages)
    }

    @Test
    fun registeringAddsOneRendererPerLiveKindAndNothingElse() {
        val renderers = PluginViewHosts.register(PluginElementRenderers.Core, listOf(plugin), environment)

        assertEquals(setOf("ext:letta.example/widget"), renderers.prefixes)
        assertNotNull(renderers.registeredFor("ext:letta.example/widget"))
        assertNull(renderers.registeredFor("ext:letta.example/plain"))
        assertNull(renderers.registeredFor("ext:letta.other/widget"))
        val none = PluginViewHosts.register(PluginElementRenderers.Core, emptyList(), environment)
        assertEquals(PluginElementRenderers.Core.prefixes, none.prefixes)
    }

    @Test
    fun aPageSeesOnlyDeclaredSettingsAndTheEnvironmentKnowsWhenItIsOffline() {
        val withSettings = plugin.copy(settings = JsonObject(mapOf("undeclared" to JsonPrimitive("x"))))
        assertEquals(JsonObject(emptyMap()), withSettings.publicSettings)

        assertFalse(environment.online)
        assertTrue(environment.copy(transport = FakePluginViewTransport()).online)
    }
}
