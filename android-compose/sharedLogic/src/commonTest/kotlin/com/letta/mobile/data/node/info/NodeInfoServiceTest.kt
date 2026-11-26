package com.letta.mobile.data.node.info

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.rpcwire.AdminRpcErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

class NodeInfoServiceTest {
    @Test
    fun bootLineParsesAppliedAndSkippedCounts() {
        val set = RuntimePatchBootLine.parse("[stderr] [letta-code-patch] lcp-patches applied=36 skipped=0")

        assertEquals(RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 36, skipped = 0), set)
        assertNull(RuntimePatchBootLine.parse("[letta-code-patch] applied 36 runtime patch(es) to /opt/letta.js"))
    }

    @Test
    fun observerKeepsTheLatestBootSummaryOnly() {
        val observer = RuntimePatchBootLineObserver()
        assertEquals(RuntimePatchSet.UNKNOWN, observer.current())

        observer.observe("lcp-patches applied=30 skipped=0")
        observer.observe("some other line")
        observer.observe("lcp-patches applied=36 skipped=1")

        assertEquals(36, observer.current().applied)
        assertEquals(1, observer.current().skipped)
    }

    @Test
    fun readsFactsVersionsModelsAndPatches() = runTest {
        val facts = NodeFacts(
            os = NodeOs.family("Windows 11"),
            appVersion = "1.4.0",
            capabilities = setOf(NodeCapability.STORE, NodeCapability.UI),
            runtimePatches = { RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 36, skipped = 0) },
        )
        val runtime = NodeRuntimeFacts.of({ "0.34.70" }) { listOf("anthropic/claude-sonnet-4-5", "openai/gpt-5", "openai/gpt-5") }

        val info = NodeInfoService(facts, runtime).read()

        assertEquals("windows", info.os)
        assertEquals("1.4.0", info.appVersion)
        assertEquals("0.34.70", info.lettaCodeVersion)
        assertEquals(StoreFormatVersion.CURRENT, info.storeFormat)
        assertEquals(listOf("ui", "store"), info.capabilities, "in the role table's order")
        assertEquals(listOf("anthropic/claude-sonnet-4-5", "openai/gpt-5"), info.models)
        assertEquals(36, info.runtimePatches.applied)
    }

    @Test
    fun aFailingAppServerReadsUnknownAndEmptyNeverAFailure() = runTest {
        val runtime = NodeRuntimeFacts.of({ error("not connected") }) { error("App Server down") }

        val info = NodeInfoService(NodeFacts.UNKNOWN, runtime).read()

        assertEquals(NodeInfoWire.UNKNOWN, info.lettaCodeVersion)
        assertEquals(emptyList(), info.models)
        assertEquals(RuntimePatchSet.UNKNOWN, info.runtimePatches)
    }

    @Test
    fun noPathSecretOrFreeTextReachesTheWire() = runTest {
        val facts = NodeFacts(
            os = "C:\\Users\\someone",
            appVersion = "/opt/baken/app 1.0 token=sk-abc",
            runtimePatches = { RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 1, skipped = 0, digest = "/root/x") },
        )
        val runtime = NodeRuntimeFacts.of({ "/root/letta-code-0.34.39" }) {
            listOf("/etc/passwd", "C:\\models\\x", "D:relative", "../escape", "ok/model-1")
        }

        val json = NodeInfoService(facts, runtime).readJson().toString()

        val info = NodeInfoService(facts, runtime).read()
        assertEquals(NodeInfoWire.UNKNOWN, info.os)
        assertEquals(NodeInfoWire.UNKNOWN, info.appVersion)
        assertEquals(NodeInfoWire.UNKNOWN, info.lettaCodeVersion)
        assertEquals(listOf("ok/model-1"), info.models)
        assertNull(info.runtimePatches.digest)
        listOf("/root", "/etc", "/opt", "Users", "sk-abc", "\\\\").forEach { leak ->
            assertTrue(leak !in json, "node.info leaked '$leak': $json")
        }
    }

    @Test
    fun theWireCarriesEveryFieldEvenWhenUnknown() = runTest {
        val result = NodeInfoService(NodeFacts.UNKNOWN).readJson().jsonObject

        assertEquals(
            setOf("schema", "os", "app_version", "letta_code_version", "store_format", "capabilities", "models", "runtime_patches"),
            result.keys,
        )
        assertEquals(setOf("source", "applied", "skipped", "patch_ids_sha256"), result.getValue("runtime_patches").jsonObject.keys)
    }

    @Test
    fun handshakeJudgesTheNodeItReads() = runTest {
        val wire = NodeInfoService(NodeFacts(capabilities = setOf(NodeCapability.CATALOG))).readJson()
        val reference = NodeInfo(lettaCodeVersion = "0.34.70")
        val calls = mutableListOf<String>()

        val result = NodeHandshake(AdminRpcInvoker { method, _ -> calls += method; wire }) { reference }.run()

        assertEquals(listOf(NodeInfoWire.METHOD), calls)
        val checked = assertIs<NodeHandshakeResult.Checked>(result)
        assertEquals(NodeAvailability.COMPATIBLE, checked.compatibility.availability, "an unknown version is not a mismatch")
    }

    @Test
    fun handshakeTreatsAnUnknownMethodOrAdminGateAsALegacyNode() = runTest {
        val unknown = NodeHandshake(AdminRpcInvoker { _, _ -> throw ModelControlException(AdminRpcErrors.unknownMethod("node.info")) })
        val gated = NodeHandshake(AdminRpcInvoker { _, _ -> throw ModelControlException("forbidden") })
        val broken = NodeHandshake(AdminRpcInvoker { _, _ -> throw ModelControlException("Not connected to a host") })

        assertEquals(NodeHandshakeResult.Legacy, unknown.run())
        assertEquals(NodeHandshakeResult.Legacy, gated.run())
        assertEquals(NodeHandshakeResult.Failed("Not connected to a host"), broken.run())
    }
}
