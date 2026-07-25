package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
            // P0.5 (audit): read-only server metadata is now a benign CHAT_READ,
            // accessible to a standard paired desktop (not admin-gated).
            "provider.list", "goal.get", "group.list", "folder.list", "archive.list",
            "step.list", "identity.list", "identity.get", "run.list", "run.get",
            // Regression fix: agent.update (model selection) and agent.context
            // (context-window UI) fell to else->ADMIN_FULL once the admin_rpc
            // stream path started enforcing per-method capabilities, breaking
            // both on every paired device. agent.context is a benign read
            // (missed in the P0.5 reclassification); agent.update is
            // desktop-manageable config editing (agent lifecycle stays admin.full).
            "agent.update", "agent.context",
        ).forEach { method ->
            assertTrue(
                IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod(method)),
                "desktop role should allow $method",
            )
        }
        // Denied: server administration + MUTATIONS still require explicit admin.full.
        listOf(
            "agent.create", "agent.delete",
            "health.check", "goal.command",
            "pair.invite.create", "pair.peer.list", "pair.peer.get",
            "pair.peer.rename", "pair.peer.set_capabilities", "pair.peer.revoke",
        ).forEach { method ->
            assertFalse(
                IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod(method)),
                "desktop role must NOT allow $method",
            )
        }
        assertFalse(IrohPeerCapabilities.ADMIN_FULL in desktopRole, "admin.full is never implicit")
    }

    @Test
    fun reflectionSettingsAreMemoryTierNotAdminOnly() {
        // lgns8.16: reflection/sleeptime (dreaming) settings control WHEN the agent
        // consolidates memory — a memory-management operation, not server admin.
        // reflection.get is a memory READ, reflection.set a memory WRITE, so a
        // standard paired device can use dreaming without admin.full. Before this
        // they fell into the `else -> ADMIN_FULL` deny-by-default bucket and were
        // silently unusable for every non-admin peer.
        assertEquals(IrohPeerCapabilities.MEMORY_READ, IrohPeerCapabilities.forAdminMethod("reflection.get"))
        assertEquals(IrohPeerCapabilities.MEMORY_WRITE, IrohPeerCapabilities.forAdminMethod("reflection.set"))
        assertTrue(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("reflection.get")),
            "a standard device must be able to READ reflection settings (dreaming not silently denied)",
        )
        assertTrue(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("reflection.set")),
            "a standard device must be able to SET reflection settings (dreaming not admin-gated)",
        )
        assertNotEquals(
            IrohPeerCapabilities.ADMIN_FULL,
            IrohPeerCapabilities.forAdminMethod("reflection.set"),
            "reflection is an intentional memory classification, not the deny-by-default admin bucket",
        )
    }

    @Test
    fun agentUpdateAndAgentContextAreDeskTopReachableButLifecycleStaysAdminFull() {
        // agent.context: benign read (context-window UI), CHAT_READ tier.
        assertEquals(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.forAdminMethod("agent.context"))
        assertTrue(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("agent.context")),
            "the default desktop role must be allowed agent.context",
        )

        // agent.update: trusted-desktop config editing (e.g. model selection),
        // classified into CONVERSATION_MANAGE which DEFAULT_DESKTOP_ROLE holds.
        assertEquals(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.forAdminMethod("agent.update"))
        assertTrue(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("agent.update")),
            "the default desktop role must be allowed agent.update (model selection)",
        )

        // Agent LIFECYCLE (create/delete) is unchanged: still admin.full via the
        // else branch, denied to the default desktop role.
        assertEquals(IrohPeerCapabilities.ADMIN_FULL, IrohPeerCapabilities.forAdminMethod("agent.create"))
        assertEquals(IrohPeerCapabilities.ADMIN_FULL, IrohPeerCapabilities.forAdminMethod("agent.delete"))
        assertFalse(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("agent.create")),
            "the default desktop role must NOT allow agent.create",
        )
        assertFalse(
            IrohPeerCapabilities.isAllowed(desktopRole, IrohPeerCapabilities.forAdminMethod("agent.delete")),
            "the default desktop role must NOT allow agent.delete",
        )
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
