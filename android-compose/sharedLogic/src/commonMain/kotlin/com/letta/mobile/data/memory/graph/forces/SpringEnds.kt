package com.letta.mobile.data.memory.graph.forces

/** The two node indices of a spring, also used to collect each node's owners. */
internal data class SpringEnds(val source: Int, val target: Int) {
    fun contains(node: Int): Boolean = source == node || target == node

    companion object {
        fun at(springs: IntArray, spring: Int): SpringEnds = SpringEnds(springs[2 * spring], springs[2 * spring + 1])
    }
}
