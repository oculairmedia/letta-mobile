package com.letta.mobile.data.pairing

/**
 * What a paired device may do, as the host records it,
 * reduced to the three levels the device list shows as a chip.
 */
enum class DeviceAccess {
    /** chat.read / chat.send / conversation.manage only — the least-privilege default. */
    CHAT_ONLY,

    /** Chat plus settings-level grants (memory, tools, skills, schedules, projects). */
    CHAT_AND_SETTINGS,

    /** admin.full. */
    FULL_ADMIN,
    ;

    companion object {
        private const val ADMIN_FULL = "admin.full"
        private val SETTINGS_CAPABILITIES = setOf(
            "memory.write",
            "tools.manage",
            "skills.manage",
            "schedule.manage",
            "projects.manage",
        )

        fun of(capabilities: Set<String>): DeviceAccess = when {
            ADMIN_FULL in capabilities -> FULL_ADMIN
            capabilities.any { it in SETTINGS_CAPABILITIES } -> CHAT_AND_SETTINGS
            else -> CHAT_ONLY
        }
    }
}

val PairedDevice.access: DeviceAccess get() = DeviceAccess.of(capabilities)

/**
 * The paired-device list split for display: this device first, then
 * the others, most recently active first.
 */
data class PairedDeviceGroups(val thisDevice: PairedDevice?, val others: List<PairedDevice>) {
    /** "Revoke all other devices" only makes sense when this device is known and others exist. */
    val canRevokeAllOthers: Boolean get() = thisDevice != null && others.size > 1

    /** True when some device is limited to named agents (the host filters their listings). */
    val anyAgentScoped: Boolean get() = (listOfNotNull(thisDevice) + others).any { it.agentIds != null }

    companion object {
        fun of(devices: List<PairedDevice>, selfNodeId: String?): PairedDeviceGroups {
            val self = selfNodeId?.let { id -> devices.firstOrNull { it.nodeId.equals(id, ignoreCase = true) } }
            val others = devices.filterNot { it === self }.sortedByDescending { it.lastSeenAtMs ?: it.pairedAtMs }
            return PairedDeviceGroups(self, others)
        }
    }
}
