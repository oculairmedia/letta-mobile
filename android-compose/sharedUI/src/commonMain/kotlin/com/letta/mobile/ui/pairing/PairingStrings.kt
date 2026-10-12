package com.letta.mobile.ui.pairing

import com.letta.mobile.data.pairing.DeviceAccess
import com.letta.mobile.data.pairing.PairingError

/** Copy for the scan-to-pair surfaces (letta-mobile-dpv7m.2, UX pass dpv7m.5), shared by Android and desktop. */
object PairingStrings {
    // Scanner (joining device).
    const val SCAN_TITLE = "Scan to connect"
    const val SCAN_HINT = "Point the camera at the pairing code on your other device."
    const val CAMERA_READY = "Camera ready. Point it at the pairing code."
    const val PASTE_LINK = "Paste link"
    const val SCAN_WITH_CAMERA = "Scan with camera"
    const val PASTE_ONLY_HINT = "Paste the pairing link from the other device's \"Pair a device\" screen or the host's pair command."
    const val PASTE_LABEL = "Pairing link"
    const val PASTE_HINT = "meridian://pair?…"
    const val PASTE_ACTION = "Continue"
    const val TORCH_ON = "Turn flashlight off"
    const val TORCH_OFF = "Turn flashlight on"
    const val CAMERA_RATIONALE_TITLE = "Allow camera to scan"
    const val CAMERA_RATIONALE_BODY = "Meridian uses the camera only to read the pairing code. Nothing is recorded or uploaded."
    const val CAMERA_ALLOW = "Allow camera"
    const val CAMERA_DENIED_TITLE = "Camera access is off"
    const val CAMERA_DENIED_BODY = "Turn on camera access in settings to scan, or paste the pairing link instead."
    const val OPEN_SETTINGS = "Open settings"
    const val PASTE_INSTEAD = "Paste link instead"

    // Trust prompt.
    fun trustTitle(hostShortId: String) = "Connect to $hostShortId?"

    const val TRUST_BODY = "Only connect if you started pairing and this ID matches the one on the other screen."

    fun trustHostLine(hostShortId: String) = "Host ID: $hostShortId"

    fun trustExpiresLine(remainingMs: Long) = "Code expires in ${countdown(remainingMs)}"

    const val TRUST_ACCESS = "Access: what the inviter chose for this code"
    const val CONNECT = "Connect"
    const val CANCEL = "Cancel"

    // Verifying.
    const val VERIFYING_TITLE = "Verifying…"
    const val VERIFYING_STEP_CONTACT = "Contacting host…"
    const val VERIFYING_STEP_SECURE = "Securing connection…"
    const val VERIFYING_KEEP_OPEN = "Keep Meridian open on both devices."

    // Success.
    fun pairedTitle(hostShortId: String) = "Paired with $hostShortId"

    fun pairedDeviceLine(deviceName: String) = "This device is listed on the host as \"$deviceName\"."

    const val PAIRED_BODY = "This device can now chat with the agents the host shared with it."
    const val ALREADY_PAIRED_BODY = "This device was already paired with this host. The code was not used."
    const val OPEN_CHAT = "Open chat"

    // Errors: one distinct line, one action.
    const val SCAN_AGAIN = "Scan again"
    const val RETRY = "Retry"

    fun error(error: PairingError): String = when (error) {
        PairingError.NOT_A_PAIRING_CODE -> "That is not a Meridian pairing code."
        PairingError.UNSUPPORTED_VERSION -> "This pairing code is newer than this app. Update the app and try again."
        PairingError.MALFORMED -> "This pairing code is damaged or incomplete. Ask for a new one."
        PairingError.EXPIRED -> "This code expired. Ask for a new one."
        PairingError.HOST_UNREACHABLE -> "Can't reach the host. Check it is online."
        PairingError.HOST_MISMATCH -> "The pairing link points at a different host than the code. Ask for a new one."
        PairingError.INVITE_INVALID -> "This code was already used or cancelled. Ask for a new one."
        PairingError.DENIED -> "The host refused this device."
        PairingError.RATE_LIMITED -> "Too many attempts. Wait a minute, then try a new code."
        PairingError.SAVE_FAILED -> "Paired, but this device couldn't save the connection. Nothing was saved; try again."
    }

    // Inviter: "Pair a device".
    const val INVITE_TITLE = "Pair a device"
    const val INVITE_DEVICE_NAME = "Device name (optional)"
    const val INVITE_ACCESS = "Which agents can it use?"
    const val INVITE_ALL_AGENTS = "All agents"
    const val INVITE_CHOOSE_AGENTS = "Choose agents"
    const val INVITE_CHOOSE_AT_LEAST_ONE = "Choose at least one agent."
    const val INVITE_SETTINGS_TOGGLE = "Allow settings changes"
    const val INVITE_SETTINGS_BODY = "Lets the device change memory, tools, skills and schedules. Off: chat only."
    const val INVITE_SHOW_CODE = "Show pairing code"
    const val INVITE_STEP_1 = "1. Open Meridian on your phone."
    const val INVITE_STEP_2 = "2. Settings > Scan to connect."
    const val INVITE_STEP_3 = "3. Point it at this code."
    const val SHOW_LINK = "Show code instead"
    const val SHOW_QR = "Show QR code"
    const val COPY_LINK = "Copy link"
    const val LINK_COPIED = "Link copied"
    const val SHARE_LINK = "Share"
    const val LINK_SECRET_NOTE = "Works once. Anyone with this link can pair until it is used or expires."
    const val NEW_CODE = "New code"
    const val CANCEL_CODE = "Cancel code"
    const val CHANGE_ACCESS = "Change access"
    const val CODE_EXPIRED = "Code expired"
    const val CODE_EXPIRED_BODY = "This code can no longer be used. Make a new one when the other device is ready."
    const val CODE_CANCELLED = "Code cancelled"
    const val CODE_CANCELLED_BODY = "This code can no longer be used."
    const val EXPIRING_SOON = "Expiring soon"
    const val DONE = "Done"

    fun countdown(remainingMs: Long) = com.letta.mobile.data.pairing.InviteCountdown.format(remainingMs)

    fun expiresIn(remainingMs: Long) = "Expires in ${countdown(remainingMs)}"

    /** The QR's accessible description, coarse on purpose (no per-second chatter). */
    fun qrDescription(remainingMs: Long): String {
        val minutes = (remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE
        return if (minutes <= 1) "Pairing code, expires in under a minute" else "Pairing code, expires in $minutes minutes"
    }

    fun countdownAnnouncement(thresholdSeconds: Long) =
        if (thresholdSeconds >= SECONDS_PER_MINUTE) "Pairing code expires in 1 minute" else "Pairing code expires in $thresholdSeconds seconds"

    fun accessSummary(agentNames: List<String>?, allowSettings: Boolean, ttlMs: Long): String {
        val agents = agentNames?.joinToString(", ")?.ifBlank { "no agents" } ?: INVITE_ALL_AGENTS
        val level = if (allowSettings) "chat + settings" else "chat only"
        return "Access: $agents · $level · expires in ${ttlMs / MS_PER_MINUTE} min"
    }

    fun accepted(name: String) = "${name.ifBlank { "The device" }} is paired."

    // Paired devices.
    const val DEVICES_TITLE = "Paired devices"
    const val THIS_DEVICE = "THIS DEVICE"
    const val OTHER_DEVICES = "OTHER DEVICES"
    const val ACTIVE_NOW = "Active now"
    const val NOT_CONNECTED_YET = "Not connected yet"
    const val NO_DEVICES = "No paired devices yet."
    const val REVOKE = "Revoke"
    const val REVOKE_ACCESS = "Revoke access"
    const val REVOKING = "Revoking…"
    const val REVOKE_ALL_OTHERS = "Revoke all other devices"
    const val REVOKE_ALL_OTHERS_BODY = "Every device except this one loses access immediately."
    const val PENDING_TITLE = "Pending invites"
    const val CANCELLING = "Cancelling…"

    fun revokeConfirm(name: String) = "$name will lose access immediately and any open chat ends."

    /**
     * Honest scope note (dpv7m.4 is open): the host filters agent LISTINGS for an
     * agent-limited device, but does not yet block opening a conversation by id.
     */
    const val AGENT_SCOPE_NOTE =
        "Agent limits hide other agents from a device's lists. They are not yet a hard access boundary: " +
            "a device that already knows a conversation link can still open it."

    fun agentScope(agentIds: Set<String>?) = when {
        agentIds == null -> "All agents"
        agentIds.size == 1 -> "Lists 1 agent"
        else -> "Lists ${agentIds.size} agents"
    }

    fun access(access: DeviceAccess) = when (access) {
        DeviceAccess.CHAT_ONLY -> "Chat only"
        DeviceAccess.CHAT_AND_SETTINGS -> "Chat + settings"
        DeviceAccess.FULL_ADMIN -> "Full admin"
    }

    fun lastActive(lastSeenAtMs: Long?, nowMs: Long): String {
        lastSeenAtMs ?: return NOT_CONNECTED_YET
        val minutes = ((nowMs - lastSeenAtMs).coerceAtLeast(0)) / MS_PER_MINUTE
        return when {
            minutes < 1 -> "Active just now"
            minutes < MINUTES_PER_HOUR -> "Last active $minutes min ago"
            minutes < MINUTES_PER_DAY -> "Last active ${minutes / MINUTES_PER_HOUR} h ago"
            else -> "Last active ${minutes / MINUTES_PER_DAY} d ago"
        }
    }

    // Install-and-pair card (letta-mobile-dpv7m.3).
    const val JOIN_TITLE = "Connect to a host with a pairing link"
    const val INSTALL_TITLE = "Get the Meridian app on the phone"
    const val INSTALL_BODY = "Scan with the phone's camera to download the Android app, then scan the pairing code below."

    /** The latest Android release; the APK is attached to every GitHub release. */
    const val APP_DOWNLOAD_URL = "https://github.com/oculairmedia/letta-mobile/releases/latest"

    private const val MS_PER_MINUTE = 60_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 1_440L
}

/** Test tags for the pairing surfaces. */
object PairingTags {
    const val STATUS = "pairing_status"
    const val ERROR = "pairing_error"
    const val SCAN_AGAIN = "pairing_scan_again"
    const val RETRY = "pairing_retry"
    const val OPEN_CHAT = "pairing_open_chat"
    const val TRUST_PROMPT = "pairing_trust_prompt"
    const val TRUST_CONNECT = "pairing_trust_connect"
    const val TRUST_CANCEL = "pairing_trust_cancel"
    const val VERIFYING = "pairing_verifying"
    const val VERIFY_CANCEL = "pairing_verify_cancel"
    const val PASTE_FIELD = "pairing_paste_field"
    const val PASTE_SUBMIT = "pairing_paste_submit"
    const val PASTE_TOGGLE = "pairing_paste_toggle"
    const val CAMERA_AREA = "pairing_camera_area"
    const val CAMERA_RATIONALE = "pairing_camera_rationale"
    const val CAMERA_ALLOW = "pairing_camera_allow"
    const val CAMERA_DENIED = "pairing_camera_denied"
    const val OPEN_SETTINGS = "pairing_open_settings"
    const val TORCH = "pairing_torch"
    const val INVITE_QR = "pairing_invite_qr"
    const val INVITE_LINK = "pairing_invite_link"
    const val INVITE_SHOW = "pairing_invite_show"
    const val INVITE_TOGGLE_LINK = "pairing_invite_toggle_link"
    const val INVITE_COPY = "pairing_invite_copy"
    const val INVITE_SHARE = "pairing_invite_share"
    const val INVITE_COUNTDOWN = "pairing_invite_countdown"
    const val INVITE_NEW_CODE = "pairing_invite_new_code"
    const val INVITE_CANCEL = "pairing_invite_cancel"
    const val INVITE_EXPIRED = "pairing_invite_expired"
    const val INVITE_CANCELLED = "pairing_invite_cancelled"
    const val INVITE_ACCEPTED = "pairing_invite_accepted"
    const val INVITE_ERROR = "pairing_invite_error"
    const val INVITE_ALL_AGENTS = "pairing_invite_all_agents"
    const val INVITE_CHOOSE_AGENTS = "pairing_invite_choose_agents"
    const val INVITE_SETTINGS = "pairing_invite_settings"
    const val THIS_DEVICE = "pairing_this_device"
    const val REVOKE_ALL_OTHERS = "pairing_revoke_all_others"
    const val REVOKE_ALL_CONFIRM = "pairing_revoke_all_confirm"
    const val AGENT_SCOPE_NOTE = "pairing_agent_scope_note"

    fun agentOption(id: String) = "pairing_agent_$id"

    fun revokeDevice(nodeId: String) = "pairing_revoke_$nodeId"

    fun revokeConfirm(nodeId: String) = "pairing_revoke_confirm_$nodeId"

    fun revoking(nodeId: String) = "pairing_revoking_$nodeId"

    fun deviceRow(nodeId: String) = "pairing_device_$nodeId"

    fun cancelInvite(inviteId: String) = "pairing_cancel_invite_$inviteId"
}
