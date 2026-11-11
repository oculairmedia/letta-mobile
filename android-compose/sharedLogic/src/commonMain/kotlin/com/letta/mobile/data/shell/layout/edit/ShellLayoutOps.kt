package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLayoutConfig

/**
 * One change to a [ShellLayoutConfig], as data. A list of them is checked and applied by
 * [ShellLayoutEdits.apply]: all of them or none.
 */
sealed interface ShellLayoutOp {
    /**
     * [lane] holds exactly [slots], in this order.
     *
     * @property lane the lane id.
     * @property slots the lane's new slots, in order.
     */
    data class SetLane(val lane: String, val slots: List<ModuleSlot>) : ShellLayoutOp

    /**
     * [slot] is added to [lane] at [index] (at the end when null).
     *
     * @property lane the lane id.
     * @property slot the slot added.
     * @property index where it goes, 0 being first; null for the end.
     */
    data class Add(val lane: String, val slot: ModuleSlot, val index: Int? = null) : ShellLayoutOp

    /**
     * [slot] is taken out of [lane].
     *
     * @property lane the lane id.
     * @property slot the slot removed.
     */
    data class Remove(val lane: String, val slot: ModuleSlot) : ShellLayoutOp

    /**
     * [slot] moves from [lane] to [index] of [toLane] (the same lane by default; the end when
     * [index] is null). Positions count after the slot has left its old place.
     *
     * @property lane the lane the slot is in.
     * @property slot the slot moved.
     * @property toLane the lane it moves to.
     * @property index where it goes, 0 being first; null for the end.
     */
    data class Move(val lane: String, val slot: ModuleSlot, val toLane: String = lane, val index: Int? = null) : ShellLayoutOp

    /** Every lane goes back to [ShellLayoutConfig.Default]; later ops in the list apply to that. */
    data object Reset : ShellLayoutOp
}

/** The rule an edit broke, with the code an agent reads in a refusal. */
enum class ShellLayoutEditRule(val code: String) {
    /** The request is not in the tool's input format. */
    MALFORMED("malformed"),

    /** More ops than one call may carry, or none. */
    OP_COUNT("op_count"),

    /** The lane does not exist or cannot be changed. */
    UNKNOWN_LANE("unknown_lane"),

    /** The lane may not hold this view. */
    VIEW_NOT_ALLOWED("view_not_allowed"),

    /** The view needs an object (a canvas id) and none was given. */
    OBJECT_REQUIRED("object_required"),

    /** The view takes no object and one was given. */
    OBJECT_NOT_ALLOWED("object_not_allowed"),

    /** The object reference is longer than [ShellLayoutCatalog.MAX_OBJECT_LENGTH]. */
    OBJECT_TOO_LONG("object_too_long"),

    /** The page would leave the main lane. */
    PAGE_STAYS_IN_MAIN("page_stays_in_main"),

    /** Settings would leave the side menu. */
    SETTINGS_STAYS("settings_stays"),

    /** The Home button would leave the agent rail. */
    HOME_STAYS("home_stays"),

    /** The slot is already in this lane, or in the other of main and side. */
    DUPLICATE_SLOT("duplicate_slot"),

    /** The lane would hold more slots than it takes. */
    LANE_FULL("lane_full"),

    /** The slot is not in the lane the op names. */
    SLOT_NOT_FOUND("slot_not_found"),

    /** The position is outside the lane. */
    INDEX_OUT_OF_RANGE("index_out_of_range"),
}

/**
 * Why an edit was refused.
 *
 * @property opIndex the op that broke [rule], 0 being the first; null when the result as a whole does.
 * @property rule the rule broken.
 * @property detail what was wrong, in a sentence the agent can act on.
 */
data class ShellLayoutEditError(val opIndex: Int?, val rule: ShellLayoutEditRule, val detail: String)

/**
 * What one lane held before an edit and holds after it.
 *
 * @property lane the lane id.
 * @property before the lane's slots before.
 * @property after the lane's slots after.
 */
data class ShellLayoutLaneChange(val lane: String, val before: List<ModuleSlot>, val after: List<ModuleSlot>)

/** The outcome of [ShellLayoutEdits.apply]. */
sealed interface ShellLayoutEditResult {
    /**
     * Every op was valid; [after] is the layout they make.
     *
     * @property before the layout the ops applied to.
     * @property after the layout they make.
     */
    data class Applied(val before: ShellLayoutConfig, val after: ShellLayoutConfig) : ShellLayoutEditResult {
        /** The lanes whose slots differ, in catalog order. */
        val changes: List<ShellLayoutLaneChange> by lazy { ShellLayoutEdits.changes(before, after) }
    }

    /**
     * At least one op was invalid, so none was applied.
     *
     * @property errors every problem found, in op order.
     */
    data class Rejected(val errors: List<ShellLayoutEditError>) : ShellLayoutEditResult
}
