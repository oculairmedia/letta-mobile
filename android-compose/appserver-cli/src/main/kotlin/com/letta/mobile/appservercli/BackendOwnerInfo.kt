package com.letta.mobile.appservercli

/**
 * Canonical ownership metadata for a Letta local-backend root
 * (letta-mobile-lgns8.14).
 *
 * The OS [LocalBackendLock] guarantees exclusivity between *cooperating*
 * processes, but a crash can leave a stale lock file and a rogue/legacy process
 * that never took the lock can still be writing the same root. This sidecar
 * record (persisted next to the lock as `.owner.json`) makes the *current owner*
 * observable so a preflight can:
 *  - refuse to start when a live competing writer exists (fence),
 *  - detect that a recorded owner is stale (dead PID or PID reused) and reclaim
 *    ownership safely,
 *  - identify the restart authority (systemd unit) for one-owner topology.
 *
 * [startTimeMs] is the process start time; combined with [pid] it defends
 * against PID reuse — a live process with the recorded PID but a different start
 * time is NOT the recorded owner.
 */
data class BackendOwnerInfo(
    val pid: Long,
    val startTimeMs: Long,
    val backendDir: String,
    val unit: String?,
    val acquiredAtIso: String,
) {
    /** Minimal, dependency-free JSON serialization (no kotlinx.serialization here). */
    fun toJson(): String = buildString {
        append('{')
        append("\"pid\":").append(pid).append(',')
        append("\"startTimeMs\":").append(startTimeMs).append(',')
        append("\"backendDir\":").append(quote(backendDir)).append(',')
        append("\"unit\":").append(if (unit == null) "null" else quote(unit)).append(',')
        append("\"acquiredAtIso\":").append(quote(acquiredAtIso))
        append('}')
    }

    companion object {
        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }

        /** Tolerant parse of the sidecar; returns null if malformed (treated as stale). */
        fun fromJson(json: String): BackendOwnerInfo? {
            return try {
                val pid = longField(json, "pid") ?: return null
                val startTimeMs = longField(json, "startTimeMs") ?: return null
                val backendDir = stringField(json, "backendDir") ?: return null
                val unit = stringField(json, "unit")
                val acquiredAtIso = stringField(json, "acquiredAtIso") ?: return null
                BackendOwnerInfo(pid, startTimeMs, backendDir, unit, acquiredAtIso)
            } catch (_: Exception) {
                null
            }
        }

        private fun longField(json: String, name: String): Long? {
            val re = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(-?\\d+)")
            return re.find(json)?.groupValues?.get(1)?.toLongOrNull()
        }

        private fun stringField(json: String, name: String): String? {
            val re = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(null|\"((?:[^\"\\\\]|\\\\.)*)\")")
            val m = re.find(json) ?: return null
            if (m.groupValues[1] == "null") return null
            return m.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
    }
}
