package com.simpletools.sensorlab.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.database.ChannelAggregates
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.sensors.SensorKind
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One-page A4 report built with the platform [PdfDocument] (no third-party library). */
class PdfReport(private val context: Context, private val units: DisplayUnits) {

    fun write(
        session: SessionEntity,
        aggregates: ChannelAggregates?,
        chartSamples: List<SampleEntity>,
        out: OutputStream,
    ) {
        val kind = SensorKind.fromKey(session.kindKey) ?: error("Unknown sensor ${session.kindKey}")
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        render(page.canvas, session, aggregates, chartSamples)
        doc.finishPage(page)
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        out.flush()
    }

    /** Draws the report on any A4-sized canvas (a PDF page in production, a bitmap in tests). */
    fun render(canvas: Canvas, session: SessionEntity, aggregates: ChannelAggregates?, chartSamples: List<SampleEntity>) {
        val kind = SensorKind.fromKey(session.kindKey) ?: error("Unknown sensor ${session.kindKey}")
        draw(canvas, session, kind, aggregates, chartSamples)
    }

    private fun s(id: Int, vararg a: Any) = context.getString(id, *a)

    private fun draw(c: Canvas, session: SessionEntity, kind: SensorKind, agg: ChannelAggregates?, data: List<SampleEntity>) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 28, 36) }
        val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 96, 110); textSize = 10f }
        val accent = Color.rgb(0x34, 0x57, 0xD5)
        val m = 40f
        var y = 0f

        // Header band
        c.drawRect(0f, 0f, W.toFloat(), 64f, Paint().apply { color = accent })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawText(s(R.string.app_name), m, 30f, white.apply { textSize = 20f; isFakeBoldText = true })
        c.drawText(s(R.string.report_publisher), m, 48f, white.apply { textSize = 11f; isFakeBoldText = false })
        y = 92f

        c.drawText(session.name, m, y, ink.apply { textSize = 16f; isFakeBoldText = true })
        y += 22f
        ink.isFakeBoldText = false; ink.textSize = 11f
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        val kindName = s(kind.nameRes)
        val rows = listOf(
            s(R.string.report_sensor) to kindName,
            s(R.string.report_date) to df.format(Date(session.startedAtMs)),
            s(R.string.report_duration) to "%.1f s".format(session.durationMs / 1000.0),
            s(R.string.report_samples) to "${session.sampleCount} (≈ %.1f Hz)".format(session.observedRateHz),
            s(R.string.report_unit) to units.unit(kind),
            s(R.string.report_settings) to session.settingsSummary.ifBlank { "—" },
        )
        for ((k, v) in rows) {
            c.drawText(k, m, y, grey.apply { textSize = 10f })
            c.drawText(v, m + 110f, y, ink)
            y += 16f
        }
        y += 8f

        // Statistics table
        c.drawText(s(R.string.report_statistics), m, y, ink.apply { isFakeBoldText = true; textSize = 13f })
        ink.isFakeBoldText = false; ink.textSize = 10.5f
        y += 18f
        val cols = floatArrayOf(m, m + 130f, m + 220f, m + 310f, m + 400f)
        listOf("", s(R.string.stat_min), s(R.string.stat_max), s(R.string.stat_mean), s(R.string.stat_rms))
            .forEachIndexed { i, t -> c.drawText(t, cols[i], y, grey) }
        y += 14f
        fun statRow(label: String, mn: Double?, mx: Double?, avg: Double?, rms: Double?) {
            c.drawText(label, cols[0], y, ink)
            listOf(mn, mx, avg, rms).forEachIndexed { i, v ->
                c.drawText(v?.let { units.format(kind, it) } ?: "—", cols[i + 1], y, ink)
            }
            y += 15f
        }
        val showRms = kind != SensorKind.AMBIENT_TEMPERATURE && kind != SensorKind.PRESSURE && kind != SensorKind.LIGHT
        if (kind.channelCount == 3 && agg != null) {
            val ch = listOf(
                Quad(agg.min0, agg.max0, agg.avg0, agg.msq0),
                Quad(agg.min1, agg.max1, agg.avg1, agg.msq1),
                Quad(agg.min2, agg.max2, agg.avg2, agg.msq2),
            )
            kind.channelLabels.forEachIndexed { i, l ->
                val q = ch[i]
                statRow(l, q.min, q.max, q.avg, q.msq?.let { r -> sqrt(r) }.takeIf { showRms })
            }
        }
        val scalarLabel = if (kind.channelCount == 3) s(R.string.stat_scalar_norm) else s(R.string.stat_value)
        statRow(
            scalarLabel, session.scalarMin, session.scalarMax, session.scalarMean,
            session.scalarRms.takeIf { showRms },
        )
        y += 10f

        // Chart
        val chartH = 200f
        drawChart(c, kind, data, RectF(m, y, W - m, y + chartH), accent)
        y += chartH + 18f

        // Notes
        if (session.note.isNotBlank()) {
            c.drawText(s(R.string.report_observations), m, y, ink.apply { isFakeBoldText = true; textSize = 12f })
            ink.isFakeBoldText = false; ink.textSize = 10.5f
            y += 15f
            for (line in wrap(session.note, ink, W - 2 * m).take(6)) {
                c.drawText(line, m, y, ink); y += 13f
            }
            y += 6f
        }

        // Disclaimer
        val dis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 0); textSize = 9.5f }
        var dy = H - 70f
        for (line in wrap(s(R.string.report_disclaimer), dis, W - 2 * m)) {
            c.drawText(line, m, dy, dis); dy += 12f
        }
    }

    private class Quad(val min: Double?, val max: Double?, val avg: Double?, val msq: Double?)
    private class RectF(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun drawChart(c: Canvas, kind: SensorKind, data: List<SampleEntity>, r: RectF, accent: Int) {
        val frame = Paint().apply { color = Color.rgb(200, 204, 214); style = Paint.Style.STROKE; strokeWidth = 0.8f }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 96, 110); textSize = 8.5f }
        val left = r.l + 38f
        val plot = RectF(left, r.t + 6f, r.r - 6f, r.b - 18f)
        c.drawRect(plot.l, plot.t, plot.r, plot.b, frame)
        if (data.size < 2) {
            c.drawText(s(R.string.report_no_chart), plot.l + 8f, plot.t + 20f, txt)
            return
        }
        val series: List<Pair<Int, (SampleEntity) -> Float>> =
            if (kind.channelCount == 3) listOf(
                Color.rgb(0xD3, 0x2F, 0x2F) to { x: SampleEntity -> x.v0 },
                Color.rgb(0x2E, 0x7D, 0x32) to { x: SampleEntity -> x.v1 },
                Color.rgb(0x15, 0x65, 0xC0) to { x: SampleEntity -> x.v2 },
            ) else listOf(accent to { x: SampleEntity -> x.v0 })
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (d in data) for ((_, f) in series) { val v = units.convert(kind, f(d)); lo = min(lo, v); hi = max(hi, v) }
        if (hi - lo < 1e-6f) { lo -= 1f; hi += 1f }
        val pad = (hi - lo) * 0.05f; lo -= pad; hi += pad
        val t0 = data.first().tUs; val t1 = max(data.last().tUs, t0 + 1)
        for ((color, f) in series) {
            val p = Path()
            data.forEachIndexed { i, d ->
                val x = plot.l + (d.tUs - t0).toFloat() / (t1 - t0) * (plot.r - plot.l)
                val y = plot.b - (units.convert(kind, f(d)) - lo) / (hi - lo) * (plot.b - plot.t)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1f })
        }
        c.drawText("%.2f".format(hi), r.l, plot.t + 8f, txt)
        c.drawText("%.2f".format(lo), r.l, plot.b, txt)
        c.drawText("${units.unit(kind)}", r.l, plot.t + 20f, txt)
        c.drawText("0 s", plot.l, r.b - 4f, txt)
        val end = "%.1f s".format((t1 - t0) / 1e6)
        c.drawText(end, plot.r - txt.measureText(end), r.b - 4f, txt)
        if (kind.channelCount == 3) {
            var lx = plot.l + 6f
            kind.channelLabels.forEachIndexed { i, l ->
                c.drawText(l, lx, plot.t + 10f, Paint(txt).apply { color = series[i].first; isFakeBoldText = true })
                lx += 22f
            }
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val trial = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(trial) > width && line.isNotEmpty()) { out += line; line = word } else line = trial
            }
            out += line
        }
        return out
    }

    companion object { const val W = 595; const val H = 842 }
}
