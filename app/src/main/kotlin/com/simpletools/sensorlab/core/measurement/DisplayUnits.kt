package com.simpletools.sensorlab.core.measurement

import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AngularUnit
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.PressureUnit
import com.simpletools.sensorlab.core.settings.TemperatureUnit

/** Converts stored (native-unit) values to the units chosen by the user, for display only. */
class DisplayUnits(private val settings: AppSettings) {

    fun unit(kind: SensorKind): String = when (kind) {
        SensorKind.GYROSCOPE -> if (settings.angularUnit == AngularUnit.DEG_PER_S) "°/s" else "rad/s"
        SensorKind.PRESSURE -> if (settings.pressureUnit == PressureUnit.MMHG) "mmHg" else "hPa"
        SensorKind.AMBIENT_TEMPERATURE -> if (settings.temperatureUnit == TemperatureUnit.FAHRENHEIT) "°F" else "°C"
        else -> kind.unit
    }

    fun convert(kind: SensorKind, native: Double): Double = when (kind) {
        SensorKind.GYROSCOPE -> if (settings.angularUnit == AngularUnit.DEG_PER_S) Units.radToDeg(native) else native
        SensorKind.PRESSURE -> if (settings.pressureUnit == PressureUnit.MMHG) Units.hpaToMmHg(native) else native
        SensorKind.AMBIENT_TEMPERATURE ->
            if (settings.temperatureUnit == TemperatureUnit.FAHRENHEIT) Units.celsiusToFahrenheit(native) else native
        else -> native
    }

    fun convert(kind: SensorKind, native: Float): Float = convert(kind, native.toDouble()).toFloat()

    /** Number of decimals that suits the kind. */
    fun decimals(kind: SensorKind): Int = when (kind) {
        SensorKind.LIGHT -> 1
        SensorKind.PRESSURE -> 2
        SensorKind.GYROSCOPE -> 3
        SensorKind.VIBRATION -> 3
        SensorKind.INCLINOMETER -> 1
        else -> 2
    }

    fun format(kind: SensorKind, native: Double): String {
        if (!native.isFinite()) return "—"
        return "%.${decimals(kind)}f".format(convert(kind, native))
    }
}
