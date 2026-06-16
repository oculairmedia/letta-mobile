package com.letta.mobile.runtime.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceSensorSampler @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceSensorSampler {
    override fun sample(
        descriptor: SensorDescriptor,
        sampleCount: Int,
        timeoutMs: Long,
    ): SensorSampleResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return SensorSampleResponse(
                status = "unsupported",
                sensor = descriptor,
                requestedSamples = sampleCount,
                timeoutMs = timeoutMs,
                error = "SensorManager is not available.",
            )
        val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { sensor ->
            sensor.type == descriptor.type &&
                sensor.stringType.orEmpty() == descriptor.stringType &&
                sensor.name.orEmpty() == descriptor.name
        } ?: sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { sensor ->
            sensor.type == descriptor.type && sensor.stringType.orEmpty() == descriptor.stringType
        } ?: return SensorSampleResponse(
            status = "no_match",
            sensor = descriptor,
            requestedSamples = sampleCount,
            timeoutMs = timeoutMs,
            error = "Matched catalog sensor is no longer available.",
        )

        val boundedCount = sampleCount.coerceIn(1, MAX_SAMPLE_COUNT)
        val boundedTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val samples = mutableListOf<SensorSample>()
        val latch = CountDownLatch(boundedCount)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(samples) {
                    if (samples.size >= boundedCount) return
                    samples += SensorSample(
                        timestampNanos = event.timestamp,
                        accuracy = event.accuracy,
                        values = event.values.toList(),
                    )
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        return try {
            val registered = sensorManager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
            if (!registered) {
                return SensorSampleResponse(
                    status = "unsupported",
                    sensor = descriptor,
                    requestedSamples = boundedCount,
                    timeoutMs = boundedTimeout,
                    error = "Android refused to register a listener for this sensor.",
                )
            }
            latch.await(boundedTimeout, TimeUnit.MILLISECONDS)
            val captured = synchronized(samples) { samples.toList() }
            if (captured.isEmpty()) {
                SensorSampleResponse(
                    status = "timeout_no_sample",
                    sensor = descriptor,
                    requestedSamples = boundedCount,
                    timeoutMs = boundedTimeout,
                    error = "No sample arrived before timeout.",
                )
            } else {
                SensorSampleResponse(
                    status = "available",
                    sensor = descriptor,
                    samples = captured,
                    requestedSamples = boundedCount,
                    timeoutMs = boundedTimeout,
                )
            }
        } catch (error: SecurityException) {
            SensorSampleResponse(
                status = "blocked_by_android_policy",
                sensor = descriptor,
                requestedSamples = boundedCount,
                timeoutMs = boundedTimeout,
                error = error.message ?: "Android blocked sensor sampling.",
            )
        } finally {
            runCatching { sensorManager.unregisterListener(listener) }
        }
    }

    private companion object {
        const val MAX_SAMPLE_COUNT = 16
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 10_000L
    }
}
