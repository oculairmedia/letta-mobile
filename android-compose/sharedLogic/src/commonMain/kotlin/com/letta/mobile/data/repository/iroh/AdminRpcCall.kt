package com.letta.mobile.data.repository.iroh

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Typed admin_rpc method name (e.g. `project.list`). */
@JvmInline
@Serializable
value class AdminRpcMethod(val value: String) {
    override fun toString(): String = value
}

/** Typed admin_rpc HTTP-equivalent path. */
@JvmInline
@Serializable
value class AdminRpcPath(val value: String) {
    override fun toString(): String = value
}

/** Typed admin_rpc JSON body payload. */
@JvmInline
@Serializable
value class AdminRpcBody(val value: String) {
    override fun toString(): String = value
}

/** Typed schedule identifier. */
@JvmInline
@Serializable
value class ScheduleId(val value: String) {
    override fun toString(): String = value
}

/** Typed skill name. */
@JvmInline
@Serializable
value class SkillName(val value: String) {
    override fun toString(): String = value
}

/** Typed device label for Iroh chat gateway telemetry and logging. */
@JvmInline
@Serializable
value class IrohDeviceLabel(val value: String) {
    override fun toString(): String = value
}

/** Typed turn identifier within an Iroh frame stream. */
@JvmInline
@Serializable
value class IrohTurnId(val value: String) {
    override fun toString(): String = value
}

/** Typed failure reason string for turn diagnostics. */
@JvmInline
@Serializable
value class IrohFailureReason(val value: String) {
    override fun toString(): String = value
}

/** Typed failure kind string for turn diagnostics. */
@JvmInline
@Serializable
value class IrohFailureKind(val value: String) {
    override fun toString(): String = value
}

/** Typed failure detail string for turn errors. */
@JvmInline
@Serializable
value class IrohFailureDetail(val value: String) {
    override fun toString(): String = value
}

/**
 * One `admin_rpc` invocation: the registry method plus the HTTP-equivalent
 * path (and optional JSON body) the server-side handler expects — the same
 * conventions the Android IrohAdminRpc*Source classes use.
 */
data class AdminRpcCall(
    val method: String,
    val path: String,
    val body: String? = null,
) {
    companion object {
        fun of(method: AdminRpcMethod, path: AdminRpcPath, body: AdminRpcBody? = null): AdminRpcCall =
            AdminRpcCall(method = method.value, path = path.value, body = body?.value)

        fun of(method: AdminRpcMethod, path: AdminRpcPath, body: String?): AdminRpcCall =
            AdminRpcCall(method = method.value, path = path.value, body = body)
    }
}
