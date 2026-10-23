package com.letta.mobile.data.chat.tools.steps

import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * What a kind of step adds up to when several are grouped ("Explored files, ran commands"):
 * reading and searching are both exploring, writing and editing both edit files.
 */
enum class ToolActivity {
    Explored,
    Ran,
    Edited,
    Fetched,
    Delegated,
    Remembered,
    Drew,
    Called,
}

/** The activity a step of this verb adds to its group. */
val ToolVerb.activity: ToolActivity
    get() = when (this) {
        ToolVerb.Read, ToolVerb.Searched -> ToolActivity.Explored
        ToolVerb.Ran, ToolVerb.Stopped, ToolVerb.Watching -> ToolActivity.Ran
        ToolVerb.Wrote, ToolVerb.Edited -> ToolActivity.Edited
        ToolVerb.Fetched -> ToolActivity.Fetched
        ToolVerb.Delegated -> ToolActivity.Delegated
        ToolVerb.Called, ToolVerb.Updated, ToolVerb.Planned, ToolVerb.Used -> ToolActivity.Called
        ToolVerb.EditedMemory, ToolVerb.AddedToMemory, ToolVerb.RewroteMemory, ToolVerb.WroteMemfs -> ToolActivity.Remembered
        ToolVerb.ComposedCanvas, ToolVerb.EditedCanvas, ToolVerb.ReadCanvas, ToolVerb.CreatedCanvas -> ToolActivity.Drew
    }
