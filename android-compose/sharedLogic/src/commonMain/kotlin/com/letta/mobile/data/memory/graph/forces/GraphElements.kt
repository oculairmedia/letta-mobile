package com.letta.mobile.data.memory.graph.forces

/**
 * A graph's elements as force tuning reads them, in the orders the force field was built with:
 * each node's kind ordinal and seed, and each spring's kind ordinal and seed (see [Variation]).
 * Empty seeds vary nothing.
 */
class GraphElements(
    val nodeKinds: IntArray,
    val springKinds: IntArray,
    val nodeSeeds: IntArray = IntArray(0),
    val springSeeds: IntArray = IntArray(0),
)
