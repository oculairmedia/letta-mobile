package com.letta.mobile.plugin.keystore.wire

import com.letta.mobile.plugin.keystore.KeystoreDescriptor
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreHealth
import com.letta.mobile.plugin.keystore.SecretListing
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keystore v1 transcripts (`resources/contracts/keystore/v1`) are the normative wire examples:
 * every request and answer decodes into the typed DTOs and re-encodes equal as JSON, protocol
 * refusals are exactly the lines the DTOs refuse, and no decoded DTO prints a value.
 */
class KeystoreWireFixturesTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val files = listOf(
        "initialize", "describe", "health", "unlock", "lock", "get", "put", "list", "delete", "shutdown", "errors",
    )

    private class Exchange(val request: JsonObject, val answer: JsonObject)

    private fun transcript(name: String): List<Exchange> {
        val text = checkNotNull(javaClass.getResource("/contracts/keystore/v1/$name.jsonl")) { "missing $name.jsonl" }.readText()
        val lines = text.lines().filter(String::isNotBlank).map { json.parseToJsonElement(it).jsonObject }
        assertTrue(lines.size % 2 == 0, "$name: requests and answers pair up")
        return lines.chunked(2).map { (request, answer) ->
            assertEquals(JsonPrimitive("host->provider"), request["dir"], name)
            assertEquals(JsonPrimitive("provider->host"), answer["dir"], name)
            val message = request.getValue("message").jsonObject
            val reply = answer.getValue("message").jsonObject
            assertEquals(message["id"], reply["id"], "$name: the answer has its request's id")
            Exchange(message, reply)
        }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, element: JsonElement): T {
        val decoded = json.decodeFromJsonElement(serializer, element)
        assertEquals(element, json.encodeToJsonElement(serializer, decoded))
        assertNoValueIn(decoded.toString())
        return decoded
    }

    @Suppress("UNCHECKED_CAST")
    private fun KSerializer<*>.erased(): KSerializer<Any?> = this as KSerializer<Any?>

    private fun assertNoValueIn(text: String) {
        assertFalse(SENTINEL in text, text)
        assertFalse(SENTINEL_BASE64_PREFIX in text, text)
    }

    private fun errorCode(answer: JsonObject): Int? = answer["error"]?.jsonObject?.getValue("code")?.jsonPrimitive?.int

    private fun checkRequest(exchange: Exchange) {
        val name = exchange.request.getValue("method").jsonPrimitive.content
        val params = exchange.request.getValue("params")
        val method = KeystoreMethod.byWire(name)
        when (errorCode(exchange.answer)) {
            UNKNOWN_METHOD -> assertNull(method, name)
            INVALID_PARAMS -> assertFailsWith<SerializationException>(name) { decodeParams(assertNotNull(method, name), params) }
            else -> decodeParams(assertNotNull(method, name), params)
        }
    }

    private fun decodeParams(method: KeystoreMethod, params: JsonElement) {
        val serializer = paramsSerializers[method]
        if (serializer == null) assertEquals(JsonObject(emptyMap()), params, method.wire) else roundTrip(serializer.erased(), params)
    }

    private fun checkAnswer(exchange: Exchange) {
        val method = KeystoreMethod.byWire(exchange.request.getValue("method").jsonPrimitive.content)
        val error = exchange.answer["error"]?.jsonObject
        if (error != null) return checkError(error)
        val result = exchange.answer.getValue("result")
        val serializer = resultSerializers[assertNotNull(method)]
        if (serializer == null) assertEquals(JsonObject(emptyMap()), result, method.wire) else roundTrip(serializer.erased(), result)
    }

    private fun checkError(error: JsonObject) {
        if (error.getValue("code").jsonPrimitive.int != KeystoreMethod.KEYSTORE_ERROR_CODE) return
        val decoded = roundTrip(KeystoreError.serializer(), error.getValue("data"))
        assertEquals(decoded.message, error.getValue("message").jsonPrimitive.content, "the message is the code's template")
    }

    @Test
    fun `every transcript line holds to the typed wire shapes`() {
        files.flatMap(::transcript).forEach { exchange ->
            assertEquals(JsonPrimitive("2.0"), exchange.request["jsonrpc"])
            checkRequest(exchange)
            checkAnswer(exchange)
        }
    }

    @Test
    fun `every keystore method has at least one transcript`() {
        val covered = files.flatMap(::transcript).mapNotNull { KeystoreMethod.byWire(it.request.getValue("method").jsonPrimitive.content) }
        assertEquals(KeystoreMethod.entries.toSet(), covered.toSet())
    }

    @Test
    fun `values travel as base64 in the value-carrying frames only`() {
        val get = transcript("get").first()
        val result = json.decodeFromJsonElement(KeystoreGetResult.serializer(), get.answer.getValue("result"))
        assertEquals("$SENTINEL-value-7f3a9c", result.value.revealSecretBytes { it.decodeToString() })
        assertEquals(SecretValue.REDACTED, result.value.toString())

        val put = json.decodeFromJsonElement(KeystorePutParams.serializer(), transcript("put").first().request.getValue("params"))
        assertTrue(put.value.contentEqualsConstantTime(result.value))
        assertEquals(KeystoreMethod.VALUE_CARRYING, setOf(KeystoreMethod.UNLOCK, KeystoreMethod.GET, KeystoreMethod.PUT))

        files.filter { it !in setOf("get", "put", "unlock", "errors") }.flatMap(::transcript).forEach { exchange ->
            assertNoValueIn(exchange.request.toString())
            assertNoValueIn(exchange.answer.toString())
        }
    }

    @Test
    fun `unknown capability names are ignored, known ones keep their order`() {
        val wire = """{"id":"example.memory","capabilities":["list","rotate","read"],"needsUnlock":false}"""
        val descriptor = json.decodeFromString(KeystoreDescriptor.serializer(), wire)
        assertEquals(listOf("list", "read"), descriptor.capabilities.map { it.wire })
    }

    private companion object {
        const val SENTINEL = "LKS-SENTINEL"
        const val SENTINEL_BASE64_PREFIX = "TEtTLVNFTlRJTkVM"
        const val UNKNOWN_METHOD = -32601
        const val INVALID_PARAMS = -32602

        val paramsSerializers: Map<KeystoreMethod, KSerializer<*>> = mapOf(
            KeystoreMethod.INITIALIZE to KeystoreInitializeParams.serializer(),
            KeystoreMethod.UNLOCK to KeystoreUnlockParams.serializer(),
            KeystoreMethod.GET to KeystoreRefParams.serializer(),
            KeystoreMethod.PUT to KeystorePutParams.serializer(),
            KeystoreMethod.LIST to KeystoreListParams.serializer(),
            KeystoreMethod.DELETE to KeystoreRefParams.serializer(),
        )

        val resultSerializers: Map<KeystoreMethod, KSerializer<*>> = mapOf(
            KeystoreMethod.INITIALIZE to KeystoreInitializeResult.serializer(),
            KeystoreMethod.DESCRIBE to KeystoreDescriptor.serializer(),
            KeystoreMethod.HEALTH to KeystoreHealth.serializer(),
            KeystoreMethod.GET to KeystoreGetResult.serializer(),
            KeystoreMethod.LIST to SecretListing.serializer(),
        )
    }
}
