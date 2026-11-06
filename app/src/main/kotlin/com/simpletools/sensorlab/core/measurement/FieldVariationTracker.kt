package com.simpletools.sensorlab.core.measurement

import kotlin.math.abs

enum class FieldVariationLevel { STABLE, MODERATE, STRONG }

/**
 * Descriptive indicator of how much the magnetic field magnitude departs from its recent
 * baseline. It says "the field changed"; it does NOT say why (metal, magnet, electronics...).
 */
class FieldVariationTracker(
    private val timeConstantS: Double = 5.0,
    private val moderateUt: Double = 3.0,
    private val strongUt: Double = 10.0,
) {
    private var baseline = Double.NaN
    private var lastT = Long.MIN_VALUE

    var deviationUt: Double = 0.0
        private set

    fun reset() { baseline = Double.NaN; lastT = Long.MIN_VALUE; deviationUt = 0.0 }

    fun add(timestampNs: Long, magnitudeUt: Double): FieldVariationLevel {
        if (baseline.isNaN() || lastT == Long.MIN_VALUE || timestampNs <= lastT) {
            baseline = magnitudeUt
        } else {
            val dt = (timestampNs - lastT) / 1e9
            baseline += (dt / (timeConstantS + dt)) * (magnitudeUt - baseline)
        }
        lastT = timestampNs
        deviationUt = magnitudeUt - baseline
        return levelOf(abs(deviationUt))
    }

    fun levelOf(absDeviation: Double) = when {
        absDeviation >= strongUt -> FieldVariationLevel.STRONG
        absDeviation >= moderateUt -> FieldVariationLevel.MODERATE
        else -> FieldVariationLevel.STABLE
    }
}

/** Purely indicative brightness classes; not a certified photometric measurement. */
enum class LightCategory {
    VERY_DARK, DARK, DIM_INDOOR, NORMAL_INDOOR, BRIGHT_INDOOR, OVERCAST_DAY, FULL_DAYLIGHT;

    companion object {
        fun of(lux: Double): LightCategory = when {
            lux < 1 -> VERY_DARK
            lux < 50 -> DARK
            lux < 200 -> DIM_INDOOR
            lux < 500 -> NORMAL_INDOOR
            lux < 1_000 -> BRIGHT_INDOOR
            lux < 10_000 -> OVERCAST_DAY
            else -> FULL_DAYLIGHT
        }
    }
}
