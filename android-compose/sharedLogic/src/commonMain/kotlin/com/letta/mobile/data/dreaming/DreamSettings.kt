package com.letta.mobile.data.dreaming

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * When letta-code starts a dream (a background reflection run) on its own,
 * the `trigger` of `set_reflection_settings`. Runs start only after a successful turn; there is no
 * idle trigger.
 */
enum class DreamTrigger(val wire: String) {
    /** Never on its own; a dream runs only when someone starts one. */
    Off("off"),

    /** After the agent has taken [DreamSettings.stepCount] steps since the last dream. */
    StepCount("step-count"),

    /** After a turn in which the conversation was compacted (letta-code's default). */
    CompactionEvent("compaction-event"),
    ;

    companion object {
        /** Tolerates `step_count`-style spellings, which older hosts and fixtures used. */
        fun fromWire(value: String?): DreamTrigger? {
            val normalized = value?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** How a dream's memory changes land: merged straight away, or reviewed by the agent first. */
enum class DreamMerge(val wire: String) {
    /** The dream's branch is merged into memory when it finishes. */
    Auto("auto"),

    /** The agent reviews and revises the changes in a second background run before they merge. */
    Explicit("explicit"),
    ;

    companion object {
        fun fromWire(value: String?): DreamMerge? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Where letta-code saves the settings: `scope` of `set_reflection_settings`. */
enum class DreamSettingsScope(val wire: String) {
    /** The conversation's working directory (`.letta/settings.local.json`). */
    LocalProject("local_project"),

    /** Every project on this machine (`~/.letta/settings.json`). */
    Global("global"),

    /** Both files; letta-code's default. */
    Both("both"),
    ;

    companion object {
        fun fromWire(value: String?): DreamSettingsScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One agent's dreaming settings as letta-code stores them
 * (`reflection_settings` {trigger, step_count, merge, merge_instructions}). letta-code has no
 * dream model setting: a local-backend dream uses the agent's own model.
 */
@Immutable
data class DreamSettings(
    val trigger: DreamTrigger = DreamTrigger.CompactionEvent,
    val stepCount: Int = DEFAULT_STEP_COUNT,
    val merge: DreamMerge = DreamMerge.Auto,
    val mergeInstructions: String = "",
) {
    /** Dreams start on their own. */
    val automatic: Boolean get() = trigger != DreamTrigger.Off

    /** The `settings` object of `set_reflection_settings`; letta-code requires `step_count` even when off. */
    fun toWire(): JsonObject = buildJsonObject {
        put(TRIGGER, trigger.wire)
        put(STEP_COUNT, stepCount.coerceAtLeast(MIN_STEP_COUNT))
        put(MERGE, merge.wire)
        put(MERGE_INSTRUCTIONS, mergeInstructions)
    }

    companion object {
        /** letta-code's defaults (`DEFAULT_REFLECTION_SETTINGS`). */
        const val DEFAULT_STEP_COUNT: Int = 25
        const val MIN_STEP_COUNT: Int = 1

        const val TRIGGER: String = "trigger"
        const val STEP_COUNT: String = "step_count"
        const val MERGE: String = "merge"
        const val MERGE_INSTRUCTIONS: String = "merge_instructions"

        val DEFAULT: DreamSettings = DreamSettings()

        /**
         * Reads a `reflection_settings` object. Null when there is none or its trigger is not one
         * letta-code knows; a missing field takes letta-code's default, as its device status does.
         */
        fun fromWire(settings: JsonObject?): DreamSettings? {
            settings ?: return null
            val trigger = DreamTrigger.fromWire(settings.text(TRIGGER)) ?: return null
            return DreamSettings(
                trigger = trigger,
                stepCount = settings.int(STEP_COUNT)?.takeIf { it >= MIN_STEP_COUNT } ?: DEFAULT_STEP_COUNT,
                merge = DreamMerge.fromWire(settings.text(MERGE)) ?: DreamMerge.Auto,
                mergeInstructions = settings.text(MERGE_INSTRUCTIONS).orEmpty(),
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    }
}
