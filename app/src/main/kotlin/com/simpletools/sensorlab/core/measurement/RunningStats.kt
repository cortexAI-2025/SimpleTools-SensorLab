package com.simpletools.sensorlab.core.measurement

import kotlin.math.sqrt

data class StatsSnapshot(
    val count: Long,
    val min: Double,
    val max: Double,
    val mean: Double,
    val rms: Double,
) {
    val isEmpty: Boolean get() = count == 0L

    companion object {
        val EMPTY = StatsSnapshot(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
}

/** Streaming min / max / mean / RMS in O(1) memory. Non-finite values are ignored. */
class RunningStats {
    private var count = 0L
    private var min = Double.POSITIVE_INFINITY
    private var max = Double.NEGATIVE_INFINITY
    private var sum = 0.0
    private var sumSq = 0.0

    fun add(value: Double) {
        if (!value.isFinite()) return
        count++
        if (value < min) min = value
        if (value > max) max = value
        sum += value
        sumSq += value * value
    }

    fun reset() {
        count = 0; min = Double.POSITIVE_INFINITY; max = Double.NEGATIVE_INFINITY; sum = 0.0; sumSq = 0.0
    }

    fun snapshot(): StatsSnapshot =
        if (count == 0L) StatsSnapshot.EMPTY
        else StatsSnapshot(count, min, max, sum / count, sqrt(sumSq / count))
}

/** Stats for every channel plus the headline scalar of a sensor. */
class MultiStats(channels: Int) {
    val channels: List<RunningStats> = List(channels) { RunningStats() }
    val scalar = RunningStats()

    fun add(values: FloatArray, scalarValue: Float) {
        for (i in channels.indices) channels[i].add(values[i].toDouble())
        scalar.add(scalarValue.toDouble())
    }

    fun reset() {
        channels.forEach { it.reset() }
        scalar.reset()
    }
}

/** Statistics helpers on plain arrays (used by tests and report code). */
object Stats {
    fun mean(values: DoubleArray): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size
    fun rms(values: DoubleArray): Double =
        if (values.isEmpty()) Double.NaN else sqrt(values.sumOf { it * it } / values.size)
}
