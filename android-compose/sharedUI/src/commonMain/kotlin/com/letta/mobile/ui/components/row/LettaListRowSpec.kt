package com.letta.mobile.ui.components.row

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/**
 * What a [LettaListRow] shows: title, optional leading icon, optional trailing label (a time, a
 * count) and an optional second line under the title (a path, a description).
 */
data class LettaListRowSpec(
    val title: String,
    val icon: ImageVector? = null,
    val trailing: String? = null,
    val selected: Boolean = false,
    val subtitle: String? = null,
    /** The title reads semibold (the open conversation). */
    val emphasized: Boolean = false,
    /** The title reads muted (a settled or deleting conversation). */
    val quiet: Boolean = false,
    /** A disabled row draws but takes no click (a conversation being deleted). */
    val enabled: Boolean = true,
    /** Archived rows mute their complete contents without changing click availability. */
    val muted: Boolean = false,
    /** Overrides the title shown by the host tooltip. */
    val tooltip: String = title,
    /** Optional semantic role, for rows presented as buttons. */
    val role: Role? = null,
    /** A single accessible announcement instead of separately reading the text children. */
    val description: String? = null,
    /** Maximum title lines; additional text is ellipsized. */
    val titleLines: Int = 1,
    /** Maximum subtitle lines; additional text is ellipsized. */
    val subtitleLines: Int = 1,
)

/** Test hooks for [LettaListRow]. */
object LettaListRowDefaults {
    /** On every row title block, so a test can tell a row is built on [LettaListRow]. */
    const val TitleTestTag: String = "letta_list_row_title"
}
