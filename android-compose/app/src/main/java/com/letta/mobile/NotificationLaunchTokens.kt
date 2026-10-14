package com.letta.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/**
 * letta-mobile-by4vm.31: MainActivity is exported, so any installed app can send it
 * extras. A notification tap therefore carries only an opaque, single-use token; the
 * chat target it stands for is recorded here (app-private prefs, so a tap still works
 * after process death) when the notification is posted, and redeemed exactly once.
 */
object NotificationLaunchTokens {
    private const val PREFS_NAME = "letta_notification_launch_tokens"
    private const val MAX_TOKENS = 64
    private const val KEY_AGENT_ID = "agentId"
    private const val KEY_CONVERSATION_ID = "conversationId"
    private const val KEY_AGENT_NAME = "agentName"
    private const val KEY_ISSUED_AT = "issuedAt"

    @Synchronized
    fun issue(context: Context, target: NotificationNavigationTarget): String {
        val prefs = prefs(context)
        val token = UUID.randomUUID().toString()
        val value = JSONObject()
            .put(KEY_AGENT_ID, target.agentId)
            .put(KEY_CONVERSATION_ID, target.conversationId)
            .put(KEY_AGENT_NAME, target.agentName)
            .put(KEY_ISSUED_AT, System.nanoTime())
        val evicted = oldestBeyondCapacity(prefs)
        prefs.edit(commit = true) {
            evicted.forEach(::remove)
            putString(token, value.toString())
        }
        return token
    }

    /** Returns the target for [token] and forgets it; unknown or reused tokens yield null. */
    @Synchronized
    fun redeem(context: Context, token: String?): NotificationNavigationTarget? {
        if (token.isNullOrBlank()) return null
        val prefs = prefs(context)
        val raw = prefs.getString(token, null) ?: return null
        prefs.edit(commit = true) { remove(token) }
        return decode(raw)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun oldestBeyondCapacity(prefs: SharedPreferences): List<String> {
        val entries = prefs.all
        val overflow = entries.size - (MAX_TOKENS - 1)
        if (overflow <= 0) return emptyList()
        return entries.entries
            .sortedBy { (_, value) -> issuedAt(value as? String) }
            .take(overflow)
            .map { it.key }
    }

    /** A token whose stored entry is missing or unreadable sorts oldest, so it is evicted first. */
    private fun issuedAt(rawEntry: String?): Long =
        rawEntry?.let { runCatching { JSONObject(it).getLong(KEY_ISSUED_AT) }.getOrNull() } ?: Long.MIN_VALUE

    private fun decode(raw: String): NotificationNavigationTarget? = runCatching {
        val json = JSONObject(raw)
        NotificationNavigationTarget(
            agentId = json.getString(KEY_AGENT_ID),
            conversationId = json.getString(KEY_CONVERSATION_ID),
            agentName = json.optString(KEY_AGENT_NAME).takeIf { it.isNotBlank() },
        )
    }.getOrNull()?.takeIf { it.agentId.isNotBlank() && it.conversationId.isNotBlank() }
}
