package com.simpletools.sensorlab.core.measurement

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * First-order high-pass filter, applied per channel, with a cutoff expressed in Hz.
 *
 *   alpha = RC / (RC + dt),  RC = 1 / (2*pi*fc)
 *   y[i]  = alpha * (y[i-1] + x[i] - x[i-1])
 *
 * dt is taken from the sample timestamps, so the filter stays correct when the real sampling
 * rate differs from the requested one. The first sample outputs 0 (filter initialisation).
 * It removes the static component (gravity, slow drift) so that vibration amplitude can be read.
 */
class HighPassFilter(private val channels: Int, var cutoffHz: Double) {
    private var lastT = Long.MIN_VALUE
    private val lastIn = DoubleArray(channels)
    private val lastOut = DoubleArray(channels)

    fun reset() {
        lastT = Long.MIN_VALUE
        lastIn.fill(0.0); lastOut.fill(0.0)
    }

    fun process(timestampNs: Long, input: FloatArray): FloatArray {
        val out = FloatArray(channels)
        if (lastT == Long.MIN_VALUE || timestampNs <= lastT) {
            for (c in 0 until channels) { lastIn[c] = input[c].toDouble(); lastOut[c] = 0.0 }
            lastT = timestampNs
            return out
        }
        val dt = (timestampNs - lastT) / 1e9
        val rc = 1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(MIN_CUTOFF_HZ))
        val alpha = rc / (rc + dt)
        for (c in 0 until channels) {
            val x = input[c].toDouble()
            val y = alpha * (lastOut[c] + x - lastIn[c])
            lastIn[c] = x; lastOut[c] = y
            out[c] = y.toFloat()
        }
        lastT = timestampNs
        return out
    }

    companion object { const val MIN_CUTOFF_HZ = 0.05 }
}

/** Exponential smoother with a time constant in seconds (low-pass), per channel. */
class ExponentialSmoother(private val channels: Int, var timeConstantS: Double) {
    private var lastT = Long.MIN_VALUE
    private val state = DoubleArray(channels)

    fun reset() { lastT = Long.MIN_VALUE }

    fun process(timestampNs: Long, input: FloatArray): FloatArray {
        val out = FloatArray(channels)
        if (lastT == Long.MIN_VALUE || timestampNs <= lastT) {
            for (c in 0 until channels) { state[c] = input[c].toDouble(); out[c] = input[c] }
            lastT = timestampNs
            return out
        }
        val dt = (timestampNs - lastT) / 1e9
        val a = dt / (timeConstantS.coerceAtLeast(1e-6) + dt)
        for (c in 0 until channels) {
            state[c] += a * (input[c] - state[c])
            out[c] = state[c].toFloat()
        }
        lastT = timestampNs
        return out
    }
}

/** RMS over a sliding time window. Memory is bounded by the window length. */
class WindowedRms(private val windowNs: Long) {
    private val times = ArrayDeque<Long>()
    private val squares = ArrayDeque<Double>()
    private var sumSq = 0.0

    fun reset() { times.clear(); squares.clear(); sumSq = 0.0 }

    fun add(timestampNs: Long, value: Double): Double {
        val sq = value * value
        times.addLast(timestampNs); squares.addLast(sq); sumSq += sq
        while (times.isNotEmpty() && timestampNs - times.first() > windowNs) {
            times.removeFirst(); sumSq -= squares.removeFirst()
        }
        if (sumSq < 0) sumSq = squares.sum() // guard against floating point drift
        return sqrt(sumSq / times.size)
    }
}

/** Observed sampling rate over the last [capacity] timestamps. */
class SamplingRateEstimator(private val capacity: Int = 100) {
    private val times = ArrayDeque<Long>()

    fun reset() = times.clear()

    fun add(timestampNs: Long) {
        times.addLast(timestampNs)
        if (times.size > capacity) times.removeFirst()
    }

    /** Samples per second, or null while fewer than two samples were seen. */
    val hz: Double?
        get() {
            if (times.size < 2) return null
            val span = (times.last() - times.first()) / 1e9
            return if (span <= 0) null else (times.size - 1) / span
        }
}
