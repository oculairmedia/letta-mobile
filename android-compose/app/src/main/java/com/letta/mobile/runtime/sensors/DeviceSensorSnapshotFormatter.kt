package com.letta.mobile.runtime.sensors

import java.util.Locale
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object DeviceSensorSnapshotFormatter {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun toJson(snapshot: DeviceSensorSnapshot): String = json.encodeToString(snapshot)

    fun toJsonString(value: String): String = json.encodeToString(String.serializer(), value)

    fun toCompactString(snapshot: DeviceSensorSnapshot): String {
        val parts = mutableListOf<String>()
        snapshot.battery?.let { battery ->
            val level = battery.levelPercent?.let { "$it%" } ?: "unknown"
            val charge = if (battery.isCharging) "⚡" else ""
            parts += "🔋$level$charge"
            battery.temperatureCelsius?.let { parts += "🌡️${formatOneDecimal(it)}°C" }
        }
        snapshot.thermal?.let { parts += "thermal=${it.status}" }
        snapshot.memory?.let { parts += "🧠${it.usedPercent}%" }
        snapshot.storage?.let { parts += "💿${it.usedPercent}%" }
        snapshot.network?.let { network ->
            val transports = if (network.transportTypes.isEmpty()) "none" else network.transportTypes.joinToString("+")
            parts += if (network.isConnected) "📶$transports" else "📵offline"
        }
        snapshot.display?.let { parts += "↻${it.orientation}/${it.rotation}" }
        parts += "sensors=${snapshot.sensorCount}"
        if (snapshot.gatedCapabilities.isNotEmpty()) {
            val enabled = snapshot.gatedCapabilities.count { it.status == DeviceSensorGatedStatus.Granted }
            parts += "gated=$enabled/${snapshot.gatedCapabilities.size}"
        }
        return parts.joinToString(" | ")
    }

    private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
}
