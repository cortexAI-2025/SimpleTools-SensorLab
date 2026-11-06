package com.simpletools.sensorlab.core.measurement

import com.simpletools.sensorlab.core.sensors.RawSample
import com.simpletools.sensorlab.core.sensors.SensorInfo
import com.simpletools.sensorlab.core.sensors.SensorKind

/**
 * Turns raw Android values into the channels of a [SensorKind].
 *
 *  - plain sensors: values are passed through untouched (no invented data);
 *  - INCLINOMETER: pitch / roll / tilt (degrees) from the rotation vector, or from the
 *    low-passed gravity direction when the phone has no rotation-vector sensor;
 *  - VIBRATION: accelerometer through a documented, configurable high-pass filter.
 */
class KindTransform(
    val kind: SensorKind,
    private val usesGravityFallback: Boolean = false,
    vibrationCutoffHz: Double = DEFAULT_VIBRATION_CUTOFF_HZ,
) {
    constructor(info: SensorInfo, vibrationCutoffHz: Double = DEFAULT_VIBRATION_CUTOFF_HZ) :
        this(info.kind, info.usesFallback, vibrationCutoffHz)

    private val highPass = HighPassFilter(3, vibrationCutoffHz)
    private val gravitySmoother = ExponentialSmoother(3, GRAVITY_SMOOTHING_S)

    /** Reference "up" vector captured by the user's calibration (null = true vertical). */
    @Volatile var reference: FloatArray? = null

    /** Latest absolute up vector, used to capture a calibration. */
    @Volatile var lastUp: FloatArray? = null
        private set

    var vibrationCutoffHz: Double
        get() = highPass.cutoffHz
        set(value) { highPass.cutoffHz = value }

    fun reset() { highPass.reset(); gravitySmoother.reset(); lastUp = null }

    /** Returns the channel values for [kind], or null when the sample cannot be interpreted. */
    fun apply(raw: RawSample): FloatArray? {
        val v = raw.values
        return when (kind) {
            SensorKind.INCLINOMETER -> {
                val up = if (usesGravityFallback) {
                    if (v.size < 3) return null
                    Orientation.upFromGravity(gravitySmoother.process(raw.timestampNs, v.copyOf(3)))
                } else {
                    Orientation.upFromRotationVector(v)
                } ?: return null
                lastUp = up
                Orientation.angles(up, reference)
            }
            SensorKind.VIBRATION -> {
                if (v.size < 3) null else highPass.process(raw.timestampNs, v.copyOf(3))
            }
            else -> if (v.size < kind.channelCount) null else v.copyOf(kind.channelCount)
        }
    }

    companion object {
        const val DEFAULT_VIBRATION_CUTOFF_HZ = 1.0
        const val GRAVITY_SMOOTHING_S = 0.15
    }
}
