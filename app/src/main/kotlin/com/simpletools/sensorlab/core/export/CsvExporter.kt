package com.simpletools.sensorlab.core.export

import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.CsvFormat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streams a session as CSV, page by page, so memory use is independent of the session length.
 * Values are written in the native unit of the sensor; the unit column says which.
 * UTF-8 with BOM so spreadsheets open accented characters correctly.
 */
class CsvExporter(private val format: CsvFormat) {

    fun header(kind: SensorKind): List<String> {
        val base = listOf("timestamp_iso", "elapsed_s", "sensor")
        val values = if (kind.channelCount == 3) {
            kind.channelLabels + listOf(if (kind == SensorKind.INCLINOMETER) "angle_main" else "norm")
        } else {
            listOf("value")
        }
        return base + values + "unit"
    }

    fun row(kind: SensorKind, s: SampleEntity, isoFormatter: SimpleDateFormat): List<String> {
        val cells = ArrayList<String>(8)
        cells += isoFormatter.format(Date(s.wallMs))
        cells += num(s.tUs / 1e6, 6)
        cells += kind.key
        if (kind.channelCount == 3) {
            cells += num(s.v0.toDouble(), 6); cells += num(s.v1.toDouble(), 6); cells += num(s.v2.toDouble(), 6)
            cells += num(s.scalar.toDouble(), 6)
        } else {
            cells += num(s.v0.toDouble(), 6)
        }
        cells += kind.unit
        return cells
    }

    suspend fun write(
        session: SessionEntity,
        out: OutputStream,
        page: suspend (fromIdx: Int, limit: Int) -> List<SampleEntity>,
    ): Int {
        val kind = SensorKind.fromKey(session.kindKey) ?: error("Unknown sensor ${session.kindKey}")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
        out.write(UTF8_BOM)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(line(header(kind)))
        var from = 0
        var total = 0
        while (true) {
            val rows = page(from, PAGE)
            if (rows.isEmpty()) break
            for (r in rows) writer.write(line(row(kind, r, iso)))
            total += rows.size
            from = rows.last().idx + 1
            if (rows.size < PAGE) break
        }
        writer.flush()
        return total
    }

    private fun line(cells: List<String>) = cells.joinToString(format.separator.toString(), transform = ::escape) + "\r\n"

    private fun escape(cell: String): String =
        if (cell.any { it == format.separator || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else cell

    private fun num(v: Double, decimals: Int): String {
        if (!v.isFinite()) return ""
        val s = String.format(Locale.ROOT, "%.${decimals}f", v)
        return if (format.decimal == '.') s else s.replace('.', format.decimal)
    }

    companion object {
        const val PAGE = 2000
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
