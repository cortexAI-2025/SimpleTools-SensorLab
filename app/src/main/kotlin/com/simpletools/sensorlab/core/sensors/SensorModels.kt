package com.simpletools.sensorlab.core.sensors

/** One raw reading as delivered by Android (or by a simulated source in automated tests only). */
class RawSample(
    val timestampNs: Long,
    val values: FloatArray,
    val accuracy: Int,
)

/** What this phone reports for one SensorLab module. Never assumed: always read from SensorManager. */
data class SensorInfo(
    val kind: SensorKind,
    val available: Boolean,
    /** Android sensor type actually used (may be the fallback type). */
    val androidType: Int? = null,
    val name: String? = null,
    val vendor: String? = null,
    val resolution: Float? = null,
    val maxRange: Float? = null,
    val powerMilliAmp: Float? = null,
    /** Minimum delay between two events in µs (0 = on-change sensor). */
    val minDelayUs: Int? = null,
    val reportingMode: ReportingMode? = null,
    /** Computation method actually used for this phone (e.g. fallback to gravity). */
    val method: String = kind.method,
    val usesFallback: Boolean = false,
) {
    /** Highest sampling rate the hardware announces, or null if unknown / on-change. */
    val maxRateHz: Float? get() = minDelayUs?.takeIf { it > 0 }?.let { 1_000_000f / it }
}

enum class ReportingMode { CONTINUOUS, ON_CHANGE, ONE_SHOT, SPECIAL }

/** An entry of the raw hardware sensor list (all sensors, including those SensorLab has no module for). */
data class HardwareSensor(
    val name: String,
    val vendor: String,
    val androidType: Int,
    val stringType: String,
)

class SensorUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
