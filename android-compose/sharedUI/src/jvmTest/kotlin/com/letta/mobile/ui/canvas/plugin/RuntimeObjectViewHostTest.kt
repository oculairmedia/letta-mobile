@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCommand
import com.letta.mobile.data.plugin.PluginConsent
import com.letta.mobile.data.plugin.PluginManifestParser
import com.letta.mobile.data.plugin.PluginManifestResult
import com.letta.mobile.data.plugin.PluginRegistryReducer
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginTransition
import com.letta.mobile.data.plugin.runtimeview.RuntimeObjectReference
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewBinding
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewDescriptor
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewObject
import com.letta.mobile.ui.plugin.RuntimeObjectView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeObjectViewHostTest {
    @Test
    fun aNonCanvasViewBindsAfterShellCreationAndReplacesAndUnregistersWithoutDataLoss() = runComposeUiTest {
        val canonical = MutableStateFlow(objectValue)
        val updates = MutableStateFlow("live")
        val state = mutableStateOf(installed())
        val registrations = mutableStateOf(CanvasDocumentViewers.Core)
        var active = 0
        var disposed = 0
        fun reader(name: String) = RuntimeObjectView(descriptor) { value, binding, modifier, _ ->
            val live by updates.collectAsState()
            val remembered = remember { name }
            DisposableEffect(Unit) {
                active++
                onDispose { active--; disposed++ }
            }
            Text("$remembered:$live:${binding.configuration}: ${value.content}", modifier.testTag(CONTENT))
        }
        setContent {
            ProvideCanvasDocumentViewers(registrations = { registrations.value }) {
                val value by canonical.collectAsState()
                RuntimeObjectViewHost(MOUNT, value, state.value) { reason, modifier ->
                    Text(reason.name, modifier.testTag(CONTENT))
                }
            }
        }
        onNodeWithTag(CONTENT).assertTextEquals("UNBOUND")
        runOnIdle {
            registrations.value = registrations.value.register(reader("First"))
            state.value = configure(state.value, binding)
            assertEquals(listOf(descriptor), registrations.value.runtimeViews.map { it.descriptor })
        }
        onNodeWithTag(CONTENT).assertTextEquals("First:live:{}: $CANONICAL_JSON")
        runOnIdle { updates.value = "updated" }
        onNodeWithTag(CONTENT).assertTextEquals("First:updated:{}: $CANONICAL_JSON")
        val config = Json.parseToJsonElement("""{"mode":"compact","capabilities":["write"],"code":"ignored"}""").jsonObject
        runOnIdle {
            state.value = configure(state.value, binding.copy(configuration = config))
        }
        onNodeWithTag(CONTENT).assertTextEquals("First:updated:$config: $CANONICAL_JSON")
        runOnIdle {
            assertEquals(1, active)
            assertEquals(1, disposed)
            registrations.value = registrations.value.register(reader("Second"))
        }
        onNodeWithTag(CONTENT).assertTextEquals("Second:updated:$config: $CANONICAL_JSON")
        runOnIdle {
            assertEquals(1, active)
            assertEquals(1, updates.subscriptionCount.value)
            registrations.value = registrations.value.unregister(descriptor.id)
        }
        onNodeWithTag(CONTENT).assertTextEquals("MISSING_VIEW")
        runOnIdle {
            assertEquals(0, active)
            assertEquals(0, updates.subscriptionCount.value)
            assertCanonicalPreserved(canonical.value)
            assertEquals(binding.copy(configuration = config), state.value[OWNER]!!.viewBindings.single())
            assertEquals(installed()[OWNER]!!.consent, state.value[OWNER]!!.consent)
        }
        runOnIdle { registrations.value = registrations.value.register(reader("Restored")) }
        onNodeWithTag(CONTENT).assertTextEquals("Restored:updated:$config: $CANONICAL_JSON")
        runOnIdle { state.value = assertIs<PluginTransition.Applied>(PluginRegistryReducer.unbindView(state.value, OWNER, MOUNT)).state }
        onNodeWithTag(CONTENT).assertTextEquals("UNBOUND")
        runOnIdle {
            assertEquals(0, active)
            assertEquals(0, updates.subscriptionCount.value)
            assertEquals(4, disposed)
            assertCanonicalPreserved(canonical.value)
        }
    }

    @Test
    fun schemaAndViewContractIncompatibilityAndReportedFailureHaveExplicitFallback() = runComposeUiTest {
        val registrations = mutableStateOf(CanvasDocumentViewers.Core)
        val state = mutableStateOf(configure(installed(), binding))
        var active = 0
        fun reader(metadata: RuntimeViewDescriptor, failed: Boolean = false) = RuntimeObjectView(metadata) { _, _, modifier, fail ->
            DisposableEffect(Unit) { active++; onDispose { active-- } }
            LaunchedEffect(Unit) { if (failed) fail("subscription unavailable") }
            Text("Ready", modifier.testTag(CONTENT))
        }
        setContent {
            ProvideCanvasDocumentViewers(registrations = { registrations.value }) {
                RuntimeObjectViewHost(MOUNT, objectValue, state.value) { reason, modifier -> Text(reason.name, modifier.testTag(CONTENT)) }
            }
        }
        runOnIdle { registrations.value = registrations.value.register(reader(descriptor.copy(schemaVersions = setOf(8)))) }
        onNodeWithTag(CONTENT).assertTextEquals("INCOMPATIBLE")
        runOnIdle { registrations.value = registrations.value.register(reader(descriptor.copy(contractVersion = 2))) }
        onNodeWithTag(CONTENT).assertTextEquals("INCOMPATIBLE")
        runOnIdle { state.value = configure(state.value, binding.copy(contractVersion = 2)) }
        onNodeWithTag(CONTENT).assertTextEquals("INCOMPATIBLE")
        runOnIdle {
            state.value = configure(state.value, binding)
            registrations.value = registrations.value.register(reader(descriptor, failed = true))
        }
        onNodeWithTag(CONTENT).assertTextEquals("FAILED")
        runOnIdle { assertEquals(0, active) }
        runOnIdle { registrations.value = registrations.value.register(reader(descriptor)) }
        onNodeWithTag(CONTENT).assertTextEquals("Ready")
        runOnIdle { state.value = apply(state.value, PluginCommand.Disable(OWNER)) }
        onNodeWithTag(CONTENT).assertTextEquals("UNAVAILABLE_OWNER")
        runOnIdle { assertEquals(0, active) }
    }

    @Test
    fun absentOrDifferentObjectsNeverReachTheRenderer() = runComposeUiTest {
        val value = mutableStateOf<RuntimeViewObject?>(null)
        val state = configure(installed(), binding)
        val view = RuntimeObjectView(descriptor) { _, _, _, _ -> error("must not render another object") }
        setContent {
            ProvideCanvasDocumentViewers(registrations = { register(view) }) {
                RuntimeObjectViewHost(MOUNT, value.value, state) { reason, modifier -> Text(reason.name, modifier.testTag(CONTENT)) }
            }
        }
        onNodeWithTag(CONTENT).assertTextEquals("MISSING_OBJECT")
        runOnIdle { value.value = objectValue.copy(reference = reference.copy(source = "another-authority")) }
        onNodeWithTag(CONTENT).assertTextEquals("OBJECT_MISMATCH")
    }

    private fun assertCanonicalPreserved(value: RuntimeViewObject) {
        assertEquals(reference, value.reference)
        assertEquals(CANONICAL_JSON, value.content.toString())
    }

    private fun configure(state: PluginRegistryState, binding: RuntimeViewBinding): PluginRegistryState =
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.configureView(state, OWNER, binding)).state

    private fun installed(): PluginRegistryState {
        val manifest = assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse("""
            {"manifestVersion":1,"id":"test.reader","name":"Reader","version":"1.0.0","publisher":"test",
             "contract":{"version":1},"runtime":{"kind":"jvm","jar":"reader.jar","entry":"test.Reader"}}
        """)).manifest
        val installed = apply(PluginRegistryState(), PluginCommand.Install(
            manifest, "a".repeat(64), PluginConsent.forManifest(manifest, 0), 0,
        ))
        return apply(installed, PluginCommand.Enable(OWNER))
    }

    private fun apply(state: PluginRegistryState, command: PluginCommand): PluginRegistryState =
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(state, command)).state

    private companion object {
        const val MOUNT = "workspace.detail"
        const val CONTENT = "runtime-object-content"
        val OWNER = PluginId("test.reader")
        val reference = RuntimeObjectReference("beads:external-project", "existing-42")
        const val CANONICAL_JSON = """{"id":"existing-42","content":"Keep this text","_future":{"opaque":[1,2,3]}}"""
        val objectValue = RuntimeViewObject(reference, "issue", 7, Json.parseToJsonElement(CANONICAL_JSON).jsonObject)
        val binding = RuntimeViewBinding(MOUNT, reference, "test.reader.detail", 1)
        val descriptor = RuntimeViewDescriptor(binding.viewId, OWNER.value, "issue", setOf(7), 1)
    }
}
