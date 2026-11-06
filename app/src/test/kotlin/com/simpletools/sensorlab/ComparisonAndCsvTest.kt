package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.export.CsvExporter
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.CsvFormat
import com.simpletools.sensorlab.feature.comparison.Compatibility
import com.simpletools.sensorlab.feature.comparison.MetricDiff
import com.simpletools.sensorlab.feature.comparison.SessionComparison
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

fun session(
    id: String = "a", kind: SensorKind = SensorKind.ACCELEROMETER, method: String = kind.method, unit: String = kind.unit,
    mean: Double = 10.0, min: Double = 9.0, max: Double = 11.0, rms: Double = 10.1, count: Int = 100, durationMs: Long = 5000,
    name: String = "S $id",
) = SessionEntity(
    id, name, kind.key, method, unit, 1_700_000_000_000, 1_700_000_005_000, durationMs, count, 20.0,
    min, max, mean, rms, "COMPLETED", "", "requested=25Hz",
)

class ComparisonTest {
    // 16. session comparison
    @Test fun compatibleSessionsAreCompared() {
        val a = session("a", mean = 10.0, min = 8.0, max = 12.0, rms = 10.5, durationMs = 5000)
        val b = session("b", mean = 12.0, min = 9.0, max = 15.0, rms = 12.6, durationMs = 10_000)
        assertEquals(Compatibility.Compatible, SessionComparison.check(a, b))
        val r = SessionComparison.compare(a, b)
        assertEquals(2.0, r.mean.absolute!!, 1e-12)
        assertEquals(20.0, r.mean.percent(true)!!, 1e-9)
        assertEquals(100.0, r.duration.percent(true)!!, 1e-9)
        assertEquals(3.0, r.max.absolute!!, 1e-12)
    }

    @Test fun differentSensorsAreRefused() {
        val a = session("a", SensorKind.ACCELEROMETER)
        val b = session("b", SensorKind.LINEAR_ACCELERATION)
        // same unit (m/s²) but not the same physical measurement: gravity included vs removed
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.SENSOR_TYPE), SessionComparison.check(a, b))
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.SENSOR_TYPE),
            SessionComparison.check(session("a", SensorKind.LIGHT), session("b", SensorKind.PRESSURE)))
    }

    @Test fun differentUnitsOrMethodsAreRefused() {
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.UNIT), SessionComparison.check(session("a"), session("b", unit = "g")))
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.METHOD),
            SessionComparison.check(session("a", SensorKind.INCLINOMETER), session("b", SensorKind.INCLINOMETER, method = "gravity-tilt")))
    }

    @Test fun sameSessionAndEmptySessionsAreRefused() {
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.SAME_SESSION), SessionComparison.check(session("a"), session("a")))
        assertEquals(Compatibility.Incompatible(Compatibility.Reason.NO_DATA), SessionComparison.check(session("a"), session("b", count = 0)))
    }

    @Test fun candidatesAreFilteredToCompatibleSessions() {
        val a = session("a")
        val all = listOf(a, session("b"), session("c", SensorKind.GYROSCOPE), session("d", method = "other"))
        assertEquals(listOf("b"), SessionComparison.candidatesFor(a, all).map { it.id })
    }

    @Test fun percentIsOmittedWhenMeaninglessOrReferenceIsZero() {
        assertNull(MetricDiff(0.0, 5.0).percent(true))
        assertNull(MetricDiff(20.0, 25.0).percent(false))
        assertNull(MetricDiff(null, 5.0).percent(true))
        assertNull(MetricDiff(null, 5.0).absolute)
        assertFalse(SensorKind.AMBIENT_TEMPERATURE.percentIsMeaningful)
        assertTrue(SensorKind.PRESSURE.percentIsMeaningful)
        assertFalse(SessionComparison.rmsApplies(SensorKind.AMBIENT_TEMPERATURE))
        assertTrue(SessionComparison.rmsApplies(SensorKind.VIBRATION))
    }

    @Test(expected = IllegalArgumentException::class)
    fun comparingIncompatibleSessionsThrows() {
        SessionComparison.compare(session("a", SensorKind.LIGHT), session("b", SensorKind.PRESSURE))
    }
}

class CsvExportTest {
    private fun sample(i: Int, v0: Float = 1.5f, v1: Float = -2f, v2: Float = 9.8f) =
        SampleEntity(i + 1L, "a", i, i * 20_000L, 1_700_000_000_000 + i * 20L, v0, v1, v2, 10.1f)

    private suspend fun export(kind: SensorKind, format: CsvFormat, rows: List<SampleEntity>, name: String = "n"): String {
        val out = ByteArrayOutputStream()
        val exporter = CsvExporter(format)
        val total = exporter.write(session("a", kind, name = name), out) { from, limit -> rows.filter { it.idx >= from }.take(limit) }
        assertEquals(rows.size, total)
        return out.toString(Charsets.UTF_8)
    }

    // 17. CSV export
    @Test fun vectorSensorHasAxisAndNormColumns() = runTest {
        val csv = export(SensorKind.ACCELEROMETER, CsvFormat.INTERNATIONAL, listOf(sample(0), sample(1)))
        assertTrue(csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals("timestamp_iso,elapsed_s,sensor,X,Y,Z,norm,unit", lines[0])
        assertEquals(3, lines.size)
        val cols = lines[2].split(",")
        assertEquals(8, cols.size)
        assertEquals("0.020000", cols[1])
        assertEquals("accelerometer", cols[2])
        assertEquals("1.500000", cols[3])
        assertEquals("-2.000000", cols[4])
        assertEquals("m/s²", cols[7])
        assertTrue(cols[0].startsWith("2023-11-1"))
    }

    @Test fun scalarSensorHasSingleValueColumn() = runTest {
        val csv = export(SensorKind.LIGHT, CsvFormat.INTERNATIONAL, listOf(sample(0, v0 = 312.5f)))
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals("timestamp_iso,elapsed_s,sensor,value,unit", lines[0])
        assertEquals(listOf("light", "312.500000", "lx"), lines[1].split(",").drop(2))
    }

    @Test fun excelFranceUsesSemicolonAndDecimalComma() = runTest {
        val csv = export(SensorKind.PRESSURE, CsvFormat.EXCEL_FR, listOf(sample(0, v0 = 1013.25f)))
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals("timestamp_iso;elapsed_s;sensor;value;unit", lines[0])
        assertTrue(lines[1].contains(";1013,250000;"))
        assertEquals(2, lines[1].count { it == ',' }) // two decimal commas, all other separators are ';'
    }

    @Test fun largeExportsArePagedWithoutLoss() = runTest {
        val rows = (0 until CsvExporter.PAGE * 2 + 17).map { sample(it) }
        val csv = export(SensorKind.GYROSCOPE, CsvFormat.INTERNATIONAL, rows)
        assertEquals(rows.size + 1, csv.removePrefix("\uFEFF").trimEnd().split("\r\n").size)
    }

    @Test fun emptySessionStillProducesAHeader() = runTest {
        val csv = export(SensorKind.MAGNETOMETER, CsvFormat.INTERNATIONAL, emptyList())
        assertEquals(1, csv.removePrefix("\uFEFF").trimEnd().split("\r\n").size)
    }
}
