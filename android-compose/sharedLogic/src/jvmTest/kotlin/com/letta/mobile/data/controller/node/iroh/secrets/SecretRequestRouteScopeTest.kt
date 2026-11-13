package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.secrets.host.SecretItemScope
import com.letta.mobile.data.secrets.host.SecretRequestSettings
import com.letta.mobile.data.secrets.host.TARGET_REF
import com.letta.mobile.data.secrets.host.TEAM_SCOPE
import com.letta.mobile.data.secrets.host.TestKeystoreProvider
import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The item scope [SecretRequestFeature.forHost] is given is the one agents get, and the human sees replacements. */
class SecretRequestRouteScopeTest {
    private fun createFor(item: String): JsonObject =
        JsonObject(createInput() + (SecretRequestRouteWire.VAULT_ITEM_NAME to JsonPrimitive(item)))

    private suspend fun SecretRequestFeature.createAsRequester(item: String): ExternalToolResult =
        hostTools.single().invoke(createFor(item), ExternalToolCaller(CallerIdentity.VERIFIED, REQUESTER.agentId.value))

    @Test
    fun forHostServesTheScopeItIsGivenEvenOverAWiderOneInTheSettings() = runTest {
        val feature = assertNotNull(
            SecretRequestFeature.forHost(
                IrohAuthPolicy.PairedPeersOnly,
                TestKeystoreProvider(),
                InMemorySecretRequestJournal(),
                SecretItemScope.PerAgent,
                SecretRequestSettings(policy = TEAM_SCOPE),
            ),
        )
        assertEquals(ExternalToolResult.Error("invalid_request:item_outside_scope"), feature.createAsRequester("team/openai"))
        assertEquals(
            ExternalToolResult.Error("invalid_request:item_outside_scope"),
            feature.createAsRequester("agents/${BYSTANDER.agentId.value}/openai"),
        )
        assertTrue(feature.createAsRequester("agents/${REQUESTER.agentId.value}/openai") is ExternalToolResult.Success)
    }

    @Test
    fun theHumanRecordSaysWhenARequestReplacesAnExistingValue() = runTest {
        val harness = SecretRouteHarness()
        val fresh = harness.create()
        harness.provider.seed(TARGET_REF, "owner-value")
        val replacing = harness.create()

        val freshDetails = recordDetails(harness.dispatch(SecretRequestRoute.GET, idParams(fresh), HUMAN))
        val replacingDetails = recordDetails(harness.dispatch(SecretRequestRoute.GET, idParams(replacing), HUMAN))

        assertEquals(null, freshDetails["replacesExisting"], "omitted when false")
        assertEquals(JsonPrimitive(true), replacingDetails["replacesExisting"])
        val created = harness.tool(createInput(), REQUESTER) as ExternalToolResult.Success
        assertTrue("replaces" !in created.content, "the agent is never told")
    }

    private fun recordDetails(response: String): JsonObject = assertNotNull(resultOf(response)).getValue("details").jsonObject
}
