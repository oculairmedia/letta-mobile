package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope

class PluginDriverProvidersTest {
    @Test
    fun exactRuntimeAndContractSelectTheProviderWithoutCreatingADriver() {
        val process = RecordingDriverProvider()
        val service = RecordingDriverProvider(id = "test.service", runtimeKind = "service")
        val providers = PluginDriverProviders(listOf(service, process))

        assertSame(process, providers.resolve(ProcessDriverFixture.manifest("normal")).getOrThrow())
        assertTrue(process.specs.isEmpty())
        assertTrue(service.specs.isEmpty())
    }

    @Test
    fun unsupportedRuntimeAndContractNeverFallBackToAnotherProvider() {
        val process = RecordingDriverProvider()
        val providers = PluginDriverProviders(listOf(process))
        val manifest = ProcessDriverFixture.manifest("normal")

        assertTrue(providers.resolve(manifest.copy(runtime = PluginRuntime.Service("wss://example.com"))).isFailure)
        assertTrue(providers.resolve(manifest.copy(contract = PluginContract(2))).isFailure)
        assertTrue(PluginDriverProviders(emptyList()).resolve(manifest).isFailure)
        assertTrue(process.specs.isEmpty())
    }

    @Test
    fun overlappingMatchesAreRefusedRegardlessOfRegistrationOrder() {
        val first = RecordingDriverProvider(id = "test.first")
        val second = RecordingDriverProvider(id = "test.second")

        for (order in listOf(listOf(first, second), listOf(second, first))) {
            val refusal = PluginDriverProviders(order).resolve(ProcessDriverFixture.manifest("normal")).exceptionOrNull()
            assertTrue(refusal?.message.orEmpty().contains("ambiguous"))
            assertTrue(refusal?.message.orEmpty().contains(first.id))
            assertTrue(refusal?.message.orEmpty().contains(second.id))
        }
        assertTrue(first.specs.isEmpty())
        assertTrue(second.specs.isEmpty())
    }

    @Test
    fun duplicateProviderIdentitiesAreInvalidEvenForDifferentRuntimes() {
        assertFailsWith<IllegalArgumentException> {
            PluginDriverProviders(listOf(RecordingDriverProvider(), RecordingDriverProvider(runtimeKind = "service")))
        }
    }

    @Test
    fun resolutionUsesTheCapturedMetadataEvenIfAProviderMutatesItsProperties() {
        val versions = mutableSetOf(1)
        val provider = object : PluginDriverProvider {
            override var id = "test.original"
            override var runtimeKind = "process"
            override val contractVersions: Set<Int> = versions

            override fun create(
                spec: PluginLaunchSpec,
                handlers: LcpHostHandlers,
                scope: CoroutineScope,
                onStderr: (String) -> Unit,
            ): PluginDriver = error("resolution must not construct a driver")
        }
        val providers = PluginDriverProviders(listOf(provider))
        provider.id = "test.changed"
        provider.runtimeKind = "service"
        versions.clear()
        versions += 2

        assertSame(provider, providers.resolve(ProcessDriverFixture.manifest("normal")).getOrThrow())
        assertTrue(providers.resolve(ProcessDriverFixture.manifest("normal").copy(contract = PluginContract(2))).isFailure)
    }
}
