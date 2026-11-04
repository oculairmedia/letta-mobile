package com.letta.mobile.data.model.reflection

import androidx.compose.runtime.Immutable

/** The reflection model value from `reflection_settings`, including legacy-host absence. */
@Immutable
sealed interface ReflectionModelChoice {
    /** The host omitted `model`, so the client must not show or write the setting. */
    data object Unsupported : ReflectionModelChoice

    /** JSON null: reflection inherits the agent's model. */
    data object Inherit : ReflectionModelChoice

    /** A concrete model [value] from the active session's model catalog. */
    data class Handle(val value: String) : ReflectionModelChoice
}

/** The known `last_outcome.status` values; [Unknown] keeps newer host values readable. */
enum class ReflectionLastOutcomeStatus(val wire: String) {
    /** The background reflection run was started. */
    Launched("launched"),

    /** The host decided there was no reflection work to start. */
    Skipped("skipped"),

    /** Reflection ended in an error. */
    Failed("failed"),

    /** Reflection finished. */
    Completed("completed"),

    /** A status introduced by a newer host. */
    Unknown("unknown"),
    ;

    companion object {
        /** Reads a wire status without making a newer value fail the whole settings response. */
        fun fromWire(value: String?): ReflectionLastOutcomeStatus =
            entries.firstOrNull { it != Unknown && it.wire == value?.trim()?.lowercase() } ?: Unknown
    }
}

/**
 * The host's latest reflection result, including runs that never launched or changed memory.
 * [at] is the host's ISO-8601 timestamp.
 */
@Immutable
data class ReflectionLastOutcome(
    val status: ReflectionLastOutcomeStatus,
    val reason: String? = null,
    val triggerSource: String? = null,
    val model: String? = null,
    val at: String,
)
