package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.CanvasPlugin
import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The kit passes the sample plugin and fails each purposely broken one on exactly the rule it breaks. */
class PluginConformanceTest {
    private fun assertBreaks(
        rule: ConformanceRule,
        plugin: CanvasPlugin,
        manifest: ConformanceManifest = SamplePlugin.manifest,
        options: ConformanceOptions = ConformanceOptions(),
    ) {
        val report = PluginConformance.run(plugin, manifest, options)
        assertEquals(setOf(rule), report.brokenRules, report.toString())
    }

    private fun manifestWithout(capability: String): ConformanceManifest =
        SamplePlugin.manifest.copy(capabilities = SamplePlugin.manifest.capabilities - capability)

    @Test
    fun `the sample plugin keeps the contract`() {
        val report = PluginConformance.run(SamplePlugin(), SamplePlugin.manifest)
        report.assertPassed()
        assertTrue(report.passed)
    }

    @Test
    fun `a report that did not pass fails its assertion with every finding`() {
        val report = PluginConformance.run(ThrowsOnActivate(), SamplePlugin.manifest)
        val error = assertFailsWith<AssertionError> { report.assertPassed() }
        assertTrue("activate threw" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `lifecycle - activate must not throw`() = assertBreaks(ConformanceRule.LIFECYCLE, ThrowsOnActivate())

    @Test
    fun `lifecycle - deactivate tolerates a second call`() = assertBreaks(ConformanceRule.LIFECYCLE, DeactivateOnlyOnce())

    @Test
    fun `lifecycle - no work before activate`() = assertBreaks(ConformanceRule.LIFECYCLE, EmitsBeforeActivate())

    @Test
    fun `lifecycle - no host use after deactivate`() = assertBreaks(ConformanceRule.LIFECYCLE, UsesHostAfterDeactivate())

    @Test
    fun `lifecycle - not failed right after activate`() = assertBreaks(ConformanceRule.LIFECYCLE, FailedAfterActivate())

    @Test
    fun `actions - a declared action must not throw`() = assertBreaks(ConformanceRule.ACTION, ThrowsInDeclaredAction())

    @Test
    fun `actions - every declared action is handled`() = assertBreaks(ConformanceRule.ACTION, ForgetsDeclaredAction())

    @Test
    fun `actions - an undeclared action answers unknown_action`() = assertBreaks(ConformanceRule.ACTION, AcceptsUndeclaredAction())

    @Test
    fun `actions - a supplied input must hold the action's schema`() = assertBreaks(
        ConformanceRule.ACTION,
        SamplePlugin(),
        options = ConformanceOptions(inputs = mapOf("place" to buildJsonObject { put("label", JsonPrimitive(5)) })),
    )

    @Test
    fun `emits - only declared kinds`() = assertBreaks(ConformanceRule.EMIT, EmitsUndeclaredKind())

    @Test
    fun `emits - props hold the kind's schema`() = assertBreaks(ConformanceRule.EMIT, EmitsInvalidProps())

    @Test
    fun `emits - at the kind's schema version`() = assertBreaks(ConformanceRule.EMIT, EmitsStaleVersion())

    @Test
    fun `emits - only the plugin's own elements are removed`() = assertBreaks(ConformanceRule.EMIT, RemovesForeignElement())

    @Test
    fun `capabilities - the network only to declared origins`() = assertBreaks(ConformanceRule.CAPABILITY, CallsUndeclaredOrigin())

    @Test
    fun `capabilities - reading elements needs canvas read`() =
        assertBreaks(ConformanceRule.CAPABILITY, ReadsElements(), manifestWithout(ConformanceManifest.CANVAS_READ))

    @Test
    fun `capabilities - emitting needs canvas place`() =
        assertBreaks(ConformanceRule.CAPABILITY, SamplePlugin(), manifestWithout(ConformanceManifest.CANVAS_PLACE))

    @Test
    fun `deadlines - an action answers in time`() = assertBreaks(
        ConformanceRule.DEADLINE,
        SlowAction(),
        options = ConformanceOptions(deadlines = mapOf(LcpMethod.INVOKE to 200L)),
    )

    @Test
    fun `deadlines - health answers in time even when it blocks a thread`() = assertBreaks(
        ConformanceRule.DEADLINE,
        BlockingHealth(),
        options = ConformanceOptions(deadlines = mapOf(LcpMethod.HEALTH to 150L)),
    )

    @Test
    fun `secrets - never in logs`() = assertBreaks(ConformanceRule.SECRET_LEAK, LogsSecret())

    @Test
    fun `secrets - never in URLs`() = assertBreaks(ConformanceRule.SECRET_LEAK, SecretInUrl())

    @Test
    fun `secrets - never in results`() = assertBreaks(ConformanceRule.SECRET_LEAK, SecretInResult())
}
