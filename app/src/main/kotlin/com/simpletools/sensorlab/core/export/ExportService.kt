package com.simpletools.sensorlab.core.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.database.SessionRepository
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
}

/**
 * CSV / PDF export. Two delivery routes, neither needs a storage permission:
 *  - Storage Access Framework (the user picks the destination, we write to the returned Uri);
 *  - share: a file in the app cache exposed through FileProvider and sent with the Android Sharesheet.
 */
class ExportService(
    private val context: Context,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val localize: (Context, AppSettings) -> Context,
) {
    suspend fun write(sessionId: String, format: ExportFormat, out: OutputStream) = withContext(Dispatchers.IO) {
        val session = repository.get(sessionId) ?: error("Session not found")
        val s = settings.settings.first()
        when (format) {
            ExportFormat.CSV -> CsvExporter(s.csvFormat).write(session, out) { from, limit ->
                repository.samplesPage(sessionId, from, limit)
            }
            ExportFormat.PDF -> {
                val chart = repository.chartSamples(sessionId, session.sampleCount)
                val agg = repository.channelAggregates(sessionId)
                PdfReport(localize(context, s), DisplayUnits(s)).write(session, agg, chart, out)
            }
        }
    }

    /** Writes the export to the cache and returns a content Uri for the Sharesheet. */
    suspend fun createShareUri(sessionId: String, format: ExportFormat): Uri = withContext(Dispatchers.IO) {
        val session = repository.get(sessionId) ?: error("Session not found")
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
        val file = File(dir, fileName(session, format))
        file.outputStream().use { write(sessionId, format, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri, format: ExportFormat, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** Removes cached exports (also used by "delete all data"). */
    fun clearCache() {
        File(context.cacheDir, EXPORT_DIR).listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val EXPORT_DIR = "exports"

        fun fileName(session: SessionEntity, format: ExportFormat): String {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                .format(java.util.Date(session.startedAtMs))
            val safe = session.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "session" }
            return "SensorLab_${safe}_$stamp.${format.extension}"
        }
    }
}
