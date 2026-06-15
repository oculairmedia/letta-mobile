package com.letta.mobile.runtime.sensors

interface DeviceSensorSnapshotProvider {
    fun snapshot(nowMillis: Long = System.currentTimeMillis()): DeviceSensorSnapshot
}
