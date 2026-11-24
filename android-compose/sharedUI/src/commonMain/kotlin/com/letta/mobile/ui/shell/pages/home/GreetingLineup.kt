package com.letta.mobile.ui.shell.pages.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.shell.tokens.ShellDimens

/** One member offered to the greeting's lineup: an agent, or the Baken mark ([mark]), and whether it is busy now. */
internal data class LineupMember(val id: String, val busy: Boolean = false, val mark: Boolean = false)

/**
 * Where one member stands: its tile's left edge [x] from the stage's start, how far it sits below
 * (+) or above (-) the row's line ([lift]), its tile [size], its [tilt] in degrees, and whether it
 * draws over its neighbours ([front]).
 */
internal data class LineupSpot(
    val id: String,
    val x: Dp,
    val lift: Dp,
    val size: Dp,
    val tilt: Float,
    val front: Boolean,
    val mark: Boolean,
)

/**
 * The greeting's lineup: the members in order, each its own size, height, tilt and step of gap (a
 * step of overlap to a step of gap), all read from its id so the crew stands the same way every
 * visit. A busy member stands a size bigger, a little lower and in front. As many as fit in
 * [stageWidth] packed shoulder to shoulder are placed; the width they leave is then shared evenly
 * between them, so the first body stands at the stage's start and the last at its end (a lone member
 * stays at the start). [huddled] closes every gap by two steps and draws the row in to the middle.
 */
internal fun greetingLineup(members: List<LineupMember>, stageWidth: Dp, huddled: Boolean = false): List<LineupSpot> {
    val packed = packLineup(members, stageWidth)
    if (packed.isEmpty()) return emptyList()
    val joins = packed.size - 1
    val spread = if (joins == 0) 0.dp else (stageWidth - packed.last().bodyRight) / joins
    val huddle = if (huddled) ShellDimens.homeLineupNudge * 2 else 0.dp
    // The huddle takes the same amount off every gap, then centres the shorter row on the stage.
    val inset = huddle * joins / 2
    return packed.mapIndexed { index, placed ->
        val bodyLeft = placed.bodyLeft + (spread - huddle) * index + inset
        val trait = placed.trait
        LineupSpot(
            id = placed.member.id,
            x = bodyLeft - (trait.size - placed.body) / 2,
            lift = trait.lift,
            size = trait.size,
            tilt = trait.tilt,
            front = placed.member.busy,
            mark = placed.member.mark,
        )
    }
}

/** One member packed shoulder to shoulder from the stage's start: its body's left edge and width. */
private class PackedMember(val member: LineupMember, val trait: LineupTrait, val bodyLeft: Dp, val body: Dp) {
    val bodyRight: Dp get() = bodyLeft + body
}

/** The members, each at its own step of gap from the last, as many as fit in [stageWidth]. */
private fun packLineup(members: List<LineupMember>, stageWidth: Dp): List<PackedMember> {
    val packed = mutableListOf<PackedMember>()
    for (member in members) {
        val trait = LineupTrait.of(member)
        val body = trait.size * ShellDimens.homeLineupBodyShare
        val bodyLeft = packed.lastOrNull()?.let { it.bodyRight + trait.gap } ?: 0.dp
        if (bodyLeft + body > stageWidth) break
        packed += PackedMember(member, trait, bodyLeft, body)
    }
    return packed
}

/** The stage's height: the largest tile, with room for the lift either way. */
internal val GreetingStageHeight: Dp = ShellDimens.homeLineupLarge + ShellDimens.homeLineupLift * 2

/** A member's look in the lineup, from a stable hash of its id and whether it is busy. */
private class LineupTrait(val size: Dp, val lift: Dp, val tilt: Float, val gap: Dp) {
    companion object {
        fun of(member: LineupMember): LineupTrait {
            val hash = stableHash(member.id)
            val step = (hash % SIZE_STEPS + if (member.busy) 1 else 0).coerceAtMost(SIZE_STEPS - 1)
            val size = if (member.mark) ShellDimens.homeLineupMark else SIZES[step]
            val lift = ShellDimens.homeLineupLift * (signedStep(hash / SIZE_STEPS, LIFT_STEPS) / 2f + if (member.busy) BUSY_DROP else 0f)
            val tilt = signedStep(hash / (SIZE_STEPS * LIFT_STEPS), TILT_STEPS) * ShellDimens.homeLineupTiltStep
            val gap = ShellDimens.homeLineupNudge * signedStep(hash / (SIZE_STEPS * LIFT_STEPS * TILT_STEPS), GAP_STEPS)
            return LineupTrait(size, lift.coerceIn(-ShellDimens.homeLineupLift, ShellDimens.homeLineupLift), tilt, gap)
        }

        /** A step from -(steps / 2) to +(steps / 2), [steps] odd. */
        private fun signedStep(value: Int, steps: Int): Int = value % steps - steps / 2

        /**
         * FNV-1a over the id's characters, then a mixing finalizer so ids that differ in one character
         * still land far apart: the same on every platform and every launch.
         */
        private fun stableHash(id: String): Int {
            var hash = FNV_OFFSET
            for (char in id) hash = (hash xor char.code) * FNV_PRIME
            hash = (hash xor (hash ushr MIX_SHIFT_A)) * MIX_A
            hash = (hash xor (hash ushr MIX_SHIFT_B)) * MIX_B
            return (hash xor (hash ushr MIX_SHIFT_A)) and Int.MAX_VALUE
        }

        private val SIZES = listOf(ShellDimens.homeLineupSmall, ShellDimens.homeLineupMedium, ShellDimens.homeLineupLarge)
        private const val SIZE_STEPS = 3
        private const val LIFT_STEPS = 5
        private const val TILT_STEPS = 5
        private const val GAP_STEPS = 3

        /** A busy member sits half a lift lower: nearer the viewer. */
        private const val BUSY_DROP = 0.5f
        private const val FNV_OFFSET = -0x7ee3623b
        private const val FNV_PRIME = 16777619
        private const val MIX_A = 0x7feb352d
        private const val MIX_B = -0x7b935975
        private const val MIX_SHIFT_A = 16
        private const val MIX_SHIFT_B = 15
    }
}
