package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.ID
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.callTool
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.pkg
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject

class PluginHostProviderTest {
    @Test
    fun selectedExecutorReceivesConsentedInputsAndPublishedActionsAndStopsOnDisable() {
        val provider = RecordingDriverProvider()
        withHost(PluginDriverProviders(listOf(provider))) { fixture, runtime ->
            runtime.install(pkg())
            consentAndEnable(runtime)

            val spec = provider.specs.single()
            assertEquals(ID, spec.manifest.id)
            assertEquals(fixture.pluginsDir.resolve(ID).resolve("1.0.0"), spec.packageDir)
            assertEquals(setOf(PluginCapability.CANVAS_PLACE), spec.consentedCapabilities)
            assertEquals(ExternalToolResult.Success("injected plot"), runtime.callTool())
            assertEquals(listOf("plot"), provider.drivers.single().calls.map { it.action })
            assertEquals(PluginState.Active, runtime.health().single().state)

            assertIs<PluginHostResult.Done>(runtime.disable(PluginId(ID)))

            assertEquals(1, provider.drivers.single().stops)
            assertTrue(runtime.toolSource.tools.value.isEmpty())
            assertNull(runtime.live(PluginId(ID)))
        }
    }

    @Test
    fun noExecutorPersistsFaultAndUnpublishesTools() =
        withHost(PluginDriverProviders(emptyList())) { fixture, runtime ->
            runtime.install(pkg())
            consentAndEnable(runtime)

            assertFaultedWithoutTools(runtime)
            assertTrue(runtime.health().single().lastFault.orEmpty().contains("unsupported"))
            val saved = PluginRegistryState.decode(Files.readString(fixture.pluginsDir.resolve(PluginPackageStore.REGISTRY_FILE)))
            assertIs<PluginState.Faulted>(saved.plugins.single().state)
        }

    @Test
    fun ambiguousAndIncompatibleExecutorsNeverCreateDriversOrPublishTools() {
        val first = RecordingDriverProvider(id = "test.first")
        val second = RecordingDriverProvider(id = "test.second")
        val incompatible = RecordingDriverProvider(contractVersions = setOf(2))
        for (providers in listOf(listOf(first, second), listOf(incompatible))) {
            withHost(PluginDriverProviders(providers)) { _, runtime ->
                runtime.install(pkg())
                consentAndEnable(runtime)
                assertFaultedWithoutTools(runtime)
            }
        }
        assertTrue(first.specs.isEmpty())
        assertTrue(second.specs.isEmpty())
        assertTrue(incompatible.specs.isEmpty())
    }

    @Test
    fun unconsentedPluginNeverReachesTheProviderFactory() {
        val provider = RecordingDriverProvider()
        withHost(PluginDriverProviders(listOf(provider))) { _, runtime ->
            runtime.install(pkg())

            assertIs<PluginHostResult.Refused>(runtime.enable(PluginId(ID)))

            assertTrue(provider.specs.isEmpty())
            assertTrue(runtime.toolSource.tools.value.isEmpty())
        }
    }

    @Test
    fun throwingFactoryAndStartPersistFaultWithoutLiveDriversOrAdvertisedTools() {
        for (factoryFailure in listOf(true, false)) {
            val provider = RecordingDriverProvider().apply {
                createFailure = { if (factoryFailure) IllegalStateException("factory refused") else null }
                configureDriver = { it.startFailure = IllegalStateException("start refused") }
            }
            withHost(PluginDriverProviders(listOf(provider))) { fixture, runtime ->
                runtime.install(pkg())
                consentAndEnable(runtime)

                assertFaultedWithoutTools(runtime)
                val saved = PluginRegistryState.decode(Files.readString(fixture.pluginsDir.resolve(PluginPackageStore.REGISTRY_FILE)))
                assertIs<PluginState.Faulted>(saved.plugins.single().state)
                assertEquals(if (factoryFailure) 0 else 1, provider.drivers.sumOf { it.stops })
            }
        }
    }

    @Test
    fun oneFailingEnabledPluginDoesNotAbortStartupOfAnotherPlugin() {
        val initial = RecordingDriverProvider()
        withHost(PluginDriverProviders(listOf(initial))) { fixture, runtime ->
            runtime.install(pkg())
            consentAndEnable(runtime)
            val otherId = PluginId("letta.other")
            runtime.install(pkg(id = otherId.value))
            assertIs<PluginHostResult.Done>(runtime.consent(otherId, setOf(PluginCapability.CANVAS_PLACE), emptySet()))
            assertIs<PluginHostResult.Done>(runtime.enable(otherId))
            runtime.close()
            val selected = RecordingDriverProvider().apply {
                createFailure = { if (it.manifest.id == ID) IllegalStateException("first plugin refused") else null }
            }
            val resumed = fixture.runtime(PluginDriverProviders(listOf(selected)))
            try {
                resumed.start()

                assertIs<PluginState.Faulted>(resumed.health().single { it.pluginId == ID }.state)
                assertEquals(PluginState.Active, resumed.health().single { it.pluginId == otherId.value }.state)
                assertEquals(listOf("other_plot"), resumed.toolSource.tools.value.map { it.name })
                assertEquals(ExternalToolResult.Success("injected plot"), resumed.callTool("other_plot"))
            } finally {
                resumed.close()
            }
        }
    }

    @Test
    fun cancellationDuringStartStopsThePartialDriverWithoutRecordingAFault() {
        val started = CompletableDeferred<Unit>()
        val provider = RecordingDriverProvider().apply {
            configureDriver = { driver ->
                driver.beforeStart = { started.complete(Unit); awaitCancellation() }
                driver.beforeStop = { yield() }
            }
        }
        withHost(PluginDriverProviders(listOf(provider))) { _, runtime ->
            runtime.install(pkg())
            runtime.consent(PluginId(ID), setOf(PluginCapability.CANVAS_PLACE), emptySet())
            coroutineScope {
                val enabling = launch { runtime.enable(PluginId(ID)) }
                started.await()
                enabling.cancelAndJoin()
            }

            assertEquals(1, provider.drivers.single().stops)
            assertNull(runtime.live(PluginId(ID)))
            assertEquals(PluginState.Enabled, runtime.health().single().state)
            assertNull(runtime.health().single().lastFault)
        }
    }

    @Test
    fun fatalFactoryAndStartFailuresEscapeAfterPartialDriverCleanup() {
        for (factoryFailure in listOf(true, false)) {
            val provider = RecordingDriverProvider().apply {
                createFailure = { if (factoryFailure) AssertionError("fatal factory") else null }
                configureDriver = { it.startFailure = AssertionError("fatal start") }
            }
            withHost(PluginDriverProviders(listOf(provider))) { _, runtime ->
                runtime.install(pkg())
                runtime.consent(PluginId(ID), setOf(PluginCapability.CANVAS_PLACE), emptySet())

                assertFailsWith<AssertionError> { runtime.enable(PluginId(ID)) }

                assertNull(runtime.live(PluginId(ID)))
                assertEquals(if (factoryFailure) 0 else 1, provider.drivers.sumOf { it.stops })
            }
        }
    }

    @Test
    fun launchFailureReasonsAreScrubbedBeforeTheyCanBePersisted() {
        val spec = PluginLaunchSpec(
            ProcessDriverFixture.manifest("normal"),
            Path.of("."),
            JsonObject(emptyMap()),
            PluginSecrets().with("token", "private-launch-token"),
            emptySet(),
        )

        assertEquals(SecretScrubber.REFUSED, spec.failureReason(IllegalStateException("failed with private-launch-token")))
    }

    private fun withHost(
        providers: PluginDriverProviders,
        block: suspend (PluginHostFixture, PluginHostRuntime) -> Unit,
    ) = runBlocking {
        val fixture = PluginHostFixture()
        val runtime = fixture.runtime(providers)
        try {
            runtime.start()
            block(fixture, runtime)
        } finally {
            runtime.close()
            fixture.close()
        }
    }

    private suspend fun consentAndEnable(runtime: PluginHostRuntime) {
        assertIs<PluginHostResult.Done>(runtime.consent(PluginId(ID), setOf(PluginCapability.CANVAS_PLACE), emptySet()))
        assertIs<PluginHostResult.Done>(runtime.enable(PluginId(ID)))
    }

    private fun assertFaultedWithoutTools(runtime: PluginHostRuntime) {
        assertIs<PluginState.Faulted>(runtime.health().single().state)
        assertTrue(runtime.toolSource.tools.value.isEmpty())
        assertNull(runtime.live(PluginId(ID)))
    }
}
