package com.letta.mobile.data.servicecredentials

import com.letta.mobile.data.rpcwire.AdminRpcErrors
import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServiceCredentialsClientTest {
    private val jev = ServiceId("typesafe-jev")
    private val status = ServiceCredentialStatus(
        serviceId = jev,
        displayName = "TypeSafe Jev",
        configured = true,
        defaultBaseUrl = "https://api.typesafe.ai",
        hasSecret = true,
        secretLabel = "API key",
        configFields = listOf(ServiceConfigField("model", "Model", defaultValue = "jev-latest")),
        revision = "rev-1",
    )

    private class Refused(val error: String) : Exception(error)

    /** A fake host: [reply] answers each call, or throws [Refused] to answer a failure with its error. */
    private class Host(var reply: (String, JsonObject) -> JsonElement?) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val rpc = ServiceCredentialLink { method, params ->
            calls += method to params
            try {
                ServiceCredentialReply(true, reply(method, params))
            } catch (refused: Refused) {
                ServiceCredentialReply(false, error = refused.error)
            }
        }
    }

    private fun wire(value: ServiceCredentialStatus) = LettaJson.wire.encodeToJsonElement(ServiceCredentialStatus.serializer(), value)

    private fun list(vararg services: ServiceCredentialStatus, canManage: Boolean = true) =
        LettaJson.wire.encodeToJsonElement(
            ServiceCredentialList.serializer(),
            ServiceCredentialList(canManage = canManage, services = services.toList()),
        )

    @Test
    fun aWriteCarriesTheTypedSecretOnlyInItsParamsAndNeverPrintsIt() = runTest {
        val host = Host { _, _ -> wire(status) }
        val draft = ServiceCredentialDraft("", "  placeholder-key-not-real ", mapOf("model" to " jev-1 ", "unused" to " "))
        val command = draft.commandFor(status)
        assertFalse("placeholder-key-not-real" in command.toString())
        ServiceCredentialsClient(host.rpc) { setOf(ServiceCredentialWire.CAPABILITY) }.set(command)
        val (method, params) = host.calls.single()
        assertEquals(ServiceCredentialWire.SET, method)
        assertEquals("placeholder-key-not-real", params.getValue("secret").jsonPrimitive.content)
        assertEquals("jev-1", (params.getValue("config") as JsonObject).getValue("model").jsonPrimitive.content)
        assertNull((params.getValue("config") as JsonObject)["unused"])
        assertEquals("rev-1", params.getValue("expected_revision").jsonPrimitive.content)
        assertNull(params["base_url"], "A service with a fixed address never sends one")
    }

    @Test
    fun aBlankSecretKeepsTheStoredOne() {
        val command = ServiceCredentialDraft("", " ", emptyMap()).commandFor(status)
        assertEquals(ServiceSecretChange.Keep, command.secret)
        assertTrue(ServiceSecretChange.Replace(SecretWriteValue("x")).toString().contains("withheld"))
    }

    @Test
    fun refusalsMapToTheClosedCodeSet() = runTest {
        val host = Host { _, _ -> throw Refused("forbidden") }
        val client = ServiceCredentialsClient(host.rpc) { setOf(ServiceCredentialWire.CAPABILITY) }
        assertEquals(ServiceCredentialErrorCode.NOT_AUTHORIZED, assertFailsWith<ServiceCredentialException> { client.test(jev) }.code)
        host.reply = { method, _ -> throw Refused(AdminRpcErrors.unknownMethod(method)) }
        assertEquals(ServiceCredentialErrorCode.UNSUPPORTED, assertFailsWith<ServiceCredentialException> { client.list() }.code)
        host.reply = { _, _ -> throw Refused("service.revision_conflict") }
        val conflict = assertFailsWith<ServiceCredentialException> { client.remove(jev, "rev-1") }
        assertEquals(ServiceCredentialErrorCode.REVISION_CONFLICT, conflict.code)
    }

    @Test
    fun aHostWithoutTheContractLeavesThePageUnsupported() = runTest {
        val host = Host { _, _ -> error("An old host must not be called") }
        val controller = ServiceCredentialsController(ServiceCredentialsClient(host.rpc) { emptySet() }, this)
        controller.refresh()
        advanceUntilIdle()
        assertFalse(controller.state.value.supported)
    }

    @Test
    fun aSaveClosesTheFormAndAConflictReReadsTheList() = runTest {
        val host = Host { method, _ -> if (method == ServiceCredentialWire.LIST) list(status) else wire(status.copy(revision = "rev-2")) }
        val controller = ServiceCredentialsController(ServiceCredentialsClient(host.rpc) { setOf(ServiceCredentialWire.CAPABILITY) }, this)
        controller.refresh()
        advanceUntilIdle()
        assertTrue(controller.state.value.canManage)
        controller.openEdit(jev)
        controller.save(jev, ServiceCredentialDraft("", "placeholder-key-not-real", emptyMap()))
        advanceUntilIdle()
        assertNull(controller.state.value.editing)
        assertEquals("rev-2", controller.state.value.services.single().revision)

        host.reply = { method, _ ->
            if (method == ServiceCredentialWire.LIST) list(status.copy(revision = "rev-5")) else throw Refused("service.revision_conflict")
        }
        controller.test(jev)
        advanceUntilIdle()
        assertEquals(ServiceCredentialErrorCode.REVISION_CONFLICT, controller.state.value.error)
        assertEquals("rev-5", controller.state.value.services.single().revision)
    }

    @Test
    fun aFailedConnectionIsARecoverableRequestErrorNotAnEscapedException() = runTest {
        val broken = ServiceCredentialLink { _, _ -> throw IllegalStateException("the host connection dropped") }
        val client = ServiceCredentialsClient(broken) { setOf(ServiceCredentialWire.CAPABILITY) }
        assertEquals(ServiceCredentialErrorCode.INTERNAL, assertFailsWith<ServiceCredentialException> { client.list() }.code)
        assertEquals(ServiceCredentialErrorCode.INTERNAL, assertFailsWith<ServiceCredentialException> { client.test(jev) }.code)
    }

    @Test
    fun cancellingARequestStaysACancellation() = runTest {
        val cancelled = ServiceCredentialLink { _, _ -> throw CancellationException("scope closed") }
        val client = ServiceCredentialsClient(cancelled) { setOf(ServiceCredentialWire.CAPABILITY) }
        assertFailsWith<CancellationException> { client.list() }
    }

    @Test
    fun aDroppedConnectionClearsTheBusyAndLoadingMarksAndShowsAnError() = runTest {
        val broken = ServiceCredentialLink { method, _ ->
            if (method == ServiceCredentialWire.LIST) ServiceCredentialReply(true, list(status)) else error("dropped")
        }
        val controller = ServiceCredentialsController(ServiceCredentialsClient(broken) { setOf(ServiceCredentialWire.CAPABILITY) }, this)
        controller.refresh()
        advanceUntilIdle()
        controller.test(jev)
        advanceUntilIdle()
        assertNull(controller.state.value.busy)
        assertEquals(ServiceCredentialErrorCode.INTERNAL, controller.state.value.error)
        assertEquals(jev, controller.state.value.failedService)

        val offline = ServiceCredentialLink { _, _ -> throw IllegalStateException("dropped") }
        val fresh = ServiceCredentialsController(ServiceCredentialsClient(offline) { setOf(ServiceCredentialWire.CAPABILITY) }, this)
        fresh.refresh()
        advanceUntilIdle()
        assertFalse(fresh.state.value.loading)
        assertEquals(ServiceCredentialErrorCode.INTERNAL, fresh.state.value.error)
    }
}
