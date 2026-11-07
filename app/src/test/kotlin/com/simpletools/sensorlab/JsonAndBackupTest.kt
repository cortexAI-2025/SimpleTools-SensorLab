package com.simpletools.sensorlab

import android.content.Context
import android.util.JsonReader
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simpletools.sensorlab.core.backup.BackupService
import com.simpletools.sensorlab.core.backup.RestoreError
import com.simpletools.sensorlab.core.database.AppDatabase
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.database.SessionRepository
import com.simpletools.sensorlab.core.export.SessionJson
import com.simpletools.sensorlab.core.measurement.SessionRecorder
import com.simpletools.sensorlab.core.measurement.StoragePolicy
import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class JsonAndBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun record(id: String, kind: SensorKind, n: Int): String {
        val r = SessionRecorder(repo, kind, StoragePolicy(batchSize = 40), nowMs = { 1_700_000_000_000 }, newId = { id })
        r.start("Session $id", kind.method, "requested=25Hz")
        for (i in 0 until n) {
            val v = if (kind.isVector) floatArrayOf(i.toFloat(), 2f, -1f) else floatArrayOf(100f + i)
            r.onSample(i * 10_000_000L, v, if (kind.isVector) i + 1f else v[0])
        }
        r.finish()
        return id
    }

    /** Reads a whole single-session file back: format, version and the session. */
    private fun readSessionFile(bytes: ByteArray): Pair<SessionEntity, List<SampleEntity>> {
        val r = SessionJson.reader(ByteArrayInputStream(bytes))
        var session: Pair<SessionEntity, List<SampleEntity>>? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> assertEquals(SessionJson.SESSION_FORMAT, r.nextString())
                "version" -> assertEquals(SessionJson.VERSION, r.nextInt())
                "session" -> session = SessionJson.readSession(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return session!!
    }

    // 17. export: JSON
    @Test fun sessionJsonRoundTripsEveryValue() = runBlocking {
        record("v", SensorKind.ACCELEROMETER, 120)
        val out = ByteArrayOutputStream()
        SessionJson.writeSessionFile(out, repo.get("v")!!) { id, from, limit -> repo.samplesPage(id, from, limit) }
        val (session, samples) = readSessionFile(out.toByteArray())
        val original = repo.get("v")!!
        assertEquals(original.id, session.id)
        assertEquals(original.name, session.name)
        assertEquals(original.kindKey, session.kindKey)
        assertEquals(original.unit, session.unit)
        assertEquals(original.durationMs, session.durationMs)
        assertEquals(120, samples.size)
        val stored = repo.samplesPage("v", 0, 1000)
        assertEquals(stored.map { it.idx }, samples.map { it.idx })
        assertEquals(stored[7].v0, samples[7].v0, 0f)
        assertEquals(stored[7].v2, samples[7].v2, 0f)
        assertEquals(stored[7].scalar, samples[7].scalar, 0f)
    }

    @Test fun singleValueSensorsWriteOnlyTheX() = runBlocking {
        record("light", SensorKind.LIGHT, 10)
        val out = ByteArrayOutputStream()
        SessionJson.writeSessionFile(out, repo.get("light")!!) { id, from, limit -> repo.samplesPage(id, from, limit) }
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("\"x\""))
        assertFalse("no y axis for a light sensor", text.contains("\"y\""))
        assertFalse(text.contains("\"z\""))
    }

    @Test fun nonFiniteValuesAreWrittenAsNullNotAsInvalidJson() = runBlocking {
        val s = SessionEntity(
            "nan", "NaN test", "light", "raw", "lx", 1L, null, 0L, 1, 0.0,
            Double.NaN, null, null, null, "COMPLETED", "", "",
        )
        val nanSample = SampleEntity(sessionId = "nan", idx = 0, tUs = 0, wallMs = 1, v0 = Float.NaN, v1 = 0f, v2 = 0f, scalar = Float.NaN)
        val out = ByteArrayOutputStream()
        var served = false
        SessionJson.writeSessionFile(out, s) { _, _, _ -> if (served) emptyList() else { served = true; listOf(nanSample) } }
        val (_, samples) = readSessionFile(out.toByteArray())
        assertEquals(1, samples.size)
        assertTrue(out.toString(Charsets.UTF_8.name()).contains("null"))
    }

    // 14. restore + backup
    @Test fun backupThenRestoreRecreatesEverySession() = runBlocking {
        record("a", SensorKind.ACCELEROMETER, 120)
        record("b", SensorKind.LIGHT, 30)
        val backup = BackupService(repo)
        val bytes = ByteArrayOutputStream().also { backup.write(it) }.toByteArray()

        repo.deleteAll()
        assertTrue(repo.observeAll().first().isEmpty())

        val report = backup.restore(ByteArrayInputStream(bytes))
        assertEquals(2, report.imported)
        assertEquals(0, report.skipped)
        assertNull(report.error)
        assertEquals(120, repo.get("a")!!.sampleCount)
        assertEquals(30, repo.get("b")!!.sampleCount)
        assertEquals(120, repo.samplesPage("a", 0, 1000).size)
        assertEquals(1.0, repo.get("a")!!.scalarMin!!, 1e-9)
    }

    @Test fun restoringTwiceSkipsWhatIsAlreadyThere() = runBlocking {
        record("a", SensorKind.ACCELEROMETER, 60)
        val backup = BackupService(repo)
        val bytes = ByteArrayOutputStream().also { backup.write(it) }.toByteArray()
        val second = backup.restore(ByteArrayInputStream(bytes))
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
        assertEquals(60, repo.samplesPage("a", 0, 1000).size)
    }

    @Test fun aSessionRecordingDuringTheBackupIsRestoredAsInterrupted() = runBlocking {
        record("a", SensorKind.LIGHT, 5)
        val bytes = ByteArrayOutputStream().also { BackupService(repo).write(it) }.toByteArray()
        val patched = String(bytes, Charsets.UTF_8).replaceFirst("\"status\":\"COMPLETED\"", "\"status\":\"RECORDING\"")
        repo.deleteAll()
        BackupService(repo).restore(ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)))
        assertEquals("INTERRUPTED", repo.get("a")!!.status)
    }

    @Test fun unknownFormatIsRefusedWithoutTouchingTheDatabase() = runBlocking {
        val report = BackupService(repo).restore(
            ByteArrayInputStream("""{"format":"someone-else","version":1,"sessions":[]}""".toByteArray()),
        )
        assertEquals(RestoreError.UNSUPPORTED, report.error)
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test fun newerBackupVersionIsRefused() = runBlocking {
        val report = BackupService(repo).restore(
            ByteArrayInputStream("""{"format":"simpletools-sensorlab/backup","version":99,"sessions":[]}""".toByteArray()),
        )
        assertEquals(RestoreError.UNSUPPORTED, report.error)
    }

    @Test fun damagedFileIsReportedAndNeverLeavesAHalfWrittenSession() = runBlocking {
        record("a", SensorKind.ACCELEROMETER, 120)
        val full = ByteArrayOutputStream().also { BackupService(repo).write(it) }.toByteArray()
        repo.deleteAll()
        val cut = full.copyOf((full.size * 0.6).toInt())
        val report = BackupService(repo).restore(ByteArrayInputStream(cut))
        assertEquals(RestoreError.CORRUPT, report.error)
        // Whatever was imported is complete: each session is all-or-nothing.
        repo.listAll().forEach { s ->
            assertEquals(s.sampleCount, repo.samplesPage(s.id, 0, 100_000).size)
        }
    }

    @Test fun backupOfAnEmptyDeviceIsValidAndRestoresNothing() = runBlocking {
        val bytes = ByteArrayOutputStream().also { assertEquals(0, BackupService(repo).write(it)) }.toByteArray()
        val report = BackupService(repo).restore(ByteArrayInputStream(bytes))
        // An empty backup is a valid backup: it has its sessions array and restores nothing.
        assertEquals(0, report.imported)
        assertNull(report.error)
        assertTrue(String(bytes, Charsets.UTF_8).contains("\"sessions\":[]"))
    }

    @Test fun jsonReaderIsAvailableForTheStreamingParser() {
        val r = JsonReader(java.io.StringReader("""{"a":1}"""))
        r.beginObject(); assertEquals("a", r.nextName()); assertEquals(1, r.nextInt()); r.endObject()
    }
}
