package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppServerCompatibilityTest {
    private val localRequirement = AppServerCompatibilityRequirement(
        protocolVersion = 1,
        expectedBackend = "local",
        requiredCapabilities = setOf(
            "runtime_start",
            "agent_management",
            "conversation_management",
            "memory_management",
        ),
        requiredDisabledCapabilities = setOf("split_channels"),
    )

    @Test
    fun acceptsCanonicalLocalDescriptorWithAdditiveCapabilities() {
        val info = localInfo(
            extraCapabilities = mapOf("runtime_workspace_sandbox" to true),
        )

        assertSame(info, info.requireCompatibleWith(localRequirement))
    }

    @Test
    fun rejectsMissingOrDisabledRequiredCapability() {
        val missing = localInfo(omitCapabilities = setOf("memory_management"))
        val disabled = localInfo(extraCapabilities = mapOf("runtime_start" to false))

        val missingError = assertFailsWith<IllegalStateException> {
            missing.requireCompatibleWith(localRequirement)
        }
        assertTrue(missingError.message.orEmpty().contains("memory_management"))
        val disabledError = assertFailsWith<IllegalStateException> {
            disabled.requireCompatibleWith(localRequirement)
        }
        assertTrue(disabledError.message.orEmpty().contains("runtime_start"))
    }

    @Test
    fun rejectsWrongProtocolBackendOrSplitChannelContract() {
        assertFailsWith<IllegalStateException> {
            localInfo(protocolVersion = 2).requireCompatibleWith(localRequirement)
        }
        assertFailsWith<IllegalStateException> {
            localInfo(backend = "api").requireCompatibleWith(localRequirement)
        }
        assertFailsWith<IllegalStateException> {
            localInfo(extraCapabilities = mapOf("split_channels" to true))
                .requireCompatibleWith(localRequirement)
        }
        assertFailsWith<IllegalStateException> {
            localInfo(omitCapabilities = setOf("split_channels"))
                .requireCompatibleWith(localRequirement)
        }
    }

    private fun localInfo(
        protocolVersion: Int = 1,
        backend: String = "local",
        omitCapabilities: Set<String> = emptySet(),
        extraCapabilities: Map<String, Boolean> = emptyMap(),
    ): AppServerInfoData {
        val defaults = mapOf(
            "runtime_start" to true,
            "agent_management" to true,
            "conversation_management" to true,
            "memory_management" to true,
            "split_channels" to false,
        )
        return AppServerInfoData(
            lettaCodeVersion = "0.30.25",
            protocolVersion = protocolVersion,
            backend = backend,
            capabilities = buildJsonObject {
                (defaults + extraCapabilities)
                    .filterKeys { it !in omitCapabilities }
                    .forEach { (name, enabled) -> put(name, enabled) }
            },
        )
    }
}
