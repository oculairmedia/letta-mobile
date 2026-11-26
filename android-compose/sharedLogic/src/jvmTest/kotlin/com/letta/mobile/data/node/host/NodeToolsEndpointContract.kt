package com.letta.mobile.data.node.host

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.MeridianExit
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWire
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWireRequest
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWireResponse
import com.letta.mobile.data.meridian.socket.MeridianToolsConfig
import com.letta.mobile.data.meridian.socket.MeridianToolsEndpoint
import com.letta.mobile.data.meridian.socket.MeridianToolsSocketServer
import com.letta.mobile.data.meridian.socket.MeridianToolsTransport
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What every OS's local tools endpoint must do: started from the host's default config, it answers
 * a `meridian/tools/1` request found the way the `meridian` shim finds it (the socket if present,
 * else the TCP descriptor file), refuses a caller with no live shell call, and keeps any bearer
 * token in a file only this user can read.
 */
abstract class NodeToolsEndpointContract {
    /** The bindings under test. */
    protected abstract val bindings: NodeHostBindings

    protected val dir: Path = Files.createTempDirectory("tools-endpoint")
    protected val socket: Path = dir.resolve("t.sock")
    protected val endpointFile: Path = dir.resolve("meridian/tools.endpoint")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    /** Starts the endpoint the way a host would: its default (auto) transport, adjusted by [bindings]. */
    protected fun startEndpoint(frames: kotlinx.coroutines.flow.Flow<AppServerReceivedFrame> = emptyFlow()) = runBlocking {
        val requested = MeridianToolsConfig(
            enabled = true,
            transport = MeridianToolsTransport.AUTO,
            socketPath = socket.toString(),
            endpointFile = endpointFile.toString(),
        )
        MeridianToolsEndpoint(bindings.toolsConfig(requested), ExternalToolRegistry.hostTools(emptyList()), frames, {}).start(scope)
    }

    /** The shim's endpoint lookup: the socket when it exists, else loopback TCP from the descriptor. */
    protected fun callLikeTheShim(request: MeridianToolsWireRequest): MeridianToolsWireResponse {
        if (Files.exists(socket)) {
            return exchange(SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket)) }, request)
        }
        val descriptor = Json.parseToJsonElement(Files.readString(endpointFile)).jsonObject
        val port = descriptor.getValue("port").jsonPrimitive.int
        val token = descriptor.getValue("token").jsonPrimitive.content
        val channel = SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        return exchange(channel, request.copy(token = token))
    }

    @Test
    fun answersTheShimAndRefusesACallerWithNoLiveShellCall() {
        assertNotNull(startEndpoint())

        val response = callLikeTheShim(MeridianToolsWireRequest(listOf("canvas", "list"), agentId = "agent-a", conversationId = "conv-a"))

        assertEquals(MeridianExit.DENIED, response.exitCode)
    }

    @Test
    fun anyBearerTokenIsReadableByThisUserAlone() {
        assertNotNull(startEndpoint())

        assertTrue(!Files.exists(endpointFile) || isOwnerOnly(endpointFile), "the token file must be owner-only")
    }

    protected fun exchange(channel: SocketChannel, request: MeridianToolsWireRequest): MeridianToolsWireResponse = channel.use {
        val bytes = ByteBuffer.wrap((MeridianToolsWire.encodeRequest(request) + "\n").toByteArray())
        while (bytes.hasRemaining()) it.write(bytes)
        MeridianToolsWire.decodeResponse(requireNotNull(MeridianToolsSocketServer.readLine(Channels.newInputStream(it))))
    }

    /** Owner-only by the view the filesystem supports: `rw-------` on POSIX, else an ACL naming only the owner. */
    protected fun isOwnerOnly(file: Path): Boolean {
        Files.getFileAttributeView(file, PosixFileAttributeView::class.java)?.let {
            return PosixFilePermissions.toString(it.readAttributes().permissions()) == "rw-------"
        }
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        assumeTrue("neither a POSIX nor an ACL permission view on this filesystem", acl != null)
        val owner = Files.getOwner(file)
        return acl!!.acl.all { entry -> entry.principal() == owner }
    }
}

/** Linux: the configured Unix socket. */
class LinuxNodeToolsEndpointContractTest : NodeToolsEndpointContract() {
    override val bindings: NodeHostBindings = LinuxNodeBindings
}

/** Windows: loopback TCP only, its bearer token in an owner-only file. */
class WindowsNodeToolsEndpointContractTest : NodeToolsEndpointContract() {
    override val bindings: NodeHostBindings = WindowsNodeBindings

    @Test
    fun servesLoopbackTcpWithABearerTokenAndNeverAUnixSocket() {
        assertNotNull(startEndpoint())

        val descriptor = Json.parseToJsonElement(Files.readString(endpointFile)).jsonObject
        val port = descriptor.getValue("port").jsonPrimitive.int
        val withoutToken = exchange(
            SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(), port)),
            MeridianToolsWireRequest(issueToolToken = "call-1"),
        )

        assertFalse(Files.exists(socket), "no Unix socket on Windows")
        assertEquals("127.0.0.1", descriptor.getValue("host").jsonPrimitive.content)
        assertEquals(MeridianExit.DENIED, withoutToken.exitCode)
    }

    @Test
    fun theTokenFileAclAllowsOnlyTheCurrentUser() {
        assumeTrue("Windows ACLs only", NodeHostOs.current() == NodeHostOs.WINDOWS)
        assertNotNull(startEndpoint())

        val entries = Files.getFileAttributeView(endpointFile, AclFileAttributeView::class.java).acl
        val user = endpointFile.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))

        assertEquals(1, entries.size, entries.toString())
        assertEquals(AclEntryType.ALLOW, entries.single().type())
        assertEquals(user, entries.single().principal())
    }
}
