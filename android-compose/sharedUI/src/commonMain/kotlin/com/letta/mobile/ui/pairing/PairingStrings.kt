package com.letta.mobile.ui.pairing

import com.letta.mobile.data.pairing.PairingError

/** Copy for the scan-to-pair surfaces (letta-mobile-dpv7m.2), shared by Android and desktop. */
object PairingStrings {
    const val SCAN_HINT = "Point the camera at the pairing code shown on your other device or the host."
    const val PASTE_LABEL = "Pairing link"
    const val PASTE_HINT = "Paste the meridian://pair link or letta-qr-v1 code"
    const val PASTE_ACTION = "Pair"
    const val SCAN_AGAIN = "Scan again"
    const val OPEN_CHAT = "Open chat"
    const val PAIRED_TITLE = "Paired"
    const val PAIRED_BODY = "This device can now chat with the agents the host shared with it."
    const val ALREADY_PAIRED_BODY = "This device was already paired with this host. The code was not used."

    fun pairing(fingerprint: String) = "Pairing with host $fingerprint…"

    fun hostLine(fingerprint: String) = "Host $fingerprint…"

    fun error(error: PairingError): String = when (error) {
        PairingError.NOT_A_PAIRING_CODE -> "That code isn't a Meridian pairing code."
        PairingError.UNSUPPORTED_VERSION -> "This pairing code is newer than this app. Update the app and try again."
        PairingError.MALFORMED -> "This pairing code is damaged or incomplete. Ask for a new one."
        PairingError.EXPIRED -> "This pairing code has expired. Ask for a new one."
        PairingError.HOST_UNREACHABLE -> "Couldn't reach the host. Check that it is running and reachable from this network."
        PairingError.HOST_MISMATCH -> "The pairing link points at a different host than the code. Ask for a new one."
        PairingError.INVITE_INVALID -> "This pairing code was already used or revoked. Ask for a new one."
        PairingError.DENIED -> "The host refused this device."
        PairingError.RATE_LIMITED -> "Too many attempts. Wait a minute, then try a new code."
        PairingError.SAVE_FAILED -> "Paired, but this device couldn't save the connection. Try again."
    }

    // Inviter side.
    const val INVITE_TITLE = "Pair a device"
    const val INVITE_DEVICE_NAME = "Device name"
    const val INVITE_AGENTS = "Agents this device can use"
    const val INVITE_ALL_AGENTS = "All agents"
    const val INVITE_SHOW_CODE = "Show pairing code"
    const val INVITE_SCAN_PROMPT = "Scan this with the Meridian app or the phone's camera."
    const val INVITE_ONE_TIME = "Works once. Grants chat only — no admin."
    const val INVITE_CANCEL = "Cancel code"
    const val DEVICES_TITLE = "Paired devices"
    const val PENDING_TITLE = "Waiting to be scanned"
    const val REVOKE = "Revoke"
    const val NO_DEVICES = "No devices paired yet."

    fun accepted(name: String) = "$name is paired."

    fun expiresAt(minutesLeft: Long) = if (minutesLeft <= 0) "Expires in under a minute" else "Expires in $minutesLeft min"

    fun deviceScope(agentCount: Int?) = if (agentCount == null) "All agents" else "$agentCount agent${if (agentCount == 1) "" else "s"}"
}

/** Test tags for the pairing surfaces. */
object PairingTags {
    const val STATUS = "pairing_status"
    const val ERROR = "pairing_error"
    const val SCAN_AGAIN = "pairing_scan_again"
    const val OPEN_CHAT = "pairing_open_chat"
    const val PASTE_FIELD = "pairing_paste_field"
    const val PASTE_SUBMIT = "pairing_paste_submit"
    const val INVITE_QR = "pairing_invite_qr"
    const val INVITE_SHOW = "pairing_invite_show"
    const val INVITE_ACCEPTED = "pairing_invite_accepted"
    const val INVITE_ERROR = "pairing_invite_error"

    fun agentOption(id: String) = "pairing_agent_$id"

    fun revokeDevice(nodeId: String) = "pairing_revoke_$nodeId"
}
