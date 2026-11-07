package com.simpletools.sensorlab.core.charts

import kotlin.math.max
import kotlin.math.min

/**
 * Visible time range of a historical chart, in seconds, inside the full session range.
 * All operations keep the window inside the session and never make it shorter than [MIN_SPAN_S].
 */
data class ZoomWindow(
    val start: Double,
    val end: Double,
    val fullStart: Double,
    val fullEnd: Double,
) {
    val span: Double get() = end - start
    val isFullRange: Boolean get() = start <= fullStart + 1e-9 && end >= fullEnd - 1e-9

    /** [factor] > 1 zooms in, < 1 zooms out. [anchor] in 0..1 is the point of the window that stays fixed. */
    fun zoomed(factor: Double, anchor: Double = 0.5): ZoomWindow {
        if (!factor.isFinite() || factor <= 0.0) return this
        val full = fullEnd - fullStart
        if (full <= 0.0) return this
        val a = anchor.coerceIn(0.0, 1.0)
        val minSpan = min(MIN_SPAN_S, full)
        val newSpan = (span / factor).coerceIn(minSpan, full)
        val pivot = start + span * a
        val newStart = (pivot - newSpan * a).coerceIn(fullStart, max(fullStart, fullEnd - newSpan))
        return copy(start = newStart, end = newStart + newSpan)
    }

    /** [fraction] of the visible span to move by; positive moves towards later times. */
    fun panned(fraction: Double): ZoomWindow {
        if (!fraction.isFinite()) return this
        val newStart = (start + span * fraction).coerceIn(fullStart, max(fullStart, fullEnd - span))
        return copy(start = newStart, end = newStart + span)
    }

    fun reset(): ZoomWindow = copy(start = fullStart, end = fullEnd)

    companion object {
        const val MIN_SPAN_S = 0.2

        fun full(fullStart: Double, fullEnd: Double): ZoomWindow {
            val e = if (fullEnd > fullStart) fullEnd else fullStart + 0.001
            return ZoomWindow(fullStart, e, fullStart, e)
        }
    }
}
