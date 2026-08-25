package com.letta.mobile.data.model.provider

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProviderStatusDecodingTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun credentialStatusEncodesAndDecodesKnownStates() {
        val pairs: List<Pair<CredentialStatus, String>> = listOf(
            CredentialStatus.Configured to "\"configured\"",
            CredentialStatus.Missing to "\"missing\"",
            CredentialStatus.Invalid to "\"invalid\"",
            CredentialStatus.NotRequired to "\"not_required\"",
        )

        for ((status, expectedJson) in pairs) {
            assertEquals(expectedJson, json.encodeToString<CredentialStatus>(status))
            assertEquals(status, json.decodeFromString<CredentialStatus>(expectedJson))
        }
    }

    @Test
    fun credentialStatusDecodesUnknownValuesWithoutThrowing() {
        val decoded = json.decodeFromString<CredentialStatus>("\"future_pending_verification\"")
        assertIs<CredentialStatus.Unknown>(decoded)
        assertEquals("future_pending_verification", decoded.raw)
        assertEquals("future_pending_verification", decoded.wireValue)
        assertEquals("\"future_pending_verification\"", json.encodeToString<CredentialStatus>(decoded))
    }

    @Test
    fun operationalStatusEncodesAndDecodesKnownStates() {
        val pairs: List<Pair<OperationalStatus, String>> = listOf(
            OperationalStatus.Active to "\"active\"",
            OperationalStatus.Degraded to "\"degraded\"",
            OperationalStatus.Disabled to "\"disabled\"",
            OperationalStatus.Unavailable to "\"unavailable\"",
        )

        for ((status, expectedJson) in pairs) {
            assertEquals(expectedJson, json.encodeToString<OperationalStatus>(status))
            assertEquals(status, json.decodeFromString<OperationalStatus>(expectedJson))
        }
    }

    @Test
    fun operationalStatusDecodesUnknownValuesWithoutThrowing() {
        val decoded = json.decodeFromString<OperationalStatus>("\"quarantined_by_admin\"")
        assertIs<OperationalStatus.Unknown>(decoded)
        assertEquals("quarantined_by_admin", decoded.raw)
        assertEquals("quarantined_by_admin", decoded.wireValue)
        assertEquals("\"quarantined_by_admin\"", json.encodeToString<OperationalStatus>(decoded))
    }

    @Test
    fun modelAvailabilityEncodesAndDecodesKnownStates() {
        val pairs: List<Pair<ModelAvailability, String>> = listOf(
            ModelAvailability.Available to "\"available\"",
            ModelAvailability.Deprecated to "\"deprecated\"",
            ModelAvailability.Disabled to "\"disabled\"",
            ModelAvailability.QuotaExceeded to "\"quota_exceeded\"",
        )

        for ((availability, expectedJson) in pairs) {
            assertEquals(expectedJson, json.encodeToString<ModelAvailability>(availability))
            assertEquals(availability, json.decodeFromString<ModelAvailability>(expectedJson))
        }
    }

    @Test
    fun modelAvailabilityDecodesUnknownValuesWithoutThrowing() {
        val decoded = json.decodeFromString<ModelAvailability>("\"rate_limited_tier_2\"")
        assertIs<ModelAvailability.Unknown>(decoded)
        assertEquals("rate_limited_tier_2", decoded.raw)
        assertEquals("rate_limited_tier_2", decoded.wireValue)
        assertEquals("\"rate_limited_tier_2\"", json.encodeToString<ModelAvailability>(decoded))
    }

    @Test
    fun visibilityPolicyEncodesAndDecodesKnownStates() {
        val pairs: List<Pair<VisibilityPolicy, String>> = listOf(
            VisibilityPolicy.Visible to "\"visible\"",
            VisibilityPolicy.Hidden to "\"hidden\"",
            VisibilityPolicy.Automatic to "\"auto\"",
        )

        for ((visibility, expectedJson) in pairs) {
            assertEquals(expectedJson, json.encodeToString<VisibilityPolicy>(visibility))
            assertEquals(visibility, json.decodeFromString<VisibilityPolicy>(expectedJson))
        }

        // "automatic" alias decodes to Automatic
        assertEquals(VisibilityPolicy.Automatic, json.decodeFromString<VisibilityPolicy>("\"automatic\""))
    }

    @Test
    fun visibilityPolicyDecodesUnknownValuesWithoutThrowing() {
        val decoded = json.decodeFromString<VisibilityPolicy>("\"pinned_featured\"")
        assertIs<VisibilityPolicy.Unknown>(decoded)
        assertEquals("pinned_featured", decoded.raw)
        assertEquals("pinned_featured", decoded.wireValue)
        assertEquals("\"pinned_featured\"", json.encodeToString<VisibilityPolicy>(decoded))
    }

    @Test
    fun statusDecodersRejectIntegerOrdinals() {
        // Enforce no enum ordinal persistence
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
