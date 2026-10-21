package com.letta.mobile.data.context.limit

import com.letta.mobile.data.compaction.AdminRpcCompactionRepository
import com.letta.mobile.data.compaction.AppServerCompactionRepository
import com.letta.mobile.data.compaction.CompactionRepository
import com.letta.mobile.data.compaction.orElse
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.appserver.AppServerClient

/**
 * Where the agent context card's
 * compaction and letta-code's `/context-limit` go. The Iroh host's relay ([rpc]) first and, where it
 * does not serve them, the App Server [client] directly. Desktop and the web bind the same routes.
 */
class AgentContextRoutes(rpc: AdminRpcInvoker?, client: suspend () -> AppServerClient?) {
    val compaction: CompactionRepository = AppServerCompactionRepository(client = client).let { direct ->
        rpc?.let { AdminRpcCompactionRepository(it).orElse(direct) } ?: direct
    }

    val contextLimit: ContextLimitRepository = AppServerContextLimitRepository(client = client).let { direct ->
        rpc?.let { AdminRpcContextLimitRepository(it).orElse(direct) } ?: direct
    }
}
