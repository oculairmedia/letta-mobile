package com.letta.mobile.data.chat.runtime

/**
 * What "delete chat" really does on a backend. The UI words its confirm dialog and its undo bar
 * from this, so neither promises a recovery the app cannot provide.
 */
enum class ConversationDeleteBehavior {
    /** The conversation is destroyed; there is no undo. */
    Permanent,

    /**
     * The backend has no delete command, so the conversation is only archived (Iroh admin_rpc). It
     * stays listed under the Archived filter; deleting an already archived one changes nothing.
     */
    MovesToArchived,

    /**
     * The conversation is archived and hidden (bundled App Server): it leaves every list,
     * Archived included, and the app's only way back is an immediate undo.
     */
    RemovesFromLists,
}
