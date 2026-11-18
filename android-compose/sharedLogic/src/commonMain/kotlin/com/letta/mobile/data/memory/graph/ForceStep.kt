package com.letta.mobile.data.memory.graph

/** One integration step's motion and pin policy, shared by static and live relaxation. */
internal class ForceStep(
    private val pinned: Int,
    private val anchor: Int,
    val motion: MemoryGraphMotion,
    val alpha: Float = 1f,
) {
    fun holds(node: Int): Boolean = node == pinned || node == anchor
}
