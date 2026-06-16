package com.letta.mobile.runtime.sensors

interface DeviceSensorSampler {
    fun sample(
        descriptor: SensorDescriptor,
        sampleCount: Int,
        timeoutMs: Long,
    ): SensorSampleResponse
}

object NoopDeviceSensorSampler : DeviceSensorSampler {
    override fun sample(
        descriptor: SensorDescriptor,
        sampleCount: Int,
        timeoutMs: Long,
    ): SensorSampleResponse = SensorSampleResponse(
        status = "missing_bridge",
        sensor = descriptor,
        requestedSamples = sampleCount,
        timeoutMs = timeoutMs,
        error = "Live SensorManager sampling is not available in this runtime.",
    )
}
