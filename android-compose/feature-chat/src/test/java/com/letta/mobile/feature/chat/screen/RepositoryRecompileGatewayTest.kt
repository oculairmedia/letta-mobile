package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.testutil.FakeConversationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/** letta-mobile-k5nbq.15: `/recompile` goes wherever the repository reports a route, on any host. */
@Tag("unit")
class RepositoryRecompileGatewayTest {
    @Test
    fun aSessionWithARecompileRouteRecompiles() = runTest {
        val repository = FakeConversationRepository().apply { canRecompile = true }
        RepositoryRecompileGateway(repository)
            .recompileConversation(ConversationRecompileRequest("conv-1", "agent-1"))
        assertTrue(repository.calls.any { it.startsWith("recompileConversation:conv-1") })
    }

    @Test
    fun aSessionWithoutARecompileRouteRefusesWithoutAsking() = runTest {
        val repository = FakeConversationRepository()
        val thrown = runCatching {
            RepositoryRecompileGateway(repository)
                .recompileConversation(ConversationRecompileRequest("conv-1", "agent-1"))
        }.exceptionOrNull()
        assertTrue(thrown is UnsupportedOperationException)
        assertTrue(repository.calls.none { it.startsWith("recompileConversation") })
    }
}
