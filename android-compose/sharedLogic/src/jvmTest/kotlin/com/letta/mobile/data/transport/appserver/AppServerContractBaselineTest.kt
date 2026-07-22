package com.letta.mobile.data.transport.appserver

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppServerContractBaselineTest {
    @Test
    fun capturedFrameMatrixCoversEveryTypedFixture() {
        val rows = fixtureJson("app-server-v2-contract-matrix.json")["frames"]!!.jsonArray.map { it.jsonObject }
        val fixtures = protocolFixtures()

        assertEquals(fixtures.map(::typeOf).toSet(), rows.map(::typeOf).toSet())
        assertEquals(setOf("control", "either"), rows.map { it.requiredString("channel") }.toSet())
        rows.forEach { row ->
            assertTrue(row.requiredString("owner").isNotBlank(), "${typeOf(row)} must declare an owner")
            assertTrue(row.requiredString("reconnect").isNotBlank(), "${typeOf(row)} must declare reconnect behavior")
            assertTrue(row.requiredString("idempotency").isNotBlank(), "${typeOf(row)} must declare idempotency")
            assertTrue(row.requiredString("capability").isNotBlank(), "${typeOf(row)} must declare its capability gate")
        }
    }

    @Test
    fun installedProtocolInventoryClassifiesEveryOfficialCommandAndMessage() {
        val inventory = fixtureJson("installed-protocol-v2-inventory.json")
        val commands = inventory.requiredStringSet("commands")
        val messages = inventory.requiredStringSet("messages")
        val capabilities = inventory["capabilities"]!!.jsonArray.map { it.jsonObject }
        val classifiedCommands = capabilities.flatMap { it.stringSet("commands") }
        val classifiedMessages = capabilities.flatMap { it.stringSet("messages") }

        assertEquals(90, commands.size, "Update the pinned count after reviewing an upstream union change")
        assertEquals(99, messages.size, "Update the pinned count after reviewing an upstream union change")
        assertEquals(commands, classifiedCommands.toSet())
        assertEquals(messages, classifiedMessages.toSet())
        assertEquals(classifiedCommands.size, classifiedCommands.toSet().size, "A command has multiple capability owners")
        assertEquals(classifiedMessages.size, classifiedMessages.toSet().size, "A message has multiple capability owners")
        assertEquals(
            setOf(
                "runtime_turns", "agent_crud", "conversation_crud_hydration", "models_providers", "memfs",
                "skills", "crons", "channels", "secrets", "filesystem_terminal_git", "external_tools",
                "experiments_reflection_commands",
            ),
            capabilities.map { it.requiredString("id") }.toSet(),
        )
        capabilities.forEach { capability ->
            assertTrue(
                capability.requiredString("classification") in setOf("exposed", "partial", "exposed_sensitive"),
                "${capability.requiredString("id")} needs a machine-readable exposure classification",
            )
            if (capability.requiredString("classification") == "partial") {
                assertTrue(capability.stringSet("absent_operations").isNotEmpty())
            }
        }
    }

    @Test
    fun capturedInboundFramesDecodeOnEveryAllowedSocket() {
        val matrixRows = fixtureJson("app-server-v2-contract-matrix.json")["frames"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy(::typeOf)
        val inbound = protocolFixtures().filter {
            matrixRows.getValue(typeOf(it)).requiredString("direction") == "server_to_client"
        }

        inbound.forEach { fixture ->
            val row = matrixRows.getValue(typeOf(fixture))
            val channels = when (row.requiredString("channel")) {
                "control" -> listOf(AppServerChannel.Control)
                "stream" -> listOf(AppServerChannel.Stream)
                "either" -> AppServerChannel.entries
                else -> error("Unsupported channel classification for ${typeOf(fixture)}")
            }
            channels.forEach { channel ->
                val decoded = AppServerProtocol.decodeFrame(fixture.toString(), channel)
                assertEquals(typeOf(fixture), decoded.frame.type)
                assertEquals(channel, decoded.channel)
                assertFalse(decoded.frame is AppServerInboundFrame.Unknown, "${typeOf(fixture)} must stay typed")
            }
        }
    }

    @Test
    fun baselineSeparatesObservedSocketBehaviorFromKotlinReconnectPolicy() {
        val matrix = fixtureJson("app-server-v2-contract-matrix.json")
        val observed = matrix["observed_installed_contract"]!!.jsonObject
        val upstream = matrix["upstream_socket_behavior"]!!.jsonObject
        val kotlinPolicy = matrix["kotlin_target_policy"]!!.jsonObject
        val reconnect = matrix["reconnect_classes"]!!.jsonObject

        assertEquals("app_server_v2", observed.requiredString("classification"))
        assertEquals("none; installed upstream help and declarations do not label app-server deprecated", observed.requiredString("deprecation_claim"))
        assertTrue(upstream.requiredString("second_control").contains("1008"))
        assertTrue(upstream.requiredString("stream_disconnect").contains("control session and runtime remain active"))
        assertTrue(upstream.requiredString("official_client_disconnect").contains("does not automatically close the sibling"))
        assertTrue(kotlinPolicy.requiredString("stream_disconnect").contains("rebuild both sockets"))
        assertTrue(kotlinPolicy.requiredString("note").contains("not required by the upstream server behavior"))
        assertTrue(reconnect.requiredString("turn_input").contains("do_not_blind_retry"))
        assertTrue(reconnect.requiredString("external_tool_result").contains("do_not_reexecute_tool"))
        assertTrue(reconnect.requiredString("event").contains("idempotency_key"))
    }

    @Test
    fun executedCliProbesPinInstalledVersionAndSurfaceClassification() {
        val matrix = fixtureJson("app-server-v2-contract-matrix.json")
        val baseline = matrix["baseline"]!!.jsonObject
        val probes = matrix["cli_probes"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it.requiredString("classification") }

        assertEquals("0.28.8", baseline.requiredString("version"))
        assertEquals("v24.18.0", baseline.requiredString("node"))
        assertEquals("v24.18.0\n", fixtureText(probes.getValue("installed_node_version").requiredString("fixture")))
        assertEquals("0.28.8 (Letta Code)\n", fixtureText(probes.getValue("installed_version").requiredString("fixture")))

        val serverListener = probes.getValue("server_listener")
        val appServer = probes.getValue("app_server_v2")
        assertProbe(serverListener, expectedUsage = "Usage: letta server", forbiddenUsage = "Usage: letta app-server")
        assertProbe(appServer, expectedUsage = "Usage: letta app-server", forbiddenUsage = "Usage: letta server")
        assertTrue("app_server_v2" in appServer.stringSet("capabilities"))
        assertTrue("app_server_v2" in serverListener.stringSet("not_capabilities"))
        assertTrue("cloud_environment_registration" in serverListener.stringSet("capabilities"))
        assertTrue("cloud_environment_registration" in appServer.stringSet("not_capabilities"))
    }

    @Test
    fun versionProbeAndProtocolInventoryAgreeWithBaseline() {
        val matrix = fixtureJson("app-server-v2-contract-matrix.json")
        val baseline = matrix["baseline"]!!.jsonObject
        val source = fixtureJson("installed-protocol-v2-inventory.json")["source"]!!.jsonObject

        assertEquals(baseline.requiredString("package"), source.requiredString("package"))
        assertEquals(baseline.requiredString("version"), source.requiredString("version"))
        assertEquals(baseline.requiredString("node"), source.requiredString("node_version"))
        assertEquals(baseline.requiredString("protocol_sha256"), source.requiredString("protocol_sha256"))
        assertTrue(fixtureText("cli-version.txt").startsWith(baseline.requiredString("version")))
        assertEquals("installed-protocol-v2-inventory.json", matrix["observed_installed_contract"]!!.jsonObject.requiredString("protocol_inventory_fixture"))
    }

    @Test
    fun fixturesAreSanitizedAndContainNoCredentialOrPatchLoaderMaterial() {
        val fixtureNames = listOf(
            "app-server-v2-contract-matrix.json",
            "installed-protocol-v2-inventory.json",
            "protocol-frames.jsonl",
            "cli-node-version.txt",
            "cli-version.txt",
            "cli-server-help.txt",
            "cli-app-server-help.txt",
        )
        val forbidden = listOf(
            "authorization: bearer", "sk-", "api_key", "refresh_token", "private_key",
            "[letta-code-patch]", "patch-loader", "/root/", "/tmp/letta-code-shell-shim",
        )

        fixtureNames.forEach { name ->
            val content = fixtureText(name)
            forbidden.forEach { marker ->
                assertFalse(content.lowercase().contains(marker.lowercase()), "$name contains forbidden marker $marker")
            }
        }
        fixtureNames.filter { it.endsWith(".json") }.forEach { name ->
            assertTokensRedacted(fixtureJson(name), name)
        }
        protocolFixtures().forEach { frame ->
            assertTokensRedacted(frame, "protocol-frames.jsonl:${typeOf(frame)}")
        }
        assertTrue(fixtureText("protocol-frames.jsonl").contains("\"token\":\"<redacted>\""))
    }

    private fun assertTokensRedacted(element: JsonElement, location: String) {
        when (element) {
            is JsonObject -> element.forEach { (name, value) ->
                if (name == "token") {
                    assertEquals("<redacted>", value.jsonPrimitive.content, "$location contains an unredacted token")
                } else {
                    assertTokensRedacted(value, "$location.$name")
                }
            }
            is JsonArray -> element.forEachIndexed { index, value ->
                assertTokensRedacted(value, "$location[$index]")
            }
            else -> Unit
        }
    }

    private fun assertProbe(probe: JsonObject, expectedUsage: String, forbiddenUsage: String) {
        assertEquals("0", probe["exit"]?.jsonPrimitive?.content)
        val output = fixtureText(probe.requiredString("fixture"))
        assertTrue(output.contains(expectedUsage))
        assertFalse(output.contains(forbiddenUsage))
    }

    private fun protocolFixtures(): List<JsonObject> =
        fixtureText("protocol-frames.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }
            .toList()

    private fun fixtureJson(name: String): JsonObject =
        AppServerProtocol.json.parseToJsonElement(fixtureText(name)).jsonObject

    private fun fixtureText(name: String): String {
        val stream = assertNotNull(javaClass.getResourceAsStream("/appserver/$name"), "missing fixture $name")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun typeOf(row: JsonObject): String = row.requiredString("type")

    private fun JsonObject.requiredString(name: String): String =
        assertNotNull(this[name], "missing $name").jsonPrimitive.content

    private fun JsonObject.stringSet(name: String): Set<String> =
        (this[name] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.requiredStringSet(name: String): Set<String> =
        assertNotNull(this[name] as? JsonArray, "missing $name").map { it.jsonPrimitive.content }.toSet()
}
