package com.letta.mobile.data.node

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeCompositionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateDir: File = Files.createTempDirectory("node-composition").toFile()
    private val lines = mutableListOf<String>()

    private val ports = NodePorts(
        httpClients = JvmAppServerHttpClients,
        log = object : NodeLog {
            override fun info(line: String) {
                lines += line
            }

            override fun error(line: String) {
                lines += "error: $line"
            }
        },
    )

    private fun node() = NodeComposition(
        config = NodeConfig(
            modelExposureFile = File(stateDir, "model-exposure.json").path,
            requestTimeoutMs = 1_000,
            endpoint = NodeEndpointConfig(bindAddr = "127.0.0.1:0"),
        ),
        ports = ports,
        scope = scope,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        stateDir.deleteRecursively()
    }

    @Test
    fun hostStateLivesBesideTheModelExposureFile() {
        val config = NodeConfig("relative/dir/model-exposure.json", 1, NodeEndpointConfig("0.0.0.0:0"))
        assertEquals(File("relative/dir").absoluteFile, config.hostStateDirectory)
    }

    @Test
    fun aNodeWithoutAnAppServerHasAControllerButNoClient() {
        val connection = node().connect(appServer = null, tools = ExternalToolRegistry.factoryDefault())
        assertNull(connection.client, "Iroh-only stub mode relays through no App Server client")
    }

    @Test
    fun theAdminRouterServesTheSubagentRegistryAndAdvertisesPushWhenAPublisherIsWired() {
        val controller = node().connect(appServer = null, tools = ExternalToolRegistry.factoryDefault()).controller
        val router = NodeAdminRouterSpec(
            controller = controller,
            eventScope = scope,
            subagentPublisher = { source: ControllerSubagentRegistrySource -> SubagentRegistryPublisher(scope, source) },
            // Production registers subagent.dismiss only over a durable store with a listener, as the host does.
            subagentRegistryFile = File(stateDir, "subagent-registry.json").path,
        ).build()
        assertTrue(AdminRpcRegistry.subagentMethods.all { it in router.registeredMethods })
        assertTrue(ControllerSubagentRegistrySource.PUSH_CAPABILITY in router.featureCapabilities)
    }
}
