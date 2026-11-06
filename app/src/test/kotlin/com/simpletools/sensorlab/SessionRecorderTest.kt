package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.measurement.RecorderState
import com.simpletools.sensorlab.core.measurement.SessionRecorder
import com.simpletools.sensorlab.core.measurement.SessionStatus
import com.simpletools.sensorlab.core.measurement.StopReason
import com.simpletools.sensorlab.core.measurement.StoragePolicy
import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {
    private val ms = 1_000_000L

    private fun recorder(store: InMemoryStore, policy: StoragePolicy = StoragePolicy(batchSize = 10)) =
        SessionRecorder(store, SensorKind.ACCELEROMETER, policy, nowMs = { 1_000L }, newId = { "id-1" })

    private suspend fun SessionRecorder.feed(fromMs: Int, count: Int, stepMs: Int = 10, v: Float = 1f) {
        for (i in 0 until count) onSample((fromMs + i * stepMs) * ms, floatArrayOf(v, 0f, 0f), v)
    }

    @Test fun idleRecorderIgnoresSamples() = runTest {
        val r = recorder(InMemoryStore())
        r.onSample(0, floatArrayOf(1f, 0f, 0f), 1f)
        assertEquals(RecorderState.IDLE, r.state)
        assertEquals(0, r.sampleCount)
    }

    // 12. stop a session
    @Test fun startRecordFinishPersistsEverything() = runTest {
        val store = InMemoryStore()
        val r = recorder(store)
        r.start("Test", "raw", "requested=25Hz")
        assertEquals(RecorderState.RECORDING, r.state)
        r.feed(0, 25)
        val summary = r.finish()
        assertNotNull(summary)
        assertEquals(RecorderState.FINISHED, r.state)
        assertEquals(25, store.rows.size)
        assertEquals(25, summary!!.sampleCount)
        assertEquals(240, summary.durationMs)
        assertEquals(100.0, summary.observedRateHz, 0.5)
        assertEquals(SessionStatus.COMPLETED, store.status)
        assertEquals("id-1", store.created.single().id)
        assertEquals(1.0, summary.stats.mean, 1e-9)
        assertEquals((0 until 25).toList(), store.rows.map { it.idx })
    }

    @Test fun batchedWritesLimitDiskOperations() = runTest {
        val store = InMemoryStore()
        val r = recorder(store, StoragePolicy(batchSize = 100))
        r.start("Batch", "raw", "")
        r.feed(0, 250)
        assertEquals(2, store.appendCalls) // 2 full batches, 50 samples still in memory
        assertEquals(200, store.rows.size)
        r.finish()
        assertEquals(3, store.appendCalls)
        assertEquals(250, store.rows.size)
    }

    // 11. pause and resume
    @Test fun pauseDropsSamplesAndRemovesTheGapFromTheTimeline() = runTest {
        val store = InMemoryStore()
        val r = recorder(store)
        r.start("Pause", "raw", "")
        r.feed(0, 10)            // t = 0..90 ms
        r.pause()
        assertEquals(RecorderState.PAUSED, r.state)
        r.feed(100, 50)          // dropped: paused
        assertEquals(10, r.sampleCount)
        r.resume()
        r.feed(10_000, 10)       // 10 s later
        val s = r.finish()!!
        assertEquals(20, store.rows.size)
        // Active time ~ 19 intervals of 10 ms, not 10 seconds.
        assertTrue("duration ${s.durationMs}", s.durationMs in 180..210)
        // wall-clock keeps the real time, active time does not
        val last = store.rows.last()
        assertTrue(last.wallMs - store.rows.first().wallMs >= 10_000)
        assertTrue(last.tUs < 300_000)
        // timeline is strictly increasing
        assertTrue(store.rows.zipWithNext().all { (a, b) -> b.tUs > a.tUs })
    }

    @Test fun resumeWithoutPauseIsIgnored() = runTest {
        val r = recorder(InMemoryStore())
        r.start("x", "raw", "")
        r.resume()
        assertEquals(RecorderState.RECORDING, r.state)
    }

    // 10. timestamps
    @Test fun duplicateAndBackwardsTimestampsAreRejected() = runTest {
        val store = InMemoryStore()
        val r = recorder(store)
        r.start("ts", "raw", "")
        r.onSample(100 * ms, floatArrayOf(1f, 0f, 0f), 1f)
        r.onSample(100 * ms, floatArrayOf(2f, 0f, 0f), 2f)
        r.onSample(90 * ms, floatArrayOf(3f, 0f, 0f), 3f)
        r.onSample(110 * ms, floatArrayOf(4f, 0f, 0f), 4f)
        r.finish()
        assertEquals(listOf(1f, 4f), store.rows.map { it.scalar })
        assertEquals(0L, store.rows.first().tUs)
        assertEquals(10_000L, store.rows.last().tUs)
    }

    @Test fun minIntervalDecimatesFastSensors() = runTest {
        val store = InMemoryStore()
        val r = recorder(store, StoragePolicy(batchSize = 1000, minIntervalNs = 100 * ms)) // keep ~10 Hz
        r.start("dec", "raw", "")
        r.feed(0, 1000, stepMs = 10)  // 100 Hz for 10 s
        r.finish()
        assertTrue("kept ${store.rows.size}", store.rows.size in 90..115) // ~10 Hz out of 100 Hz
    }

    @Test fun sampleLimitStopsCleanlyAndKeepsData() = runTest {
        val store = InMemoryStore()
        val r = recorder(store, StoragePolicy(maxSamplesPerSession = 30, batchSize = 10))
        r.start("limit", "raw", "")
        r.feed(0, 100)
        assertEquals(RecorderState.FINISHED, r.state)
        assertEquals(StopReason.LIMIT_REACHED, r.stopReason)
        assertEquals(30, store.rows.size)
        assertEquals(SessionStatus.COMPLETED, store.status)
        assertEquals(30, r.finish()!!.sampleCount) // idempotent
    }

    // 20. hardware / storage errors
    @Test fun storageFailureStopsRecordingWithoutCrashAndMarksInterrupted() = runTest {
        val store = InMemoryStore()
        val r = recorder(store, StoragePolicy(batchSize = 10))
        r.start("fail", "raw", "")
        r.feed(0, 10)                 // first batch OK
        store.failAppend = true
        r.feed(100, 10)               // second batch fails
        assertEquals(RecorderState.FAILED, r.state)
        assertEquals(StopReason.STORAGE_ERROR, r.stopReason)
        assertNotNull(r.lastError)
        r.feed(200, 10)               // further samples are ignored, no exception
        val s = r.finish()!!
        assertEquals(SessionStatus.INTERRUPTED, store.status)
        assertEquals(10, s.sampleCount)   // only what was really persisted
        assertEquals(10, store.rows.size)
    }

    @Test fun failureToCreateSessionIsReportedNotThrown() = runTest {
        val failing = object : com.simpletools.sensorlab.core.measurement.SessionStore {
            override suspend fun create(session: com.simpletools.sensorlab.core.measurement.NewSession) = throw java.io.IOException("no space")
            override suspend fun append(sessionId: String, rows: List<com.simpletools.sensorlab.core.measurement.SampleRow>) = Unit
            override suspend fun complete(sessionId: String, summary: com.simpletools.sensorlab.core.measurement.SessionSummary, status: SessionStatus) = Unit
        }
        val r = SessionRecorder(failing, SensorKind.LIGHT)
        r.start("x", "raw", "")
        assertEquals(RecorderState.FAILED, r.state)
        assertNull(r.finish())
    }
}
