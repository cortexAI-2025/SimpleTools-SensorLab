package com.simpletools.sensorlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.simpletools.sensorlab.core.database.ChannelAggregates
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.export.ExportFormat
import com.simpletools.sensorlab.core.export.ExportService
import com.simpletools.sensorlab.core.export.PdfReport
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real Skia drawing, so the report can be checked pixel-wise
class PdfExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun samples(n: Int) = (0 until n).map {
        SampleEntity(it + 1L, "a", it, it * 100_000L, 1_700_000_000_000 + it * 100L, it.toFloat(), -it.toFloat(), 9.8f, 10f + it)
    }

    private fun render(session: com.simpletools.sensorlab.core.database.SessionEntity, agg: ChannelAggregates?, data: List<SampleEntity>, png: String? = null): Bitmap {
        val bmp = Bitmap.createBitmap(PdfReport.W, PdfReport.H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        PdfReport(context, DisplayUnits(AppSettings())).render(canvas, session, agg, data)
        System.getProperty("sensorlab.reportDir")?.let { dir ->
            png?.let { name -> java.io.File(dir).mkdirs(); java.io.File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return bmp
    }

    private fun Bitmap.nonWhitePixels(): Int {
        val px = IntArray(width * height); getPixels(px, 0, width, 0, 0, width, height)
        return px.count { it != Color.WHITE }
    }

    // 18. PDF export: the page is rendered on a canvas (PdfDocument itself is not available in Robolectric)
    @Test fun reportIsDrawnForAVectorSensor() {
        val agg = ChannelAggregates(0.0, 9.0, 4.5, 30.0, -9.0, 0.0, -4.5, 30.0, 9.8, 9.8, 9.8, 96.0)
        val bmp = render(
            session("a", SensorKind.ACCELEROMETER, name = "Table test").copy(note = "Mesure sur la table.\nDeuxième ligne."),
            agg, samples(50), "report-accelerometer.png",
        )
        assertEquals(PdfReport.W, bmp.width)
        // header band (accent colour) is painted across the full width
        assertEquals(0xFF3457D5.toInt(), bmp.getPixel(300, 10))
        assertTrue("page should contain text and a chart", bmp.nonWhitePixels() > 5_000)
    }

    @Test fun reportHandlesScalarSensorsAndEmptyCharts() {
        for (kind in listOf(SensorKind.LIGHT, SensorKind.PRESSURE, SensorKind.AMBIENT_TEMPERATURE)) {
            val bmp = render(session("a", kind), null, emptyList(), "report-${kind.key}.png")
            assertTrue("$kind", bmp.nonWhitePixels() > 2_000)
        }
    }

    @Test fun reportDoesNotFailOnLongNotesOrFlatData() {
        val flat = (0 until 20).map { SampleEntity(it + 1L, "a", it, it * 1000L, it.toLong(), 5f, 5f, 5f, 5f) }
        val bmp = render(session("a", SensorKind.GYROSCOPE).copy(note = "mot ".repeat(400)), null, flat)
        assertTrue(bmp.nonWhitePixels() > 2_000)
    }

    @Test fun shareFileNamesAreSafeAndTyped() {
        val s = session("a", name = "Test/../é accentué: 50%")
        val csv = ExportService.fileName(s, ExportFormat.CSV)
        val pdf = ExportService.fileName(s, ExportFormat.PDF)
        assertTrue(csv.startsWith("SensorLab_") && csv.endsWith(".csv"))
        assertTrue(pdf.endsWith(".pdf"))
        assertTrue(Regex("[A-Za-z0-9._-]+").matches(csv))
        assertEquals("text/csv", ExportFormat.CSV.mime)
        assertEquals("application/pdf", ExportFormat.PDF.mime)
    }
}
