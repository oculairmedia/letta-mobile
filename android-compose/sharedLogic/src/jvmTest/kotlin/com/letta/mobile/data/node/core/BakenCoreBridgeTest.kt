package com.letta.mobile.data.node.core

import com.letta.mobile.core.ffi.AddMemberRequest
import com.letta.mobile.core.ffi.AgentRuntimeSpec
import com.letta.mobile.core.ffi.CoreException
import com.letta.mobile.core.ffi.GenesisRequest
import com.letta.mobile.core.ffi.HostException
import com.letta.mobile.core.ffi.InProcessNetwork
import com.letta.mobile.core.ffi.MemberPreset
import com.letta.mobile.core.ffi.MemberStatus
import com.letta.mobile.core.ffi.MembershipView
import com.letta.mobile.core.ffi.NodeClass
import com.letta.mobile.core.ffi.NodeEvent
import com.letta.mobile.core.ffi.NodeOptions
import com.letta.mobile.core.ffi.QuiesceOutcome
import com.letta.mobile.core.ffi.RuntimeHost
import com.letta.mobile.core.ffi.RuntimeInfo
import com.letta.mobile.core.ffi.Transport
import com.letta.mobile.core.ffi.nodeIdOfSecretKey
import com.letta.mobile.core.ffi.signAddMemberOp
import com.letta.mobile.core.ffi.signGenesisOp
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.model.AgentId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two Rust core nodes in this JVM, across the real bridge: the Windows (or Linux) `baken_core`
 * library loaded through JNA, the generated bindings, and this package's adapter. Node A holds the
 * constellation's genesis and the add of B; B starts with genesis only, so it learns it is a
 * member from A once they connect.
 *
 * Runs only where the library is built: Gradle passes `bakenCore.libraryDir` (and points JNA at it)
 * when `core/target/ffi-release` holds one; elsewhere, including CI's Kotlin jobs, it is skipped.
 */
class BakenCoreBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("baken-core-bridge").toFile()

    @BeforeTest
    fun requireLibrary() {
        val libraryDir = System.getProperty("bakenCore.libraryDir")
        assumeTrue("baken_core is not built; build core-ffi with the ffi-release profile to run this.", libraryDir != null)
    }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun twoNodesConnectAndReadNodeInfoAndMembershipAcrossTheBridge(): Unit = runBlocking {
        val keyA = ByteArray(KEY_BYTES) { 1 }
        val keyB = ByteArray(KEY_BYTES) { 2 }
        val genesis = signGenesisOp(keyA, GenesisRequest("node-a", "home", ByteArray(CATALOG_ROOT_BYTES), 1uL))
        val add = signAddMemberOp(
            keyA,
            AddMemberRequest(genesis.opId, listOf(genesis.opId), nodeIdOfSecretKey(keyB), MemberPreset.DesktopNode, "node-b", 2uL),
        )
        val network = InProcessNetwork()
        val signals = MutableSharedFlow<Boolean>(replay = 1)
        val a = node("a", keyA, genesis.opId, listOf(genesis.envelope, add.envelope), network, PlatformSignals(foreground = signals))
        val b = node("b", keyB, genesis.opId, listOf(genesis.envelope), network, PlatformSignals())
        try {
            assertFailsWith<CoreException.NotRunning> { a.info() }
            val idA = a.start()
            val idB = b.start()
            assertEquals(nodeIdOfSecretKey(keyB), idB)

            val info = a.info()
            assertEquals(1u, info.schema)
            assertEquals("0.34.70", info.lettaCodeVersion)
            assertEquals(1uL, info.storeFormat.major)
            assertTrue("store" in info.capabilities, info.toString())
            assertEquals(2, a.membership().activeMembers())
            assertEquals(1, b.membership().activeMembers())

            b.connect(a.address())
            val view = withTimeout(TIMEOUT_MS) { b.membershipViews().first { it.activeMembers() == 2 } }
            assertEquals(genesis.opId, view.constellation)
            assertEquals("home", view.label)
            assertEquals("node-a", view.members.single { it.nodeId == idA }.name)
            assertTrue("admin.full" in view.members.single { it.nodeId == idA }.capabilities)
            assertEquals(2, b.membership().activeMembers())

            val events = scope.async { a.nodeEvents().first { it is NodeEvent.ForegroundChanged } }
            delay(SUBSCRIBE_SETTLE_MS)
            signals.emit(false)
            assertEquals(NodeEvent.ForegroundChanged(false), withTimeout(TIMEOUT_MS) { events.await() })

            assertNull(a.placement(AgentId("agent-unknown")))
            assertFailsWith<CoreException.InvalidArgument> { a.catalogViewJson("not a url") }
            a.stop()
            assertFailsWith<CoreException.NotRunning> { a.membership() }
        } finally {
            a.stop()
            b.stop()
            a.close()
            b.close()
        }
    }

    private fun node(
        name: String,
        key: ByteArray,
        constellation: String,
        ops: List<ByteArray>,
        network: InProcessNetwork,
        signals: PlatformSignals,
    ): CoreNodeHandle = UniffiCoreNode.create(
        NodeOptions(
            backendDir = File(dir, "$name/backend").path,
            dataDir = File(dir, "$name/data").path,
            `class` = NodeClass.DESKTOP,
            appVersion = "1.0.0",
            workerThreads = 2u,
            constellation = constellation,
            membershipOps = ops,
            tickMs = 200uL,
            watchDebounceMs = 50uL,
        ),
        Transport.InProcess(network),
        IdleRuntimeHost,
        CorePlatformHooks(FixedIrohSecretKeyStore(key), signals, scope),
    )

    private fun MembershipView.activeMembers(): Int = members.count { it.status == MemberStatus.ACTIVE }

    /** A runtime host with nothing to run; it reports a letta-code version for node.info. */
    private object IdleRuntimeHost : RuntimeHost {
        override fun info() = RuntimeInfo("0.34.70", null, null, null, emptyList())

        override suspend fun start(spec: AgentRuntimeSpec): String = throw HostException.NotRunning()

        override suspend fun stop(agent: String) = Unit

        override suspend fun quiesce(agent: String, timeoutMs: ULong) = QuiesceOutcome.IDLE

        override suspend fun checkpoint(agent: String) = Unit

        override suspend fun abortTurn(agent: String) = Unit

        override fun endpoint(): String? = null
    }

    private companion object {
        const val KEY_BYTES = 32
        const val CATALOG_ROOT_BYTES = 16
        const val TIMEOUT_MS = 20_000L
        const val SUBSCRIBE_SETTLE_MS = 500L
    }
}
