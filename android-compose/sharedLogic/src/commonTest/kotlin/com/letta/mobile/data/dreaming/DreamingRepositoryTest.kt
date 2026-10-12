package com.letta.mobile.data.dreaming

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-iczm7: dreaming over a direct App Server and over the Iroh host. */
class DreamingRepositoryTest {
    private val onDefault = DreamTarget(AgentId("agent-1"), ConversationId("conv-default-agent-1"))
    private val onConversation = DreamTarget(AgentId("agent-1"), ConversationId("conv-7"))

    /** letta-code 0.34.8's stored shape (`reflection_settings`, L:437880). */
    private val stored = buildJsonObject {
        put("agent_id", "agent-1")
        put("trigger", "step-count")
        put("step_count", 10)
        put("merge", "explicit")
        put("merge_instructions", "keep it short")
    }

    // ── Direct App Server ──────────────────────────────────────────────────

    @Test
    fun appServerReadsTheSettingsOnTheBareDefaultConversation() = runTest {
        val client = FakeClient(settings = stored)
        val outcome = AppServerDreamingRepository(client = { client }).settings(onDefault)
        val settings = assertIs<DreamOutcome.Done<DreamSettings>>(outcome).value
        assertEquals(DreamSettings(DreamTrigger.StepCount, 10, DreamMerge.Explicit, "keep it short"), settings)
        assertEquals("default", client.gets.single().runtime.conversationId)
    }

    @Test
    fun appServerSavesEveryFieldAndTheScope() = runTest {
        val client = FakeClient(settings = stored)
        val change = DreamSettingsChange(DreamSettings(DreamTrigger.Off, 7, DreamMerge.Auto, ""), DreamSettingsScope.Global)
        AppServerDreamingRepository(client = { client }).save(onConversation, change)
        val sent = client.sets.single()
        assertEquals("global", sent.scope)
        assertEquals("conv-7", sent.runtime.conversationId)
        assertEquals("off", sent.settings.getValue("trigger").jsonPrimitive.content)
        assertEquals(7, sent.settings.getValue("step_count").jsonPrimitive.int)
        assertEquals("auto", sent.settings.getValue("merge").jsonPrimitive.content)
    }

    @Test
    fun appServerRefusalCarriesLettaCodesText() = runTest {
        val client = FakeClient(settingsSuccess = false, errorText = "Reflection settings require memfs")
        val failed = AppServerDreamingRepository(client = { client }).settings(onDefault)
        assertEquals("Reflection settings require memfs", assertIs<DreamOutcome.Failed>(failed).message)
    }

    @Test
    fun appServerRunsDreamWithTheInstructionAfterTheFlagTerminator() = runTest {
        val client = FakeClient(output = "Started a reflection pass for this conversation.")
        val outcome = AppServerDreamingRepository(client = { client }).runNow(onConversation, "  focus on tooling  ")
        assertEquals(DreamStartStatus.Started, assertIs<DreamOutcome.Done<DreamRunStart>>(outcome).value.status)
        val command = client.commands.single()
        assertEquals("dream", command.commandId)
        assertEquals("-- focus on tooling", command.args)
        assertEquals("conv-7", command.runtime?.conversationId)
    }

    @Test
    fun aRuntimeWithoutDreamIsTooOldAndNothingIsSent() = runTest {
        val client = FakeClient(output = "unused")
        // The embedded 0.26.1 / 0.32.3 command list (golden letta-code-0.32.3-live.jsonl:9).
        val commands = listOf("clear", "doctor", "init", "remember", "compact", "reload", "context-limit")
        val outcome = AppServerDreamingRepository(client = { client }, supportedCommands = { commands }).runNow(onDefault)
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld), outcome)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun unknownCommandNoClientAndNoExecuteCommandAreUnsupported() = runTest {
        val unknown = AppServerDreamingRepository(client = { FakeClient(output = "Unknown command: dream", success = false) }).runNow(onDefault)
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld), unknown)
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection), AppServerDreamingRepository(client = { null }).settings(onDefault))
        val relayOnly = AppServerDreamingRepository(client = { FakeClient(unsupported = true) })
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection), relayOnly.runNow(onDefault))
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection), relayOnly.settings(onDefault))
    }

    // ── Iroh: reflection.* ─────────────────────────────────────────────────

    @Test
    fun irohSendsTheMergeFieldsAndDecodesTheStoredSettings() = runTest {
        var sent: JsonObject? = null
        val repository = AdminRpcDreamingRepository(
            AdminRpcInvoker { method, params ->
                assertEquals(DreamingRpc.SET, method)
                sent = params
                buildJsonObject { put("reflection_settings", stored); put("scope", "both") }
            },
        )
        val change = DreamSettingsChange(DreamSettings(DreamTrigger.StepCount, 10, DreamMerge.Explicit, "keep it short"))
        val saved = assertIs<DreamOutcome.Done<DreamSettings>>(repository.save(onDefault, change)).value
        assertEquals(DreamMerge.Explicit, saved.merge)
        val params = sent!!
        assertEquals("default", params.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("step-count", params.getValue("trigger").jsonPrimitive.content)
        assertEquals(10, params.getValue("step_count").jsonPrimitive.int)
        assertEquals("explicit", params.getValue("merge").jsonPrimitive.content)
        assertNull(params["scope"])
    }

    @Test
    fun irohRunDecodesTheHostsAnswer() = runTest {
        val repository = AdminRpcDreamingRepository(
            AdminRpcInvoker { method, params ->
                assertEquals(DreamingRpc.RUN, method)
                assertEquals("why", params.getValue("instruction").jsonPrimitive.content)
                Json.parseToJsonElement("""{"status":"AlreadyRunning","output":"A reflection agent is already running for this conversation."}""")
            },
        )
        val start = assertIs<DreamOutcome.Done<DreamRunStart>>(repository.runNow(onDefault, "why")).value
        assertEquals(DreamStartStatus.AlreadyRunning, start.status)
    }

    @Test
    fun irohErrorsMapToReasons() = runTest {
        fun failing(error: String) = AdminRpcDreamingRepository(AdminRpcInvoker { _, _ -> throw ModelControlException(error) })
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.HostNeedsUpdate), failing("Unknown method: reflection.run").runNow(onDefault))
        assertEquals(
            DreamOutcome.Unsupported(DreamUnsupportedReason.NoNativeAppServer),
            failing("capability_unavailable: 'reflection.get' has no injected 'native App Server client' service on this controller").settings(onDefault),
        )
        // The message hosts sent before this change.
        assertEquals(
            DreamOutcome.Unsupported(DreamUnsupportedReason.NoNativeAppServer),
            failing("reflection settings require the native App Server client").settings(onDefault),
        )
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld), failing("capability_unavailable: this App Server has no /dream").runNow(onDefault))
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection), failing("admin_rpc is not supported by this transport").settings(onDefault))
        assertEquals("the App Server did not answer", assertIs<DreamOutcome.Failed>(failing("dream_failed: the App Server did not answer").runNow(onDefault)).message)
    }

    @Test
    fun orElseFallsBackOnlyWhenTheFirstCannotServeAndKeepsTheMoreSpecificReason() = runTest {
        val hostNeedsUpdate = AdminRpcDreamingRepository(AdminRpcInvoker { _, _ -> throw ModelControlException("Unknown method: reflection.get") })
        val direct = AppServerDreamingRepository(client = { FakeClient(settings = stored) })
        assertIs<DreamOutcome.Done<DreamSettings>>(hostNeedsUpdate.orElse(direct).settings(onDefault))
        val noDirect = AppServerDreamingRepository(client = { null })
        assertEquals(DreamOutcome.Unsupported(DreamUnsupportedReason.HostNeedsUpdate), hostNeedsUpdate.orElse(noDirect).settings(onDefault))
    }

    private class FakeClient(
        private val settings: JsonObject? = null,
        private val settingsSuccess: Boolean = true,
        private val errorText: String? = null,
        private val output: String? = null,
        private val success: Boolean = true,
        private val unsupported: Boolean = false,
    ) : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>()
        val gets = mutableListOf<AppServerCommand.GetReflectionSettings>()
        val sets = mutableListOf<AppServerCommand.SetReflectionSettings>()
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()

        override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings): AppServerInboundFrame.GetReflectionSettingsResponse {
            if (unsupported) throw UnsupportedOperationException("not relayed")
            gets += command
            return AppServerInboundFrame.GetReflectionSettingsResponse(command.requestId, settingsSuccess, settings, errorText)
        }

        override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings): AppServerInboundFrame.SetReflectionSettingsResponse {
            if (unsupported) throw UnsupportedOperationException("not relayed")
            sets += command
            return AppServerInboundFrame.SetReflectionSettingsResponse(command.requestId, settingsSuccess, settings, command.scope, errorText)
        }

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            if (unsupported) throw UnsupportedOperationException("execute_command is not supported by this client")
            commands += command
            return AppServerInboundFrame.ExecuteCommandResponse(command.requestId, success, output = output)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unexpected()
        override suspend fun input(command: AppServerCommand.Input): Unit = unexpected()
        override suspend fun sync(command: AppServerCommand.Sync) = unexpected()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unexpected()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unexpected()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unexpected()

        private fun unexpected(): Nothing = error("Unexpected App Server operation")
    }
}
