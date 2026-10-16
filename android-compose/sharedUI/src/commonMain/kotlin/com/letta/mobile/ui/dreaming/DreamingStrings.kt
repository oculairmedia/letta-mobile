package com.letta.mobile.ui.dreaming

import com.letta.mobile.data.dreaming.DreamActivity
import com.letta.mobile.data.dreaming.DreamCommitKind
import com.letta.mobile.data.dreaming.DreamSettingsScope
import com.letta.mobile.data.dreaming.DreamStartStatus
import com.letta.mobile.data.dreaming.DreamTrigger
import com.letta.mobile.data.dreaming.DreamUnsupportedReason
import com.letta.mobile.data.model.SubagentKind

/** letta-mobile-iczm7: the Dreaming surface's text. Our own words over letta-code's behaviour. */
object DreamingStrings {
    const val TITLE = "Dreaming"
    const val INTRO = "After a turn, a background run reviews recent conversations " +
        "and folds what it learned into memory, without interrupting your work."

    const val AUTOMATIC = "Dream automatically"
    const val WHEN = "When"
    const val REVIEW = "Agent reviews changes first"
    const val REVIEW_HINT = "A second background run on the agent's own model checks and revises the changes " +
        "before they reach memory, roughly doubling what each dream costs."
    const val REVIEW_INSTRUCTIONS = "Review instructions (optional)"
    const val SAVE = "Save"
    const val SAVED_FOR = "Applies to"
    const val MODEL = "Dreams use the agent's own model."
    const val STEPS_LABEL = "Steps between dreams"

    const val RUN_NOW = "Dream now"
    const val STARTING = "Starting…"
    const val FOCUS = "Focus (optional)"

    const val HISTORY = "Dreams that changed memory"
    const val HISTORY_EMPTY = "No dream has changed this agent's memory yet."
    const val HISTORY_LOADING = "Reading memory history…"
    const val HISTORY_UNAVAILABLE = "Memory history is not reachable over this connection."
    const val BACK = "Back"
    const val DETAIL_LOADING = "Loading changes…"
    const val NO_FILES = "This dream committed no file changes."

    const val NOW_IDLE = "Not dreaming"
    const val NOTIFY = "Notify me when a dream finishes"
    const val WINDOWS_BLOCKED = "On Windows, letta-code only dreams when you start one. Automatic dreams are off for this runtime."
    const val WINDOWS_ALLOW = "Allow automatic dreams"
    const val WINDOWS_ALLOWED = "Automatic dreams are allowed on this computer. They start after the local runtime restarts."

    fun trigger(trigger: DreamTrigger): String = when (trigger) {
        DreamTrigger.Off -> "Only when started"
        DreamTrigger.StepCount -> "Every few steps"
        DreamTrigger.CompactionEvent -> "After compaction"
    }

    /** The trigger in one plain sentence, for the status line. */
    fun triggerSentence(trigger: DreamTrigger, stepCount: Int): String = when (trigger) {
        DreamTrigger.Off -> "Dreams only when you start one."
        DreamTrigger.StepCount -> "Dreams after every $stepCount agent steps, once a turn finishes."
        DreamTrigger.CompactionEvent -> "Dreams after a turn in which the conversation was compacted."
    }

    fun scope(scope: DreamSettingsScope): String = when (scope) {
        DreamSettingsScope.LocalProject -> "This project"
        DreamSettingsScope.Global -> "All projects"
        DreamSettingsScope.Both -> "Both"
    }

    fun live(activity: DreamActivity): String = when (activity.kind) {
        SubagentKind.Memory -> "Tidying memory: ${activity.description}"
        else -> "Dreaming: ${activity.description.ifBlank { "reviewing recent conversations" }}"
    }

    fun kind(kind: DreamCommitKind): String = when (kind) {
        DreamCommitKind.Change -> "Dream"
        DreamCommitKind.Merge -> "Merged"
    }

    fun start(status: DreamStartStatus, output: String?): String = when (status) {
        DreamStartStatus.Started -> "Dream started. It runs in the background; its changes appear below when it finishes."
        DreamStartStatus.AlreadyRunning -> "A dream is already running for this conversation."
        DreamStartStatus.NothingNew -> "Nothing new to dream about since the last dream."
        DreamStartStatus.MemoryOff -> "Dreaming needs the memory filesystem (MemFS) on for this agent."
        DreamStartStatus.Failed -> output ?: "letta-code couldn't start a dream."
    }

    fun unsupported(reason: DreamUnsupportedReason): String = when (reason) {
        DreamUnsupportedReason.NoConnection -> "Dreaming needs a connection to letta-code: the bundled local runtime, an App Server, or an Iroh host."
        DreamUnsupportedReason.HostNeedsUpdate -> "This Iroh host is too old for dreaming controls. Update the host (meridian-iroh-wrapper)."
        DreamUnsupportedReason.NoNativeAppServer -> "This host has no App Server connection to letta-code, so it can't manage dreaming."
        DreamUnsupportedReason.RuntimeTooOld ->
            "This letta-code runtime can't dream on request yet (the on-phone runtime included); " +
                "use a desktop or server runtime."
    }

    fun failure(message: String): String = "Couldn't update dreaming: $message"

    fun lastDream(summary: String, whenText: String): String = "Last dream $whenText: $summary"

    fun stats(added: Int, removed: Int): String = "+$added −$removed"
}
