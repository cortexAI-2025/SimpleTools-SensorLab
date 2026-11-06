package com.simpletools.sensorlab.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** One curve. [t] is in seconds (any origin), [v] in the displayed unit. NaN breaks the line. */
class ChartSeries(
    val label: String,
    val color: Color,
    val t: FloatArray,
    val v: FloatArray,
)

/**
 * Lightweight Canvas line chart: time axis, unit axis with round ticks, visible min / max,
 * and line breaks where samples are missing (gap larger than [gapFactor] x the typical interval).
 *
 * Rendering is independent from acquisition: callers hand over an already decimated snapshot.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    xFrom: Float? = null,
    xTo: Float? = null,
    xUnit: String = "s",
    gapFactor: Float = 6f,
    description: String = "",
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(color = textColor, fontSize = 10.sp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val left = 44.dp.toPx()
        val right = 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        if (w <= 0 || h <= 0) return@Canvas

        var yMin = Double.POSITIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY
        var tMin = Float.POSITIVE_INFINITY
        var tMax = Float.NEGATIVE_INFINITY
        for (s in series) {
            for (i in s.v.indices) {
                val y = s.v[i]
                if (y.isFinite()) { yMin = minOf(yMin, y.toDouble()); yMax = maxOf(yMax, y.toDouble()) }
            }
            if (s.t.isNotEmpty()) { tMin = minOf(tMin, s.t.first()); tMax = maxOf(tMax, s.t.last()) }
        }
        val x0 = xFrom ?: if (tMin.isFinite()) tMin else 0f
        val x1 = xTo ?: if (tMax.isFinite()) tMax else 1f
        val xSpan = max(x1 - x0, 1e-6f)
        val scale = NiceScale.compute(
            if (yMin.isFinite()) yMin else 0.0,
            if (yMax.isFinite()) yMax else 1.0,
        )
        val ySpan = (scale.max - scale.min).coerceAtLeast(1e-12)

        fun px(t: Float) = left + (t - x0) / xSpan * w
        fun py(v: Float) = (top + h - ((v - scale.min) / ySpan * h)).toFloat()

        // Grid + Y labels
        for (tick in scale.ticks) {
            val y = py(tick.toFloat())
            drawLine(axisColor, Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
            val txt = measurer.measure(formatTick(tick, scale.step), labelStyle)
            drawText(txt, topLeft = Offset(left - txt.size.width - 4.dp.toPx(), y - txt.size.height / 2f))
        }
        drawLine(axisColor, Offset(left, top), Offset(left, top + h), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(left, top + h), Offset(left + w, top + h), strokeWidth = 1.5f)

        // X labels (start / end)
        val startTxt = measurer.measure("${formatTick(x0.toDouble(), xSpan / 4.0)} $xUnit", labelStyle)
        val endTxt = measurer.measure("${formatTick(x1.toDouble(), xSpan / 4.0)} $xUnit", labelStyle)
        drawText(startTxt, topLeft = Offset(left, top + h + 3.dp.toPx()))
        drawText(endTxt, topLeft = Offset(left + w - endTxt.size.width, top + h + 3.dp.toPx()))
        val unitTxt = measurer.measure(unit, labelStyle)
        drawText(unitTxt, topLeft = Offset(0f, 0f))

        // Curves
        for (s in series) {
            val n = minOf(s.t.size, s.v.size)
            if (n == 0) continue
            val gap = medianInterval(s.t, n) * gapFactor
            val path = Path()
            var pen = false
            for (i in 0 until n) {
                val y = s.v[i]
                if (!y.isFinite()) { pen = false; continue }
                val x = px(s.t[i]); val yy = py(y)
                val broken = i > 0 && gap > 0 && s.t[i] - s.t[i - 1] > gap
                if (!pen || broken) { path.moveTo(x, yy); pen = true } else path.lineTo(x, yy)
            }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
        }

        // Min / max markers of the visible data
        if (yMin.isFinite()) {
            val maxTxt = measurer.measure("max ${formatTick(yMax, scale.step)}", labelStyle.copy(color = textColor))
            val minTxt = measurer.measure("min ${formatTick(yMin, scale.step)}", labelStyle.copy(color = textColor))
            drawText(maxTxt, topLeft = Offset(left + w - maxTxt.size.width - 4.dp.toPx(), top + 2.dp.toPx()))
            drawText(minTxt, topLeft = Offset(left + w - minTxt.size.width - 4.dp.toPx(), top + h - minTxt.size.height - 2.dp.toPx()))
        }
    }
}

internal fun medianInterval(t: FloatArray, n: Int): Float {
    if (n < 3) return 0f
    val d = FloatArray(n - 1) { t[it + 1] - t[it] }
    d.sort()
    return d[d.size / 2]
}

internal fun formatTick(v: Double, step: Double): String {
    val decimals = when {
        step >= 1 -> 0
        step >= 0.1 -> 1
        step >= 0.01 -> 2
        else -> 3
    }
    return "%.${decimals}f".format(v)
}
