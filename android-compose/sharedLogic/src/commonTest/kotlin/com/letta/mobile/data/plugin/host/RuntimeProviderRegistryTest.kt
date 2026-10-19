package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeProviderRegistryTest {
    @Test
    fun exactRuntimeAndMajorSelectTheOpaqueHostBinding() {
        val registry = RuntimeProviderRegistry(listOf(
            registration("process-v1", "process", setOf(1)),
            registration("service-v1", "service", setOf(1)),
            registration("process-v2", "process", setOf(2)),
        ))

        assertEquals("process-v1", registry.resolve(manifest()).getOrThrow())
        assertEquals("process-v2", registry.resolve(manifest(major = 2)).getOrThrow())
        assertEquals("service-v1", registry.resolve(manifest(PluginRuntime.Service("wss://example.com"))).getOrThrow())
    }

    @Test
    fun unsupportedRuntimeAndVersionExplainThePluginAndRegisteredProviders() {
        val registry = RuntimeProviderRegistry(listOf(registration("process-v1")))
        val unsupported = listOf(manifest(major = 2), manifest(PluginRuntime.Jvm("plugin.jar", "Example")))
        for (request in unsupported) {
            val message = registry.resolve(request).exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("unsupported"))
            assertTrue(message.contains(request.id))
            assertTrue(message.contains("process-v1"))
            assertTrue(message.contains("contract ${request.contract.version}"))
        }
    }

    @Test
    fun emptyInventoryHasNoImplicitExecutor() {
        val registry = RuntimeProviderRegistry<String>(emptyList())

        assertTrue(registry.resolve(manifest()).isFailure)
    }

    @Test
    fun ambiguousRegistrationsFailWithTheSameDiagnosticInEitherOrder() {
        val first = registration("first")
        val second = registration("second")
        val forward = RuntimeProviderRegistry(listOf(first, second)).resolve(manifest()).exceptionOrNull()?.message
        val reversed = RuntimeProviderRegistry(listOf(second, first)).resolve(manifest()).exceptionOrNull()?.message

        assertEquals(forward, reversed)
        assertTrue(forward.orEmpty().contains("ambiguous"))
        assertTrue(forward.orEmpty().contains("first"))
        assertTrue(forward.orEmpty().contains("second"))
    }

    @Test
    fun duplicateProviderIdentitiesAndBlankIdentitiesAreInvalid() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeProviderRegistry(listOf(registration("same"), registration("same", "service")))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeProviderRegistry(listOf(registration("")))
        }
    }

    @Test
    fun registryCapturesTheListAndContractVersions() {
        val versions = mutableSetOf(1)
        val input = mutableListOf(registration("original", versions = versions))
        val registry = RuntimeProviderRegistry(input)
        versions.clear()
        versions += 2
        input.clear()
        input += registration("replacement", versions = setOf(2))

        assertEquals("original", registry.resolve(manifest()).getOrThrow())
        assertTrue(registry.resolve(manifest(major = 2)).isFailure)
    }

    private fun registration(
        name: String,
        kind: String = "process",
        versions: Set<Int> = setOf(1),
    ) = RuntimeProviderRegistration(name, PluginId(name), kind, versions)

    private fun manifest(runtime: PluginRuntime = PluginRuntime.Process("node"), major: Int = 1) = PluginManifest(
        manifestVersion = 1,
        id = "letta.example",
        name = "Example",
        version = "1.0.0",
        publisher = "letta",
        contract = PluginContract(major),
        runtime = runtime,
    )
}
