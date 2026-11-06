package com.simpletools.sensorlab.core.measurement

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tilt maths for the digital level. Everything is derived from the "up" unit vector expressed in
 * the device frame (what a stationary accelerometer reads, normalised). The deprecated
 * orientation sensor is deliberately not used.
 *
 * Conventions (device lying flat, screen up => up = (0,0,1) => all angles 0):
 *  - pitch: rotation raising the top edge is positive.
 *  - roll: roll = atan2(ux, uz), positive when the right edge goes down.
 *  - tilt: angle between the device normal and the vertical (0 flat, 90 standing).
 */
object Orientation {
    private fun deg(rad: Double) = rad * 180.0 / PI

    /** Up vector (third row of the rotation matrix) from an Android rotation-vector (x, y, z[, w]). */
    fun upFromRotationVector(rv: FloatArray): FloatArray? {
        if (rv.size < 3) return null
        val x = rv[0].toDouble(); val y = rv[1].toDouble(); val z = rv[2].toDouble()
        val w = if (rv.size >= 4 && rv[3] != 0f && rv[3].toDouble() in -1.0..1.0) rv[3].toDouble()
        else sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val r6 = 2.0 * (x * z - w * y)
        val r7 = 2.0 * (y * z + w * x)
        val r8 = 1.0 - 2.0 * (x * x + y * y)
        return floatArrayOf(r6.toFloat(), r7.toFloat(), r8.toFloat())
    }

    /** Normalised up vector from a gravity reading; null when the reading is (near) zero. */
    fun upFromGravity(g: FloatArray): FloatArray? {
        val n = sqrt(g[0].toDouble() * g[0] + g[1].toDouble() * g[1] + g[2].toDouble() * g[2])
        if (n < 1e-3) return null
        return floatArrayOf((g[0] / n).toFloat(), (g[1] / n).toFloat(), (g[2] / n).toFloat())
    }

    fun pitchDeg(up: FloatArray): Double =
        deg(atan2(up[1].toDouble(), sqrt(up[0].toDouble() * up[0] + up[2].toDouble() * up[2])))

    fun rollDeg(up: FloatArray): Double = deg(atan2(up[0].toDouble(), up[2].toDouble()))

    /** Angle between [up] and the reference direction (default: the true vertical = device z). */
    fun tiltDeg(up: FloatArray, reference: FloatArray? = null): Double {
        val ref = reference ?: floatArrayOf(0f, 0f, 1f)
        val dot = (up[0] * ref[0] + up[1] * ref[1] + up[2] * ref[2]).toDouble().coerceIn(-1.0, 1.0)
        return deg(acos(dot))
    }

    /** pitch, roll, tilt relative to an optional user reference surface. */
    fun angles(up: FloatArray, reference: FloatArray?): FloatArray {
        val pitch = pitchDeg(up) - (reference?.let { pitchDeg(it) } ?: 0.0)
        val roll = rollDeg(up) - (reference?.let { rollDeg(it) } ?: 0.0)
        return floatArrayOf(pitch.toFloat(), roll.toFloat(), tiltDeg(up, reference).toFloat())
    }
}
