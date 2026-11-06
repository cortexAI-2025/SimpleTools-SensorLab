package com.simpletools.sensorlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simpletools.sensorlab.core.database.AppDatabase
import com.simpletools.sensorlab.core.database.SessionRepository
import com.simpletools.sensorlab.core.measurement.SessionRecorder
import com.simpletools.sensorlab.core.measurement.StoragePolicy
import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun record(kind: SensorKind = SensorKind.ACCELEROMETER, n: Int = 120, id: String = "s1"): String {
        val r = SessionRecorder(repo, kind, StoragePolicy(batchSize = 50), nowMs = { 1_700_000_000_000 }, newId = { id })
        r.start("Test $id", kind.method, "requested=25Hz")
        for (i in 0 until n) r.onSample(i * 10_000_000L, floatArrayOf(i.toFloat(), 2f, 0f), (i + 1).toFloat())
        r.finish()
        return id
    }

    // 13. Room save
    @Test fun sessionAndSamplesAreSaved() = runBlocking {
        record()
        val s = repo.get("s1")!!
        assertEquals("COMPLETED", s.status)
        assertEquals(120, s.sampleCount)
        assertEquals(120, db.sessionDao().countSamples("s1"))
        assertEquals("accelerometer", s.kindKey)
        assertEquals("m/s²", s.unit)
        assertEquals(1.0, s.scalarMin!!, 1e-9)
        assertEquals(120.0, s.scalarMax!!, 1e-9)
        assertEquals(60.5, s.scalarMean!!, 1e-9)
        assertEquals(1190L, s.durationMs)
        assertEquals(100.0, s.observedRateHz, 0.5)
        assertNotNull(s.endedAtMs)
    }

    // 14. restore
    @Test fun sessionIsRestoredWithItsSamplesInOrder() = runBlocking {
        record()
        val page1 = repo.samplesPage("s1", 0, 100)
        val page2 = repo.samplesPage("s1", 100, 100)
        assertEquals(100, page1.size); assertEquals(20, page2.size)
        assertEquals((0 until 120).toList(), (page1 + page2).map { it.idx })
        assertEquals(5f, page1[5].v0, 0f)
        assertEquals(2f, page1[5].v1, 0f)
        assertEquals(6f, page1[5].scalar, 0f)
        assertEquals("s1", repo.observeLatest().first()!!.id)
        assertEquals(listOf("s1"), repo.observeAll().first().map { it.id })
    }

    @Test fun channelAggregatesComputedInSql() = runBlocking {
        record()
        val a = repo.channelAggregates("s1")
        assertEquals(0.0, a.min0!!, 1e-9); assertEquals(119.0, a.max0!!, 1e-9); assertEquals(59.5, a.avg0!!, 1e-9)
        assertEquals(2.0, a.min1!!, 1e-9); assertEquals(4.0, a.msq1!!, 1e-9)
    }

    @Test fun chartSamplesAreDownsampled() = runBlocking {
        record(n = 300)
        val pts = repo.chartSamples("s1", 300, maxPoints = 100)
        assertTrue(pts.size in 99..101)
        assertEquals(0, pts.first().idx)
    }

    // 15. delete
    @Test fun deletingASessionRemovesItsSamples() = runBlocking {
        record(id = "s1"); record(id = "s2", n = 10)
        repo.delete("s1")
        assertNull(repo.get("s1"))
        assertEquals(0, db.sessionDao().countSamples("s1"))
        assertEquals(10, db.sessionDao().countSamples("s2"))
    }

    @Test fun renameAndNoteArePersisted() = runBlocking {
        record()
        repo.rename("s1", "  Mon test  ")
        repo.setNote("s1", "Sur la table")
        val s = repo.get("s1")!!
        assertEquals("Mon test", s.name); assertEquals("Sur la table", s.note)
    }

    @Test fun deleteAllWipesEverything() = runBlocking {
        record(id = "s1"); record(id = "s2")
        repo.deleteAll()
        assertTrue(repo.observeAll().first().isEmpty())
        assertEquals(0, db.sessionDao().countSamples("s1") + db.sessionDao().countSamples("s2"))
    }

    @Test fun sessionLeftRecordingAfterACrashIsKeptAndFlaggedInterrupted() = runBlocking {
        val r = SessionRecorder(repo, SensorKind.LIGHT, StoragePolicy(batchSize = 10), nowMs = { 5L }, newId = { "crash" })
        r.start("Crash", "raw", "")
        for (i in 0 until 25) r.onSample(i * 20_000_000L, floatArrayOf(i.toFloat()), i.toFloat())
        // process killed here: finish() never called, 20 samples were flushed
        repo.recoverInterrupted()
        val s = repo.get("crash")!!
        assertEquals("INTERRUPTED", s.status)
        assertEquals(20, s.sampleCount)
        assertEquals(20, db.sessionDao().countSamples("crash"))
    }

    // data survives closing and reopening the database file
    @Test fun dataSurvivesReopeningTheDatabaseFile() = runBlocking {
        val name = "persist-test.db"
        context.deleteDatabase(name)
        var file = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        var r = SessionRepository(file)
        SessionRecorder(r, SensorKind.PRESSURE, StoragePolicy(batchSize = 5), newId = { "p1" }).apply {
            start("Persist", "raw", "")
            for (i in 0 until 12) onSample(i * 100_000_000L, floatArrayOf(1013f + i), 1013f + i)
            finish()
        }
        file.close()
        file = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        r = SessionRepository(file)
        assertEquals(12, r.get("p1")!!.sampleCount)
        assertEquals(12, r.samplesPage("p1", 0, 100).size)
        file.close()
        context.deleteDatabase(name)
        Unit
    }
}
