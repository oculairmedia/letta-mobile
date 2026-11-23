package com.letta.mobile.data.home.greeting

/** At most this many agents are offered to the greeting's lineup; the stage shows as many as fit. */
const val GREETING_CAST_LIMIT: Int = 12

/**
 * How many of an agent's runs have ended so far, well ([ok]) and not ([notOk]: failed or cancelled).
 * A count that grows while the greeting shows is a [GreetingInput.RunFinished] moment.
 */
data class GreetingRunTally(val ok: Int = 0, val notOk: Int = 0)

/** One agent as the greeting's status line sees it: its name, its mood and how much it did today. */
data class GreetingStatusAgent(val name: String, val mood: GreetingMood, val updatesToday: Int = 0)

/**
 * The greeting's second line, from what the agents are doing: who needs the user, who is stuck,
 * who is busy now, who was busy today, or a quiet note. Short and in the agents' favour.
 */
fun greetingStatusLine(agents: List<GreetingStatusAgent>): String {
    agents.firstOrNull { it.mood == GreetingMood.WAITING }?.let { return "${it.name} needs you" }
    agents.firstOrNull { it.mood == GreetingMood.ERROR }?.let { return "${it.name} hit a snag" }
    val busy = agents.filter { it.mood.busy }
    return when {
        busy.size == 1 -> "${busy[0].name} is on it"
        busy.size == 2 -> "${busy[0].name} and ${busy[1].name} are busy"
        busy.size > 2 -> "${busy.size} agents are busy"
        else -> quietLine(agents)
    }
}

private fun quietLine(agents: List<GreetingStatusAgent>): String {
    if (agents.isEmpty()) return "Your agents will gather here"
    val busiest = agents.maxBy { it.updatesToday }
    return if (busiest.updatesToday >= BUSY_DAY_UPDATES) "${busiest.name}'s been busy today" else "All quiet. Your agents are resting"
}

/** Conversation updates in a day that make an agent's day a busy one. */
private const val BUSY_DAY_UPDATES = 3
