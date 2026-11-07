package com.simpletools.sensorlab.core.sensors

import android.hardware.Sensor
import androidx.annotation.StringRes
import com.simpletools.sensorlab.R
import kotlin.math.sqrt

/**
 * The measurement modules offered by SensorLab.
 *
 * [key] is persisted in the database and must never change.
 * [method] identifies how the stored values are computed; two sessions are only comparable when
 * kind, unit and method are identical.
 */
enum class SensorKind(
    val key: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val androidType: Int,
    val channelLabels: List<String>,
    val unit: String,
    val method: String,
    val fallbackAndroidType: Int? = null,
) {
    ACCELEROMETER(
        "accelerometer", R.string.sensor_accelerometer, R.string.sensor_accelerometer_desc,
        Sensor.TYPE_ACCELEROMETER, listOf("X", "Y", "Z"), "m/s²", "raw-with-gravity",
    ),
    LINEAR_ACCELERATION(
        "linear_acceleration", R.string.sensor_linear, R.string.sensor_linear_desc,
        Sensor.TYPE_LINEAR_ACCELERATION, listOf("X", "Y", "Z"), "m/s²", "raw-gravity-removed",
    ),
    GYROSCOPE(
        "gyroscope", R.string.sensor_gyroscope, R.string.sensor_gyroscope_desc,
        Sensor.TYPE_GYROSCOPE, listOf("X", "Y", "Z"), "rad/s", "raw",
    ),
    MAGNETOMETER(
        "magnetometer", R.string.sensor_magnetometer, R.string.sensor_magnetometer_desc,
        Sensor.TYPE_MAGNETIC_FIELD, listOf("X", "Y", "Z"), "µT", "raw-calibrated",
    ),
    LIGHT(
        "light", R.string.sensor_light, R.string.sensor_light_desc,
        Sensor.TYPE_LIGHT, listOf(""), "lx", "raw",
    ),
    PROXIMITY(
        "proximity", R.string.sensor_proximity, R.string.sensor_proximity_desc,
        Sensor.TYPE_PROXIMITY, listOf(""), "cm", "raw",
    ),
    PRESSURE(
        "pressure", R.string.sensor_pressure, R.string.sensor_pressure_desc,
        Sensor.TYPE_PRESSURE, listOf(""), "hPa", "raw",
    ),
    AMBIENT_TEMPERATURE(
        "ambient_temperature", R.string.sensor_temperature, R.string.sensor_temperature_desc,
        Sensor.TYPE_AMBIENT_TEMPERATURE, listOf(""), "°C", "raw",
    ),
    INCLINOMETER(
        "inclinometer", R.string.sensor_inclinometer, R.string.sensor_inclinometer_desc,
        Sensor.TYPE_ROTATION_VECTOR, listOf("pitch", "roll", "tilt"), "°", "rotation-vector-tilt",
        fallbackAndroidType = Sensor.TYPE_ACCELEROMETER,
    ),
    VIBRATION(
        "vibration", R.string.sensor_vibration, R.string.sensor_vibration_desc,
        Sensor.TYPE_ACCELEROMETER, listOf("X", "Y", "Z"), "m/s²", "highpass-accelerometer",
    ),
    ;

    val channelCount: Int get() = channelLabels.size
    val isVector: Boolean get() = channelCount == 3

    /** Index of the channel that is the "headline" scalar for inclinometer-like kinds, else -1. */
    private val scalarChannel: Int get() = if (this == INCLINOMETER) 2 else -1

    /** Headline scalar: vector norm for 3-axis sensors, the angle for the inclinometer, else the value. */
    fun scalarOf(values: FloatArray): Float = when {
        scalarChannel >= 0 -> values[scalarChannel]
        isVector -> VectorMath.norm(values[0], values[1], values[2])
        else -> values[0]
    }

    /** Whether the scalar is a magnitude where "percent difference" is a meaningful comparison. */
    val percentIsMeaningful: Boolean get() = this != AMBIENT_TEMPERATURE && this != INCLINOMETER

    companion object {
        fun fromKey(key: String): SensorKind? = entries.firstOrNull { it.key == key }
    }
}

object VectorMath {
    fun norm(x: Float, y: Float, z: Float): Float =
        sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z).toFloat()
}
