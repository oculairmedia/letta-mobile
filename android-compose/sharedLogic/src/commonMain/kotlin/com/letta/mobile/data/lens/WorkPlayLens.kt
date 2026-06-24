package com.letta.mobile.data.lens

/**
 * The Work / Play "lens" over the same underlying agents, memory, and
 * conversations. Per the App Mockups v2 design: "'Play' is a lens, not a
 * different app: same agents, memory & groups — Characters = agents,
 * Worlds & Lore = memory blocks/lorebooks, Scenes = conversations,
 * Personas = your agent."
 *
 * Only the *vocabulary and presentation* change between modes; the data and
 * destinations are identical. Keeping this mapping in commonMain lets both the
 * desktop shell and (later) the mobile app drive the same relabeling from one
 * source of truth.
 */
enum class WorkPlayMode {
    Work,
    Play,
    ;

    val other: WorkPlayMode get() = if (this == Work) Play else Work
}

/** A navigable section in the shell, lens-agnostic. */
enum class LensDestination {
    Memory,
    Schedules,
    Channels,
    Skills,
    Conversations,
}

/**
 * Resolves the lens-specific vocabulary for the shell chrome. Platform UIs pass
 * the current [WorkPlayMode] and read labels/placeholders from here instead of
 * hard-coding "Memory" / "New chat" / "Message {agent}…".
 */
object WorkPlayLens {
    /** Label for the segmented mode toggle option. */
    fun modeLabel(mode: WorkPlayMode): String = when (mode) {
        WorkPlayMode.Work -> "Work"
        WorkPlayMode.Play -> "Play"
    }

    /** The relabeled nav item for [destination] in the current [mode]. */
    fun destinationLabel(mode: WorkPlayMode, destination: LensDestination): String = when (destination) {
        LensDestination.Memory -> when (mode) {
            WorkPlayMode.Work -> "Memory"
            WorkPlayMode.Play -> "Worlds & Lore"
        }
        LensDestination.Schedules -> "Schedules"
        LensDestination.Channels -> "Channels"
        LensDestination.Skills -> when (mode) {
            WorkPlayMode.Work -> "Skills"
            WorkPlayMode.Play -> "Characters"
        }
        LensDestination.Conversations -> when (mode) {
            WorkPlayMode.Work -> "Chats"
            WorkPlayMode.Play -> "Scenes"
        }
    }

    /** Nav destinations shown in the current lens, in display order. */
    fun navDestinations(mode: WorkPlayMode): List<LensDestination> = when (mode) {
        WorkPlayMode.Work -> listOf(
            LensDestination.Memory,
            LensDestination.Schedules,
            LensDestination.Channels,
            LensDestination.Skills,
        )
        // Play focuses the roleplay vocabulary: Characters (agents/skills),
        // Worlds & Lore (memory). Schedules/Channels stay Work-only.
        WorkPlayMode.Play -> listOf(
            LensDestination.Skills,
            LensDestination.Memory,
        )
    }

    /** Label for the "start a new conversation" affordance. */
    fun newConversationLabel(mode: WorkPlayMode): String = when (mode) {
        WorkPlayMode.Work -> "New chat"
        WorkPlayMode.Play -> "New scene"
    }

    /** Section header above the conversation/scene list. */
    fun conversationsHeader(mode: WorkPlayMode): String = when (mode) {
        WorkPlayMode.Work -> "Pinned"
        WorkPlayMode.Play -> "Scenes"
    }

    /** Composer placeholder: "Message {agent}…" vs "Message the scene…". */
    fun composerPlaceholder(mode: WorkPlayMode, agentName: String): String = when (mode) {
        WorkPlayMode.Work -> "Message ${agentName.ifBlank { "the agent" }}…"
        WorkPlayMode.Play -> "Message the scene…"
    }

    /** Noun for the focused entity ("agent" vs "character"). */
    fun agentNoun(mode: WorkPlayMode): String = when (mode) {
        WorkPlayMode.Work -> "agent"
        WorkPlayMode.Play -> "character"
    }
}
