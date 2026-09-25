package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.ApprovalDecisionCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-qygvv.10: approval decisions whose `input_accepted` is still outstanding.
 *
 * The unleased answerer and the router's replay responder both see every `control_request` on
 * `client.events`. The decision is cached BEFORE the send, so when the answerer wins that race the
 * responder sees a cached decision for the very frame being answered and would re-send it. A
 * frame for a decision still in flight is a duplicate of that send, not a lost answer: the
 * in-flight send's own ack settles it. A real replay (after reconnect) arrives once it settled.
 */
internal class InFlightApprovalSends {
    private val lock = SynchronizedObject()
    private val keys = mutableSetOf<ApprovalDecisionCache.Key>()

    fun contains(key: ApprovalDecisionCache.Key): Boolean = synchronized(lock) { key in keys }

    suspend fun <T> track(key: ApprovalDecisionCache.Key, send: suspend () -> T): T {
        synchronized(lock) { keys += key }
        try {
            return send()
        } finally {
            synchronized(lock) { keys -= key }
        }
    }
}
