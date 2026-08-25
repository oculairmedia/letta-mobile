package com.letta.mobile.data.model.provider

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderStatusDecodingTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private inline fun <reified T : Any> assertWireRoundTrip(pairs: List<Pair<T, String>>) {
        for ((status, expectedJson) in pairs) {
            assertEquals(expectedJson, json.encodeToString<T>(status))
            assertEquals(status, json.decodeFromString<T>(expectedJson))
        }
    }

    private inline fun <reified T : Any> assertUnknownWireDecoding(
        rawString: String,
        crossinline extractRaw: (T) -> String,
        crossinline extractWireValue: (T) -> String,
    ) {
        val decoded = json.decodeFromString<T>("\"$rawString\"")
        assertEquals(rawString, extractRaw(decoded))
        assertEquals(rawString, extractWireValue(decoded))
        assertEquals("\"$rawString\"", json.encodeToString<T>(decoded))
    }

    @Test
    fun providerProtocolEncodesAndDecodesKnownStates() {
        assertWireRoundTrip<ProviderProtocol>(
            listOf(
                ProviderProtocol.OpenAi to "\"openai\"",
                ProviderProtocol.Anthropic to "\"anthropic\"",
                ProviderProtocol.GoogleAi to "\"google\"",
                ProviderProtocol.Ollama to "\"ollama\"",
            ),
        )
    }

    @Test
    fun providerProtocolDecodesUnknownValuesWithoutThrowing() {
        assertUnknownWireDecoding<ProviderProtocol>(
            rawString = "mistral_native",
            extractRaw = { (it as ProviderProtocol.Unknown).raw },
            extractWireValue = { it.wireValue },
        )
    }

    @Test
    fun credentialStatusEncodesAndDecodesKnownStates() {
        assertWireRoundTrip<CredentialStatus>(
            listOf(
                CredentialStatus.Configured to "\"configured\"",
                CredentialStatus.Missing to "\"missing\"",
                CredentialStatus.Invalid to "\"invalid\"",
                CredentialStatus.NotRequired to "\"not_required\"",
            ),
        )
    }

    @Test
    fun credentialStatusDecodesUnknownValuesWithoutThrowing() {
        assertUnknownWireDecoding<CredentialStatus>(
            rawString = "future_pending_verification",
            extractRaw = { (it as CredentialStatus.Unknown).raw },
            extractWireValue = { it.wireValue },
        )
    }

    @Test
    fun operationalStatusEncodesAndDecodesKnownStates() {
        assertWireRoundTrip<OperationalStatus>(
            listOf(
                OperationalStatus.Active to "\"active\"",
                OperationalStatus.Degraded to "\"degraded\"",
                OperationalStatus.Disabled to "\"disabled\"",
                OperationalStatus.Unavailable to "\"unavailable\"",
            ),
        )
    }

    @Test
    fun operationalStatusDecodesUnknownValuesWithoutThrowing() {
        assertUnknownWireDecoding<OperationalStatus>(
            rawString = "quarantined_by_admin",
            extractRaw = { (it as OperationalStatus.Unknown).raw },
            extractWireValue = { it.wireValue },
        )
    }

    @Test
    fun modelAvailabilityEncodesAndDecodesKnownStates() {
        assertWireRoundTrip<ModelAvailability>(
            listOf(
                ModelAvailability.Available to "\"available\"",
                ModelAvailability.Deprecated to "\"deprecated\"",
                ModelAvailability.Disabled to "\"disabled\"",
                ModelAvailability.QuotaExceeded to "\"quota_exceeded\"",
            ),
        )
    }

    @Test
    fun modelAvailabilityDecodesUnknownValuesWithoutThrowing() {
        assertUnknownWireDecoding<ModelAvailability>(
            rawString = "rate_limited_tier_2",
            extractRaw = { (it as ModelAvailability.Unknown).raw },
            extractWireValue = { it.wireValue },
        )
    }

    @Test
    fun visibilityPolicyEncodesAndDecodesKnownStates() {
        assertWireRoundTrip<VisibilityPolicy>(
            listOf(
                VisibilityPolicy.Visible to "\"visible\"",
                VisibilityPolicy.Hidden to "\"hidden\"",
                VisibilityPolicy.Automatic to "\"auto\"",
            ),
        )

        // "automatic" alias decodes to Automatic and re-encodes to canonical "auto"
        val fromAlias = json.decodeFromString<VisibilityPolicy>("\"automatic\"")
        assertEquals(VisibilityPolicy.Automatic, fromAlias)
        assertEquals("\"auto\"", json.encodeToString<VisibilityPolicy>(fromAlias))
    }

    @Test
    fun visibilityPolicyDecodesUnknownValuesWithoutThrowing() {
        assertUnknownWireDecoding<VisibilityPolicy>(
            rawString = "pinned_featured",
            extractRaw = { (it as VisibilityPolicy.Unknown).raw },
            extractWireValue = { it.wireValue },
        )
    }

    @Test
    fun statusDecodersRejectIntegerOrdinals() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ProviderProtocol>("0")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CredentialStatus>("0")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<OperationalStatus>("0")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ModelAvailability>("0")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<VisibilityPolicy>("0")
        }
    }
}
