package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `shell_layout` tool's JSON receipts: what changed (each touched lane before and after) and a
 * short summary, or each problem as {op_index, rule, detail} with nothing applied.
 */
object ShellLayoutReceipts {
    private const val NO_CHANGE = "No change: the layout already is this."
    private const val NOTHING_APPLIED = "Nothing was applied. Call with action get for the lanes and the views each may hold."

    /** The rules every edit keeps, as the receipt of action get states them. */
    private val rules = listOf(
        "${ShellViews.PAGE.value} stays in lane ${ShellLanes.MAIN}.",
        "${PanelViews.SETTINGS.value} stays in lane ${ShellLanes.PANEL}.",
        "${RailViews.HOME.value} stays in lane ${ShellLanes.RAIL}.",
        "The transcript and composer stay in lane ${ShellLanes.CHAT}.",
        "A slot appears once, and in only one of ${ShellLanes.MAIN} and ${ShellLanes.SIDE}.",
        "Each lane holds only its listed views, at most max_slots of them.",
    )

    /** The current lanes, the catalog and how many changes [undoSteps] undo can step back. */
    fun current(config: ShellLayoutConfig, undoSteps: Int): ExternalToolResult = success(ShellLayoutAction.GET) {
        putJsonObject("lanes") {
            ShellLayoutCatalog.lanes.forEach { put(it.lane, slots(ShellLayoutEdits.slots(config, it.lane))) }
        }
        put("undo_steps", undoSteps)
        put("rules", JsonArray(rules.map(::JsonPrimitive)))
        putJsonArray("catalog") { ShellLayoutCatalog.lanes.forEach { add(lane(it)) } }
    }

    /** A valid edit that was not applied: a dry run, or one that changes nothing. */
    fun checked(result: ShellLayoutEditResult.Applied, dryRun: Boolean): ExternalToolResult = success(ShellLayoutAction.APPLY) {
        put("dry_run", dryRun)
        put("applied", false)
        put("summary", if (result.changes.isEmpty()) NO_CHANGE else summary(result.changes))
        put("changes", changes(result.changes))
    }

    /** What a change through the port came to. */
    fun committed(action: ShellLayoutAction, commit: ShellLayoutCommit): ExternalToolResult = when (commit) {
        is ShellLayoutCommit.Done -> success(action) {
            put("applied", true)
            put("summary", commit.change.summary)
            put("changes", changes(ShellLayoutEdits.changes(commit.change.before, commit.change.after)))
            put("visible_to_user", true)
            put("user_can_undo", commit.change.undoable)
        }
        ShellLayoutCommit.Unchanged -> success(action) {
            put("applied", false)
            put("summary", if (action == ShellLayoutAction.UNDO) "No earlier layout to go back to." else NO_CHANGE)
        }
        ShellLayoutCommit.NoShell -> noShell(action)
        ShellLayoutCommit.Stale -> failure(action, "The layout changed while this edit was made; call get and try again.")
    }

    /** No shell on screen: there is no layout to read or change. */
    fun noShell(action: ShellLayoutAction): ExternalToolResult =
        failure(action, "No app shell is on screen, so there is no layout to read or change.")

    /** An input or edit that broke [errors]: nothing applied. */
    fun refused(action: ShellLayoutAction?, errors: List<ShellLayoutEditError>): ExternalToolResult = ExternalToolResult.Error(
        buildJsonObject {
            put("ok", false)
            action?.let { put("action", it.wire) }
            putJsonArray("errors") {
                errors.forEach { error ->
                    add(
                        buildJsonObject {
                            error.opIndex?.let { put("op_index", it) }
                            put("rule", error.rule.code)
                            put("detail", error.detail)
                        },
                    )
                }
            }
            put("hint", NOTHING_APPLIED)
        }.toString(),
    )

    /**
     * What [changes] did, a lane at a time: "Home: reordered; Side menu: removed New chat".
     */
    fun summary(changes: List<ShellLayoutLaneChange>): String = changes.joinToString("; ") { change ->
        val label = ShellLayoutCatalog.lane(change.lane)?.label ?: change.lane
        val removed = change.before - change.after.toSet()
        val added = change.after - change.before.toSet()
        val kept = change.before.filter { it in change.after }
        val parts = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "added ${names(change.lane, it)}" },
            removed.takeIf { it.isNotEmpty() }?.let { "removed ${names(change.lane, it)}" },
            "reordered".takeIf { kept != change.after.filter { it in change.before } },
        )
        "$label: ${parts.joinToString(", ")}"
    }

    private fun names(lane: String, slots: List<ModuleSlot>): String = slots.joinToString { slot ->
        val label = ShellLayoutCatalog.label(lane, slot)
        slot.objectRef?.let { "$label $it" } ?: label
    }

    private fun success(action: ShellLayoutAction, body: JsonObjectBuilder.() -> Unit): ExternalToolResult =
        ExternalToolResult.Success(
            buildJsonObject {
                put("ok", true)
                put("action", action.wire)
                body()
            }.toString(),
        )

    private fun failure(action: ShellLayoutAction, detail: String): ExternalToolResult = ExternalToolResult.Error(
        buildJsonObject {
            put("ok", false)
            put("action", action.wire)
            put("error", detail)
        }.toString(),
    )

    private fun changes(changes: List<ShellLayoutLaneChange>): JsonArray = buildJsonArray {
        changes.forEach { change ->
            add(
                buildJsonObject {
                    put("lane", change.lane)
                    put("before", slots(change.before))
                    put("after", slots(change.after))
                },
            )
        }
    }
}

private fun slots(slots: List<ModuleSlot>): JsonArray = buildJsonArray { slots.forEach { add(slot(it)) } }

private fun slot(slot: ModuleSlot): JsonObject = buildJsonObject {
    put("view", slot.view.value)
    slot.objectRef?.let { put("object", it) }
}

private fun lane(spec: ShellLayoutLaneSpec): JsonObject = buildJsonObject {
    put("lane", spec.lane)
    put("description", spec.description)
    put("max_slots", spec.maxSlots)
    putJsonArray("views") {
        spec.views.forEach { view ->
            add(
                buildJsonObject {
                    put("view", view.view.value)
                    put("description", view.description)
                    if (view.needsObject) put("needs_object", true)
                },
            )
        }
    }
}
