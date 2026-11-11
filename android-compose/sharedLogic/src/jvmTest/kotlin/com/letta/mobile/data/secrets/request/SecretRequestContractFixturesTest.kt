package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.plugin.keystore.KeystoreProviderId
import com.letta.mobile.plugin.keystore.KeystoreSpi
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared fixtures of `docs/reference/contracts/secret-request-v1.md`: every case decodes into
 * the typed models and re-encodes to an equal JSON value, request validation agrees with the
 * documented problems, and the names follow the keystore grammar.
 */
class SecretRequestContractFixturesTest {
    private val json = LettaJson.wire

    /** The typed params and result of one method; null params means the method's own check applies. */
    private class Shape(val params: KSerializer<*>?, val result: KSerializer<*>)

    private val shapes = mapOf(
        SecretRequestMethods.REQUEST to Shape(AgentSecretRequest.serializer(), SecretRequestCreated.serializer()),
        SecretRequestMethods.STATUS to Shape(SecretRequestRef.serializer(), SecretRequestReceiptResult.serializer()),
        SecretRequestMethods.LIST to Shape(null, SecretRequestList.serializer()),
        SecretRequestMethods.GET to Shape(SecretRequestRef.serializer(), SecretRequestRecord.serializer()),
        SecretRequestMethods.SUBMIT to Shape(null, SecretRequestReceiptResult.serializer()),
        SecretRequestMethods.CANCEL to Shape(SecretRequestRef.serializer(), SecretRequestReceiptResult.serializer()),
    )

    private fun fixture(method: String): JsonObject {
        val text = checkNotNull(javaClass.getResource("/secret-request/v1/$method.json")) { method }.readText()
        return json.parseToJsonElement(text).jsonObject.also { assertEquals(method, it.getValue("method").jsonPrimitive.content) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(serializer: KSerializer<*>, element: JsonElement, label: String) {
        val typed = serializer as KSerializer<Any?>
        assertEquals(element, json.encodeToJsonElement(typed, json.decodeFromJsonElement(typed, element)), label)
    }

    @Test
    fun everyCaseOfEveryMethodRoundTripsThroughTheTypedModels() {
        for ((method, shape) in shapes) {
            for (case in fixture(method).getValue("cases").jsonArray.map { it.jsonObject }) {
                val label = "$method/${case.getValue("name").jsonPrimitive.content}"
                val params = case.getValue("params").jsonObject
                shape.params?.let { roundTrip(it, params, "$label params") }
                case["result"]?.let { roundTrip(shape.result, it, "$label result") }
                case["error"]?.let { roundTrip(SecretRequestRefusal.serializer(), it, "$label error") }
                assertTrue((case["result"] == null) != (case["error"] == null), "$label has one outcome")
                params["requestId"]?.let { assertTrue(SecretRequestId(it.jsonPrimitive.content).isWellFormed, label) }
            }
        }
    }

    @Test
    fun listTakesNoParamsAndSubmitCarriesOnlyTheIdAndABase64Value() {
        fixture(SecretRequestMethods.LIST).getValue("cases").jsonArray.forEach {
            assertEquals(JsonObject(emptyMap()), it.jsonObject.getValue("params"))
        }
        for (case in fixture(SecretRequestMethods.SUBMIT).getValue("cases").jsonArray.map { it.jsonObject }) {
            val params = case.getValue("params").jsonObject
            assertEquals(setOf("requestId", "value"), params.keys)
            val value = Base64.getDecoder().decode(params.getValue("value").jsonPrimitive.content).decodeToString()
            assertTrue(value.isEmpty() || value.startsWith("SENTINEL-"), "fixture values are sentinels")
            val outcome = (case["result"] ?: case["error"]).toString()
            assertTrue(value.isEmpty() || !outcome.contains(value), "no value in a result or error")
        }
    }

    @Test
    fun validationAgreesWithTheDocumentedProblems() {
        for (case in fixture(SecretRequestMethods.REQUEST).getValue("cases").jsonArray.map { it.jsonObject }) {
            val request = json.decodeFromJsonElement(AgentSecretRequest.serializer(), case.getValue("params"))
            val refusal = case["error"]?.let { json.decodeFromJsonElement(SecretRequestRefusal.serializer(), it) }
            val validation = SecretRequestValidation.validate(request)
            val expected = refusal?.problem?.takeUnless { it == SecretRequestProblem.TOO_MANY_PENDING }
            assertEquals(expected, (validation as? SecretRequestValidation.Invalid)?.problem, case.toString())
        }
    }

    @Test
    fun namesFollowTheKeystoreGrammarExactly() {
        val items = listOf("a", "a/b", "a//b", "/a", "a/", ".", "..", "a/./b", "A-z_0.9", "a b", "é", "",
            "x/".repeat(15) + "y", "x/".repeat(16) + "y", "a".repeat(256), "a".repeat(257))
        items.forEach { assertEquals(SecretPath(it).isWellFormed, VaultItemName(it).isWellFormed, it) }
        val fields = listOf("api_key", "", ".", "..", "a/b", "f".repeat(64), "f".repeat(65), "x y")
        fields.forEach { assertEquals(SecretField(it).isWellFormed, VaultFieldName(it).isWellFormed, it) }
        val keystores = listOf("local.vault", "a-b.c", "A", "-a", "a.", "a..b", "", "k".repeat(64), "k".repeat(65))
        keystores.forEach { assertEquals(KeystoreProviderId(it).isWellFormed, SecretKeystoreName(it).isWellFormed, it) }
        assertEquals(KeystoreSpi.MAX_VALUE_BYTES, SecretRequestLimits.MAX_VALUE_BYTES)
        assertNotNull(shapes[SecretRequestMethods.SUBMIT])
    }
}
