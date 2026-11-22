package com.letta.mobile.data.home.greeting

/** How many mascots share the greeting. */
const val GREETING_CAST_SIZE: Int = 3

/**
 * How many of an agent's runs have ended so far, well ([ok]) and not ([notOk]: failed or cancelled).
 * A count that grows while the greeting shows is a [GreetingInput.RunFinished] moment.
 */
data class GreetingRunTally(val ok: Int = 0, val notOk: Int = 0)
