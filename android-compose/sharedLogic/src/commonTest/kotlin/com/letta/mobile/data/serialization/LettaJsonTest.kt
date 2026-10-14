package com.letta.mobile.data.serialization

import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pins the canonical configurations flag by flag (letta-mobile-by4vm.20). A change here changes
 * how every migrated consumer reads drifted input, so it must be a deliberate edit to this test.
 */
@OptIn(ExperimentalSerializationApi::class)
class LettaJsonTest {
    @Serializable
    private data class Sample(val name: String = "default", val note: String? = null)

    private data class Flags(
        val ignoreUnknownKeys: Boolean,
        val isLenient: Boolean,
        val coerceInputValues: Boolean,
        val encodeDefaults: Boolean,
        val explicitNulls: Boolean,
        val classDiscriminator: String,
    )

    private fun Json.flags(): Flags = configuration.let {
        Flags(
            it.ignoreUnknownKeys,
            it.isLenient,
            it.coerceInputValues,
            it.encodeDefaults,
            it.explicitNulls,
            it.classDiscriminator,
        )
    }

    @Test
    fun wireFlagsArePinned() {
        assertEquals(
            Flags(ignoreUnknownKeys = true, isLenient = false, coerceInputValues = false,
                encodeDefaults = false, explicitNulls = false, classDiscriminator = "type"),
            LettaJson.wire.flags(),
        )
    }

    @Test
    fun adminRpcFlagsArePinned() {
        assertEquals(
            Flags(ignoreUnknownKeys = true, isLenient = true, coerceInputValues = true,
                encodeDefaults = false, explicitNulls = false, classDiscriminator = "type"),
            LettaJson.adminRpc.flags(),
        )
    }

    @Test
    fun persistedFlagsArePinned() {
        assertEquals(
            Flags(ignoreUnknownKeys = true, isLenient = false, coerceInputValues = false,
                encodeDefaults = true, explicitNulls = true, classDiscriminator = "type"),
            LettaJson.persisted.flags(),
        )
    }

    @Test
    fun appServerProtocolJsonIsTheWireInstance() {
        assertSame(LettaJson.wire, AppServerProtocol.json)
    }

    @Test
    fun lettaWireJsonIsTheAdminRpcInstance() {
        assertSame(LettaJson.adminRpc, lettaWireJson)
    }

    @Test
    fun wireIgnoresUnknownKeysButRejectsTypeDrift() {
        val decoded = LettaJson.wire.decodeFromString(Sample.serializer(), """{"name":"a","added":1}""")
        assertEquals(Sample(name = "a"), decoded)
        assertFailsWith<SerializationException> {
            LettaJson.wire.decodeFromString(Sample.serializer(), """{"name":null}""")
        }
        assertEquals("{}", LettaJson.wire.encodeToString(Sample.serializer(), Sample()))
    }

    @Test
    fun adminRpcCoercesExplicitNullToTheDefault() {
        val decoded = LettaJson.adminRpc.decodeFromString(Sample.serializer(), """{"name":null,"added":1}""")
        assertEquals(Sample(), decoded)
    }

    @Test
    fun persistedWritesEveryValueAndRejectsTypeDrift() {
        assertEquals(
            """{"name":"default","note":null}""",
            LettaJson.persisted.encodeToString(Sample.serializer(), Sample()),
        )
        assertEquals(
            Sample(name = "b"),
            LettaJson.persisted.decodeFromString(Sample.serializer(), """{"name":"b","fromNewerBuild":true}"""),
        )
        assertFailsWith<SerializationException> {
            LettaJson.persisted.decodeFromString(Sample.serializer(), """{"name":null}""")
        }
    }
}
