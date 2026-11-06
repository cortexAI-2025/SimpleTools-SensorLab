package com.simpletools.sensorlab.feature.comparison

import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlin.math.abs

sealed interface Compatibility {
    data object Compatible : Compatibility
    data class Incompatible(val reason: Reason) : Compatibility
    enum class Reason { SAME_SESSION, SENSOR_TYPE, UNIT, METHOD, NO_DATA }
}

data class MetricDiff(val a: Double?, val b: Double?) {
    /** b - a, or null if either side is missing. */
    val absolute: Double? get() = if (a != null && b != null) b - a else null

    /**
     * Relative difference of b versus a in percent. Null when the reference is (nearly) zero or
     * when percentages have no physical meaning for the quantity.
     */
    fun percent(meaningful: Boolean): Double? {
        if (!meaningful || a == null || b == null || abs(a) < 1e-9) return null
        return (b - a) / abs(a) * 100.0
    }
}

data class ComparisonResult(
    val duration: MetricDiff,
    val count: MetricDiff,
    val mean: MetricDiff,
    val min: MetricDiff,
    val max: MetricDiff,
    val rms: MetricDiff,
)

object SessionComparison {

    /** Two sessions are comparable only if they measure the same physical quantity the same way. */
    fun check(a: SessionEntity, b: SessionEntity): Compatibility = when {
        a.id == b.id -> Compatibility.Incompatible(Compatibility.Reason.SAME_SESSION)
        a.kindKey != b.kindKey -> Compatibility.Incompatible(Compatibility.Reason.SENSOR_TYPE)
        a.unit != b.unit -> Compatibility.Incompatible(Compatibility.Reason.UNIT)
        a.method != b.method -> Compatibility.Incompatible(Compatibility.Reason.METHOD)
        a.sampleCount == 0 || b.sampleCount == 0 -> Compatibility.Incompatible(Compatibility.Reason.NO_DATA)
        else -> Compatibility.Compatible
    }

    fun candidatesFor(a: SessionEntity, all: List<SessionEntity>): List<SessionEntity> =
        all.filter { check(a, it) == Compatibility.Compatible }

    fun compare(a: SessionEntity, b: SessionEntity): ComparisonResult {
        require(check(a, b) == Compatibility.Compatible) { "Sessions are not compatible" }
        return ComparisonResult(
            duration = MetricDiff(a.durationMs / 1000.0, b.durationMs / 1000.0),
            count = MetricDiff(a.sampleCount.toDouble(), b.sampleCount.toDouble()),
            mean = MetricDiff(a.scalarMean, b.scalarMean),
            min = MetricDiff(a.scalarMin, b.scalarMin),
            max = MetricDiff(a.scalarMax, b.scalarMax),
            rms = MetricDiff(a.scalarRms, b.scalarRms),
        )
    }

    /** RMS is meaningful for zero-centred or magnitude signals, not for temperature / pressure offsets. */
    fun rmsApplies(kind: SensorKind): Boolean = when (kind) {
        SensorKind.AMBIENT_TEMPERATURE, SensorKind.PRESSURE, SensorKind.LIGHT -> false
        else -> true
    }
}
