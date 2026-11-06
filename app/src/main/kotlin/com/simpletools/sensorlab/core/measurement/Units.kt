package com.simpletools.sensorlab.core.measurement

import kotlin.math.PI

/** Pure unit conversions. Stored data always stays in the native unit of each sensor. */
object Units {
    const val STANDARD_GRAVITY = 9.80665

    fun radToDeg(rad: Double): Double = rad * 180.0 / PI
    fun degToRad(deg: Double): Double = deg * PI / 180.0

    const val HPA_TO_MMHG = 0.750061683
    fun hpaToMmHg(hpa: Double): Double = hpa * HPA_TO_MMHG
    fun mmHgToHpa(mmHg: Double): Double = mmHg / HPA_TO_MMHG

    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0
    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    fun mpsToG(a: Double): Double = a / STANDARD_GRAVITY
}
