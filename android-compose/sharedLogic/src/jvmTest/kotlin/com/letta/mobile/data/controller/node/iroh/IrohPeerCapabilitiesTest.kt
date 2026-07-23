package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrohPeerCapabilitiesTest {
    private object EmptySubagentSource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    private val desktopRole = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE
    private val adminFull = setOf(IrohPeerCapabilities.ADMIN_FULL)

    @Test
    fun everyRegisteredAdminMethodMapsToAKnownCapability() {
        val router = AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:0",
            controller = null,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(InMemoryPairedPeerStore()),
        )
        router.registeredMethods.forEach { method ->
            val capability = IrohPeerCapabilities.forAdminMethod(method)
            assertTrue(
                capability in IrohPeerCapabilities.ALL,
                "$method maps to unknown capability '$capability'",
            )
        }
    }

    @Test
    fun unknownMethodsRequireAdminFullByDefault() {
        assertEquals(IrohPeerCapabilities.ADMIN_FULL, IrohPeerCapabilities.forAdminMethod("brand.new.method"))
        assertFalse(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("brand.new.method")),
            "the default desktop role must not reach unclassified methods",
        )
    }

    @Test
    fun defaultDesktopRoleIsLeastPrivilegeButFunctional() {
        // Allowed: the working-desktop surface.
        listOf(
            "conversation.list", "message.list", "conversation.create", "approval.submit",
            "block.list", "block.update", "passage.create",
            "schedule.create", "skill.install", "tool.list", "project.list", "subagent.list",
        ).forEach { method ->
            assertTrue(
                IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod(method)),
                "desktop role should allow $method",
            )
        }
        // Denied: server administration requires explicit admin.full.
        listOf(
            "agent.create", "agent.delete", "identity.list", "run.list", "provider.list",
            "health.check", "goal.command",
            "pair.invite.create", "pair.peer.list", "pair.peer.get",
            "pair.peer.rename", "pair.peer.set_capabilities", "pair.peer.revoke",
            "archive.list", "folder.list", "group.list",
        ).forEach { method ->
            assertFalse(
                IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod(method)),
                "desktop role must NOT allow $method",
            )
        }
        assertFalse(IrohPeerCapabilities.ADMIN_FULL in desktopRole, "admin.full is never implicit")
    }

    @Test
    fun adminFullAllowsEverything() {
        listOf("agent.create", "pair.invite.create", "brand.new.method", "conversation.list").forEach { method ->
            assertTrue(IrohPeerCapabilities.isAllowed(adminFull, IrohPeerCapabilities.forAdminMethod(method)))
        }
    }

    @Test
    fun protocolCommandsMapToChatCapabilities() {
        assertEquals(IrohPeerCapabilities.CHAT_SEND, IrohPeerCapabilities.forProtocolCommand("runtime_start"))
        assertEquals(IrohPeerCapabilities.CHAT_SEND, IrohPeerCapabilities.forProtocolCommand("input"))
        assertEquals(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.forProtocolCommand("sync"))
        assertEquals(IrohPeerCapabilities.CHAT_SEND, IrohPeerCapabilities.forProtocolCommand("abort_message"))
    }

    @Test
    fun readOnlyPeerCannotWrite() {
        val readOnly = setOf(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.MEMORY_READ)
        assertTrue(IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forAdminMethod("message.list")))
        assertTrue(IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forAdminMethod("block.list")))
        assertFalse(IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forAdminMethod("block.update")))
        assertFalse(IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forAdminMethod("conversation.create")))
        assertFalse(IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forProtocolCommand("input")!!))
    }

    @Test
    fun pairedPeersDefaultToTheDesktopRoleAndPersistExplicitGrants() {
        val store = InMemoryPairedPeerStore()
        val pairing = IrohPairingService(store)
        val invite = pairing.createInvite("desk")
        pairing.redeem(invite.secret, "a".repeat(64))

        val peer = checkNotNull(pairing.peer("a".repeat(64)))
        assertEquals(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE, peer.capabilities)

        store.save(peer.copy(capabilities = peer.capabilities + IrohPeerCapabilities.ADMIN_FULL))
        assertTrue(
            IrohPeerCapabilities.ADMIN_FULL in checkNotNull(pairing.peer("a".repeat(64))).capabilities,
            "explicit admin.full grant must persist",
        )
    }
}
