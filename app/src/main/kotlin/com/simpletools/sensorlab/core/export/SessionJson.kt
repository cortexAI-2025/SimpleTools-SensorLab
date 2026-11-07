package com.simpletools.sensorlab.core.export

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.sensors.SensorKind
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * JSON encoding of sessions, used by the single-session JSON export and by the local backup.
 * Streamed in both directions: memory does not grow with the number of samples.
 *
 * Shape of one session:
 * { "id", "name", "kind", "method", "unit", "started_at_ms", "ended_at_ms", "duration_ms",
 *   "sample_count", "observed_rate_hz", "scalar_min", "scalar_max", "scalar_mean", "scalar_rms",
 *   "status", "note", "settings_summary", "samples": [ { "index", "elapsed_us", "timestamp_ms",
 *   "x", "y", "z", "scalar" } ] }
 * Values are always in the sensor's native unit. "y"/"z" are omitted for single-value sensors.
 */
object SessionJson {
    const val SESSION_FORMAT = "simpletools-sensorlab/session"
    const val BACKUP_FORMAT = "simpletools-sensorlab/backup"
    const val VERSION = 1
    const val PAGE = 2000

    fun writer(out: OutputStream): JsonWriter =
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8))

    suspend fun writeSessionFile(
        out: OutputStream,
        session: SessionEntity,
        page: suspend (sessionId: String, fromIdx: Int, limit: Int) -> List<SampleEntity>,
    ) {
        val w = writer(out)
        w.beginObject()
        w.name("format").value(SESSION_FORMAT)
        w.name("version").value(VERSION.toLong())
        w.name("session")
        writeSession(w, session, page)
        w.endObject()
        w.flush()
    }

    suspend fun writeSession(
        w: JsonWriter,
        s: SessionEntity,
        page: suspend (sessionId: String, fromIdx: Int, limit: Int) -> List<SampleEntity>,
    ) {
        val vector = SensorKind.fromKey(s.kindKey)?.isVector == true
        w.beginObject()
        w.name("id").value(s.id)
        w.name("name").value(s.name)
        w.name("kind").value(s.kindKey)
        w.name("method").value(s.method)
        w.name("unit").value(s.unit)
        w.name("started_at_ms").value(s.startedAtMs)
        w.name("ended_at_ms").nullableLong(s.endedAtMs)
        w.name("duration_ms").value(s.durationMs)
        w.name("sample_count").value(s.sampleCount.toLong())
        w.name("observed_rate_hz").finiteDouble(s.observedRateHz)
        w.name("scalar_min").nullableDouble(s.scalarMin)
        w.name("scalar_max").nullableDouble(s.scalarMax)
        w.name("scalar_mean").nullableDouble(s.scalarMean)
        w.name("scalar_rms").nullableDouble(s.scalarRms)
        w.name("status").value(s.status)
        w.name("note").value(s.note)
        w.name("settings_summary").value(s.settingsSummary)
        w.name("samples").beginArray()
        var from = 0
        while (true) {
            val rows = page(s.id, from, PAGE)
            if (rows.isEmpty()) break
            for (r in rows) writeSample(w, r, vector)
            from = rows.last().idx + 1
            if (rows.size < PAGE) break
        }
        w.endArray()
        w.endObject()
    }

    private fun writeSample(w: JsonWriter, r: SampleEntity, vector: Boolean) {
        w.beginObject()
        w.name("index").value(r.idx.toLong())
        w.name("elapsed_us").value(r.tUs)
        w.name("timestamp_ms").value(r.wallMs)
        w.name("x").finiteDouble(r.v0.toDouble())
        if (vector) {
            w.name("y").finiteDouble(r.v1.toDouble())
            w.name("z").finiteDouble(r.v2.toDouble())
        }
        w.name("scalar").finiteDouble(r.scalar.toDouble())
        w.endObject()
    }

    /** Reads one session object. Samples are returned in file order. */
    fun readSession(r: JsonReader): Pair<SessionEntity, List<SampleEntity>> {
        var id: String? = null
        var name = ""
        var kind = ""
        var method = ""
        var unit = ""
        var started = 0L
        var ended: Long? = null
        var duration = 0L
        var rate = 0.0
        var min: Double? = null
        var max: Double? = null
        var mean: Double? = null
        var rms: Double? = null
        var status = "COMPLETED"
        var note = ""
        var settings = ""
        val samples = ArrayList<SampleEntity>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "name" -> name = r.nextString()
                "kind" -> kind = r.nextString()
                "method" -> method = r.nextString()
                "unit" -> unit = r.nextString()
                "started_at_ms" -> started = r.nextLong()
                "ended_at_ms" -> ended = r.nullableLong()
                "duration_ms" -> duration = r.nextLong()
                "observed_rate_hz" -> rate = r.nullableDouble() ?: 0.0
                "scalar_min" -> min = r.nullableDouble()
                "scalar_max" -> max = r.nullableDouble()
                "scalar_mean" -> mean = r.nullableDouble()
                "scalar_rms" -> rms = r.nullableDouble()
                "status" -> status = r.nextString()
                "note" -> note = r.nextString()
                "settings_summary" -> settings = r.nextString()
                "samples" -> {
                    val sessionId = id ?: throw IllegalArgumentException("session id must precede its samples")
                    r.beginArray()
                    while (r.hasNext()) samples += readSample(r, sessionId)
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val sessionId = id ?: throw IllegalArgumentException("session without id")
        val entity = SessionEntity(
            id = sessionId, name = name, kindKey = kind, method = method, unit = unit,
            startedAtMs = started, endedAtMs = ended, durationMs = duration, sampleCount = samples.size,
            observedRateHz = rate, scalarMin = min, scalarMax = max, scalarMean = mean, scalarRms = rms,
            status = status, note = note, settingsSummary = settings,
        )
        return entity to samples
    }

    private fun readSample(r: JsonReader, sessionId: String): SampleEntity {
        var idx = 0
        var t = 0L
        var wall = 0L
        var x = 0f
        var y = 0f
        var z = 0f
        var scalar = 0f
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "index" -> idx = r.nextInt()
                "elapsed_us" -> t = r.nextLong()
                "timestamp_ms" -> wall = r.nextLong()
                "x" -> x = (r.nullableDouble() ?: 0.0).toFloat()
                "y" -> y = (r.nullableDouble() ?: 0.0).toFloat()
                "z" -> z = (r.nullableDouble() ?: 0.0).toFloat()
                "scalar" -> scalar = (r.nullableDouble() ?: 0.0).toFloat()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return SampleEntity(sessionId = sessionId, idx = idx, tUs = t, wallMs = wall, v0 = x, v1 = y, v2 = z, scalar = scalar)
    }

    fun reader(input: java.io.InputStream): JsonReader = JsonReader(InputStreamReader(input, Charsets.UTF_8))

    private fun JsonWriter.nullableLong(v: Long?): JsonWriter = if (v == null) nullValue() else value(v)

    private fun JsonWriter.nullableDouble(v: Double?): JsonWriter =
        if (v == null || !v.isFinite()) nullValue() else value(v)

    private fun JsonWriter.finiteDouble(v: Double): JsonWriter = if (!v.isFinite()) nullValue() else value(v)

    private fun JsonReader.nullableLong(): Long? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextLong()

    private fun JsonReader.nullableDouble(): Double? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextDouble()
}
