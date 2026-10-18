package com.letta.mobile.data.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Which resident cache a host or shared surface bound. */
enum class MemoryCacheKind {
    Images,
    Canvas,
    Attachments,
}

fun interface MemoryCacheDropper {
    fun drop()
}

/**
 * Hosts bind droppers (Coil, desktop image bodies). Shared consumers call [drop];
 * binding is not a subscription.
 */
class MemoryCachePorts {
    private val lock = SynchronizedObject()
    private val droppers = mutableMapOf<MemoryCacheKind, MutableList<MemoryCacheDropper>>()

    fun bind(kind: MemoryCacheKind, dropper: MemoryCacheDropper): () -> Unit = synchronized(lock) {
        droppers.getOrPut(kind) { mutableListOf() }.add(dropper)
        return { unbind(kind, dropper) }
    }

    fun drop(kind: MemoryCacheKind) {
        snapshot(kind).forEach { it.drop() }
    }

    fun dropAll() {
        MemoryCacheKind.entries.forEach(::drop)
    }

    private fun unbind(kind: MemoryCacheKind, dropper: MemoryCacheDropper) = synchronized(lock) {
        droppers[kind]?.remove(dropper)
    }

    private fun snapshot(kind: MemoryCacheKind): List<MemoryCacheDropper> = synchronized(lock) {
        droppers[kind].orEmpty().toList()
    }
}
