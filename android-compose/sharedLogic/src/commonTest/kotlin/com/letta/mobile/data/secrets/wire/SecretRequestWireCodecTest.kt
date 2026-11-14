package com.letta.mobile.data.secrets.wire

import com.letta.mobile.data.secrets.request.SecretRefusalText
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestMethods
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestRefusal
import com.letta.mobile.data.secrets.request.SecretServiceError
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The value codec (contract M1) and the refusal text, both directions. */
class SecretRequestWireCodecTest {
    @Test
    fun encodingMatchesStandardPaddedBase64() {
        val random = Random(SEED)
        for (size in listOf(0, 1, 2, 3, 4, 5, 31, 32, 33, 1000)) {
            val bytes = random.nextBytes(size)
            assertEquals(Base64.encode(bytes), SecretValueBase64.encode(bytes).concatToString(), "size $size")
        }
    }

    @Test
    fun decodingRoundTripsAndKnowsTheLengthBeforeDecoding() {
        val random = Random(SEED)
        for (size in listOf(1, 2, 3, 4, 64, SecretRequestLimits.MAX_VALUE_BYTES)) {
            val bytes = random.nextBytes(size)
            val text = Base64.encode(bytes)
            assertEquals(size, SecretValueBase64.decodedLength(text))
            assertContentEquals(bytes, SecretValueBase64.decode(text))
        }
        assertEquals(SecretValueBase64.MAX_ENCODED_CHARS, Base64.encode(ByteArray(SecretRequestLimits.MAX_VALUE_BYTES)).length)
    }

    @Test
    fun onlyCanonicalPaddedBase64Decodes() {
        val refused = listOf("Zg", "Zg=", "Zh==", "Zm9=", "Zm9v=", "Z===", "Zm9v Zg==", "Zm-v", "Zm_v", "=Zm9", "Zg==Zg==", "ÀAAA")
        refused.forEach { assertNull(SecretValueBase64.decode(it), it) }
        assertContentEquals(ByteArray(0), SecretValueBase64.decode(""))
    }

    @Test
    fun everyRefusalSpellsAndParsesBack() {
        val plain = SecretServiceError.entries.filter { it != SecretServiceError.INVALID_REQUEST }.map { SecretRequestRefusal(it) }
        val problems = SecretRequestProblem.entries.map { SecretRequestRefusal(SecretServiceError.INVALID_REQUEST, it) }
        for (refusal in plain + problems) {
            assertEquals(refusal, SecretRefusalText.parse(SecretRefusalText.of(refusal)))
        }
        assertEquals("invalid_request:purpose_blank", SecretRefusalText.of(problems.first()))
        assertEquals("invalid_params", SecretRefusalText.of(SecretRequestRefusal(SecretServiceError.INVALID_PARAMS)))
    }

    @Test
    fun theLeafWireAgreesWithTheContractModels() {
        assertEquals(SecretRequestLimits.MAX_VALUE_BYTES, SecretValueBase64.MAX_VALUE_BYTES)
        val methods = with(SecretRequestMethods) { setOf(REQUEST, STATUS, LIST, GET, SUBMIT, CANCEL) }
        assertEquals(methods, SecretRequestRouteWire.METHODS)
        assertEquals(setOf(SecretRequestMethods.REQUEST, SecretRequestMethods.STATUS), SecretRequestRouteWire.AGENT_METHODS)
    }

    @Test
    fun foreignTextIsNotARefusal() {
        listOf("Internal error", "forbidden: agent not in this device's scope", "invalid_request:bogus", "", "not_pending:x")
            .forEach { assertNull(SecretRefusalText.parse(it), it) }
    }

    private companion object {
        const val SEED = 2_024
    }
}
