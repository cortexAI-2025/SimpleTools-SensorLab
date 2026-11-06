package com.simpletools.sensorlab.core.charts

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Axis range with round tick values. */
data class NiceScale(val min: Double, val max: Double, val step: Double) {
    val ticks: List<Double>
        get() {
            if (step <= 0) return listOf(min, max)
            val out = ArrayList<Double>()
            var v = ceil(min / step - 1e-9) * step
            while (v <= max + step * 1e-9) { out += v; v += step }
            return out
        }

    companion object {
        fun compute(dataMin: Double, dataMax: Double, targetTicks: Int = 5): NiceScale {
            var lo = dataMin
            var hi = dataMax
            if (!lo.isFinite() || !hi.isFinite()) return NiceScale(0.0, 1.0, 0.2)
            if (abs(hi - lo) < 1e-12) {
                val pad = if (abs(lo) < 1e-9) 1.0 else abs(lo) * 0.05
                lo -= pad; hi += pad
            }
            val pad = (hi - lo) * 0.05
            lo -= pad; hi += pad
            val rawStep = (hi - lo) / targetTicks.coerceAtLeast(2)
            val mag = 10.0.pow(floor(log10(rawStep)))
            val norm = rawStep / mag
            val step = mag * when {
                norm <= 1 -> 1.0
                norm <= 2 -> 2.0
                norm <= 5 -> 5.0
                else -> 10.0
            }
            return NiceScale(floor(lo / step) * step, ceil(hi / step) * step, step)
        }
    }
}
