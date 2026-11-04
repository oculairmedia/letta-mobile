package com.letta.mobile.data.plugin

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.model.view.RuntimeViewMountId
import com.letta.mobile.data.plugin.runtimeview.RuntimeObjectReference
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewBinding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeViewBindingTest {
    @Test
    fun configurationPreservesMountReferenceAndConsentAcrossRoundTripAndRemoval() {
        val initial = enabled()
        val bound = configure(initial, binding)
        val configured = binding.copy(
            viewId = RuntimeViewId("reader.alternative"), configuration = JsonObject(mapOf("unknown" to JsonPrimitive(42))),
        )
        val updated = configure(bound, configured)
        assertEquals(listOf(configured), updated[OWNER]!!.viewBindings)
        assertEquals(initial[OWNER]!!.current, updated[OWNER]!!.current)
        assertEquals(updated, PluginRegistryState.decode(updated.encode()))
        val removed = assertIs<PluginTransition.Applied>(PluginRegistryReducer.unbindView(updated, OWNER, binding.mountId)).state
        assertEquals(initial, removed)
        assertEquals(emptyList(), apply(updated, PluginCommand.Remove(OWNER)).plugins)
    }

    @Test
    fun configurationCannotRetargetAnOwnedMountOrClaimAnotherPluginsMount() {
        val initial = enabled()
        val bound = configure(initial, binding)
        val retargeted = binding.copy(objectReference = binding.objectReference.copy(id = "another"))
        assertEquals(PluginRefusal.INVALID_STATE, assertIs<PluginTransition.Refused>(
            PluginRegistryReducer.configureView(bound, OWNER, retargeted),
        ).refusal)
        val otherManifest = PluginRegistryFixtures.manifest("/id" to JsonPrimitive("other.reader"))
        val other = PluginRegistryFixtures.registryOf(otherManifest).plugins.single()
        val withOther = apply(bound.copy(plugins = bound.plugins + other), PluginCommand.Enable(other.pluginId))
        assertEquals(PluginRefusal.INVALID_STATE, assertIs<PluginTransition.Refused>(
            PluginRegistryReducer.configureView(withOther, other.pluginId, binding),
        ).refusal)
        assertEquals(listOf(binding), bound[OWNER]!!.viewBindings)
    }

    @Test
    fun configurationCannotEnableOrConsentToAnUnavailablePlugin() {
        val initial = PluginRegistryFixtures.registryOf(PluginRegistryFixtures.example)
        assertEquals(PluginRefusal.INVALID_STATE, assertIs<PluginTransition.Refused>(
            PluginRegistryReducer.configureView(initial, OWNER, binding),
        ).refusal)
        val notReady = enabled().let { state ->
            state.copy(plugins = state.plugins.map { it.copy(settingsSummary = PluginSettingsSummary(missingRequired = setOf("baseUrl"))) })
        }
        assertEquals(PluginRefusal.NOT_READY, assertIs<PluginTransition.Refused>(
            PluginRegistryReducer.configureView(notReady, OWNER, binding),
        ).refusal)
        val unknown = PluginRegistryReducer.configureView(PluginRegistryState(), OWNER, binding)
        assertEquals(PluginRefusal.UNKNOWN_PLUGIN, assertIs<PluginTransition.Refused>(unknown).refusal)
        val unconsented = enabled().let { state ->
            state.copy(plugins = state.plugins.map { it.copy(current = it.current.copy(consent = PluginConsent.none())) })
        }
        assertEquals(PluginRefusal.CONSENT_REQUIRED, assertIs<PluginTransition.Refused>(
            PluginRegistryReducer.configureView(unconsented, OWNER, binding),
        ).refusal)
    }

    @Test
    fun anUpdatingPluginKeepsItsMountUntilTheUpdateCompletes() {
        val bound = configure(enabled(), binding)
        val staged = apply(bound, PluginCommand.StageUpdate(
            PluginRegistryFixtures.version("1.3.0"), PluginRegistryFixtures.SHA_B, null, PluginRegistryFixtures.NOW + 1,
        ))
        val configured = PluginRegistryReducer.configureView(staged, OWNER, binding)
        assertEquals(PluginRefusal.UPDATE_IN_PROGRESS, assertIs<PluginTransition.Refused>(configured).refusal)
        val removed = PluginRegistryReducer.unbindView(staged, OWNER, binding.mountId)
        assertEquals(PluginRefusal.UPDATE_IN_PROGRESS, assertIs<PluginTransition.Refused>(removed).refusal)
        val completed = apply(staged, PluginCommand.CompleteUpdate(OWNER))
        assertEquals(listOf(binding), completed[OWNER]!!.viewBindings)
    }

    private fun configure(state: PluginRegistryState, binding: RuntimeViewBinding): PluginRegistryState =
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.configureView(state, OWNER, binding)).state

    private fun enabled(): PluginRegistryState = apply(
        PluginRegistryFixtures.registryOf(PluginRegistryFixtures.example), PluginCommand.Enable(OWNER),
    )

    private fun apply(state: PluginRegistryState, command: PluginCommand): PluginRegistryState =
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(state, command)).state

    private companion object {
        val OWNER = PluginId("letta.example")
        val binding = RuntimeViewBinding(
            RuntimeViewMountId("detail"), RuntimeObjectReference("external", "existing"), RuntimeViewId("reader.detail"), 1,
        )
    }
}
