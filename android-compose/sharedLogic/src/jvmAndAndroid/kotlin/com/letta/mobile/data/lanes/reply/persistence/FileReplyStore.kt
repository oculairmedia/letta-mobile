package com.letta.mobile.data.lanes.reply.persistence

import com.letta.mobile.data.lanes.reply.ReplyStore
import com.letta.mobile.data.lanes.reply.ReplySnapshot

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Binds the shared reply policy to the host identity directory's atomic journal. */
fun fileReplyStore(identityDirectory: File, clock: () -> Long = System::currentTimeMillis): ReplyStore {
    val file = File(identityDirectory, "reply-routing/pending.json").toPath().toAbsolutePath()
    return ReplyStore(transaction = { update ->
        withContext(Dispatchers.IO) {
            AtomicJsonFile.withProcessLock(file) {
                val state = AtomicJsonFile.readOrNull(file)?.let {
                    LettaJson.persisted.decodeFromString<ReplySnapshot>(it)
                } ?: ReplySnapshot()
                val next = update(state)
                AtomicJsonFile.write(file, LettaJson.persisted.encodeToString(next), requireAtomic = true)
                next
            }
        }
    }, clock = clock, endpointFor = { IrohAgentIdentity.knownNodeIdHex(com.letta.mobile.data.model.AgentId(it), identityDirectory) },
        processEpoch = replyProcessEpoch,
        canonicalAgent = com.letta.mobile.data.model.AgentIdNamespace::normalizeToBareId)
}

private val replyProcessEpoch = java.util.UUID.randomUUID().toString()
