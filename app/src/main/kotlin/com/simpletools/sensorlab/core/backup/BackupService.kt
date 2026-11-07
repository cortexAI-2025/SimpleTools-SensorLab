package com.simpletools.sensorlab.core.backup

import com.simpletools.sensorlab.core.database.SessionRepository
import com.simpletools.sensorlab.core.export.SessionJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class RestoreError { UNSUPPORTED, CORRUPT }

/**
 * Result of a restore. Sessions are imported one by one, each in its own transaction:
 * a damaged file leaves the sessions read so far in place and never a half-written session.
 * Sessions whose id already exists on the device are skipped, so restoring twice is harmless.
 */
data class RestoreReport(val imported: Int, val skipped: Int, val error: RestoreError?)

/** Local, offline backup of every session and its samples to one JSON file chosen by the user. */
class BackupService(
    private val repository: SessionRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Writes all sessions. Returns the number of sessions written. The caller closes [out]. */
    suspend fun write(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val sessions = repository.listAll()
        val w = SessionJson.writer(out)
        w.beginObject()
        w.name("format").value(SessionJson.BACKUP_FORMAT)
        w.name("version").value(SessionJson.VERSION.toLong())
        w.name("exported_at_ms").value(nowMs())
        w.name("sessions").beginArray()
        for (s in sessions) {
            SessionJson.writeSession(w, s) { id, from, limit -> repository.samplesPage(id, from, limit) }
        }
        w.endArray()
        w.endObject()
        w.flush()
        sessions.size
    }

    suspend fun restore(input: InputStream): RestoreReport = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        try {
            val r = SessionJson.reader(input)
            var format: String? = null
            var version = -1
            var sawSessions = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "format" -> format = r.nextString()
                    "version" -> version = r.nextInt()
                    "sessions" -> {
                        if (format != SessionJson.BACKUP_FORMAT || version != SessionJson.VERSION) {
                            return@withContext RestoreReport(0, 0, RestoreError.UNSUPPORTED)
                        }
                        r.beginArray()
                        while (r.hasNext()) {
                            val (session, samples) = SessionJson.readSession(r)
                            // A session that was still recording when the backup was made is no longer running.
                            val fixed = if (session.status == "RECORDING") session.copy(status = "INTERRUPTED") else session
                            if (repository.importSession(fixed, samples)) imported++ else skipped++
                        }
                        r.endArray()
                        sawSessions = true
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!sawSessions) RestoreReport(imported, skipped, RestoreError.UNSUPPORTED)
            else RestoreReport(imported, skipped, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestoreReport(imported, skipped, RestoreError.CORRUPT)
        }
    }
}
