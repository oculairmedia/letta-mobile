package com.letta.mobile.runtime.sensors

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import java.io.File
import kotlin.math.roundToInt

class AndroidDeviceSensorSnapshotProvider(
    private val context: Context,
) : DeviceSensorSnapshotProvider {
    override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = DeviceSensorSnapshot(
        capturedAtMillis = nowMillis,
        battery = readBattery(),
        thermal = readThermal(),
        memory = readMemory(),
        storage = readStorage(),
        network = readNetwork(),
        display = readDisplay(),
        sensors = readSensors(),
    )

    private fun readBattery(): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        val pct = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100f).roundToInt() else null
        return BatterySnapshot(
            levelPercent = pct,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            chargePlug = plugName(plugged),
            temperatureCelsius = if (tempTenthsC != Int.MIN_VALUE) tempTenthsC / 10f else null,
            voltageMillivolts = if (voltage != Int.MIN_VALUE) voltage else null,
        )
    }

    private fun readThermal(): ThermalSnapshot? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThermalSnapshot(pm.currentThermalStatus.toThermalStatusName())
        } else {
            ThermalSnapshot("unsupported")
        }
    }

    private fun readMemory(): MemorySnapshot? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemorySnapshot(
            availableBytes = info.availMem,
            totalBytes = info.totalMem,
            lowMemory = info.lowMemory,
        )
    }

    private fun readStorage(): StorageSnapshot? {
        val dir: File = Environment.getDataDirectory() ?: return null
        return StorageSnapshot(
            availableBytes = dir.usableSpace,
            totalBytes = dir.totalSpace,
        )
    }

    private fun readNetwork(): NetworkSnapshot? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return NetworkSnapshot(isConnected = false)
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkSnapshot(isConnected = false)
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("wifi_aware")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) add("lowpan")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("usb")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return NetworkSnapshot(
            isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transportTypes = transports,
            isMetered = cm.isActiveNetworkMetered,
        )
    }

    @Suppress("DEPRECATION")
    private fun readDisplay(): DisplaySnapshot? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: wm.defaultDisplay.rotation
        } else {
            wm.defaultDisplay.rotation
        }
        val configOrientation = context.resources.configuration.orientation
        DisplaySnapshot(
            rotation = when (rotation) {
                Surface.ROTATION_0 -> "0"
                Surface.ROTATION_90 -> "90"
                Surface.ROTATION_180 -> "180"
                Surface.ROTATION_270 -> "270"
                else -> "unknown"
            },
            orientation = when (configOrientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> "unknown"
            },
        )
    }.getOrNull()

    private fun readSensors(): List<SensorDescriptor> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return sm.getSensorList(Sensor.TYPE_ALL)
            .sortedWith(compareBy({ it.type }, { it.stringType }, { it.name }))
            .map { sensor ->
                SensorDescriptor(
                    name = sensor.name.orEmpty(),
                    vendor = sensor.vendor.orEmpty(),
                    type = sensor.type,
                    stringType = sensor.stringType.orEmpty(),
                    reportingMode = sensor.reportingModeName(),
                    isWakeUpSensor = sensor.isWakeUpSensor,
                    maxRange = sensor.maximumRange,
                    resolution = sensor.resolution,
                    powerMilliAmps = sensor.power,
                    minDelayMicros = sensor.minDelay,
                    maxDelayMicros = sensor.maxDelay,
                )
            }
    }

    private fun plugName(value: Int): String? = when (value) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> null
    }

    private fun Int.toThermalStatusName(): String = when (this) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown:$this"
    }

    private fun Sensor.reportingModeName(): String = when (reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special_trigger"
        else -> "unknown:$reportingMode"
    }
}
