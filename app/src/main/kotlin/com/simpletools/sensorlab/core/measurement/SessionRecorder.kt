package com.simpletools.sensorlab.core.measurement

import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class RecorderState { IDLE, RECORDING, PAUSED, FINISHED, FAILED }

enum class SessionStatus { RECORDING, COMPLETED, INTERRUPTED }

/** Explicit, documented storage policy. See docs/ARCHITECTURE.md. */
data class StoragePolicy(
    /** Hard cap per session; recording stops cleanly when reached. */
    val maxSamplesPerSession: Int = 300_000,
    /** Samples buffered in memory before one batched database write. */
    val batchSize: Int = 500,
    /** Minimum spacing between two recorded samples (decimates fast sensors). 0 = keep all. */
    val minIntervalNs: Long = 0,
)

/** One stored row. [tUs] is active recording time (pauses excluded); [wallMs] is the true clock. */
class SampleRow(
    val idx: Int,
    val tUs: Long,
    val wallMs: Long,
    val v0: Float,
    val v1: Float,
    val v2: Float,
    val scalar: Float,
)

data class NewSession(
    val id: String,
    val name: String,
    val kind: SensorKind,
    val method: String,
    val startedAtMs: Long,
    val settingsSummary: String,
)

data class SessionSummary(
    val endedAtMs: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val observedRateHz: Double,
    val stats: StatsSnapshot,
)

/** Persistence port. Implemented by Room in production and by in-memory fakes in tests. */
interface SessionStore {
    suspend fun create(session: NewSession)
    suspend fun append(sessionId: String, rows: List<SampleRow>)
    suspend fun complete(sessionId: String, summary: SessionSummary, status: SessionStatus)
}

enum class StopReason { USER, LIMIT_REACHED, STORAGE_ERROR }

/**
 * State machine: IDLE -> RECORDING <-> PAUSED -> FINISHED, or FAILED when storage breaks.
 *
 * Time handling: samples carry hardware timestamps. While paused, samples are dropped. On resume
 * the silent gap is removed from the active timeline so the curve stays continuous, while the
 * wall-clock column keeps the real time.
 */
class SessionRecorder(
    private val store: SessionStore,
    private val kind: SensorKind,
    private val policy: StoragePolicy = StoragePolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var state: RecorderState = RecorderState.IDLE
        private set
    var sessionId: String? = null
        private set
    var stopReason: StopReason? = null
        private set
    var sampleCount: Int = 0
        private set
    var lastError: Throwable? = null
        private set
    /** Active duration in ms (pauses excluded). */
    val elapsedMs: Long get() = lastElapsedNs / 1_000_000

    private var finalSummary: SessionSummary? = null
    /** Serialises sample handling, batch flushes and finalisation (they all suspend on the store). */
    private val lock = Mutex()
    /** Statistics / duration of what is really persisted: the summary never describes lost samples. */
    private val persistedStats = RunningStats()
    private var persistedElapsedNs = 0L
    private var persistedCount = 0
    private val batch = ArrayList<SampleRow>()
    private var startedAtMs = 0L
    private var firstTs = Long.MIN_VALUE
    private var lastTs = Long.MIN_VALUE
    private var lastAcceptedTs = Long.MIN_VALUE
    private var pausedNs = 0L
    private var resumePending = false
    private var lastElapsedNs = 0L
    private var nominalPeriodNs = 0L

    suspend fun start(name: String, method: String, settingsSummary: String) {
        check(state == RecorderState.IDLE) { "already started" }
        val id = newId()
        startedAtMs = nowMs()
        try {
            store.create(NewSession(id, name, kind, method, startedAtMs, settingsSummary))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e); return
        }
        sessionId = id
        state = RecorderState.RECORDING
    }

    fun pause() {
        if (state == RecorderState.RECORDING) state = RecorderState.PAUSED
    }

    fun resume() {
        if (state == RecorderState.PAUSED) {
            state = RecorderState.RECORDING
            resumePending = true
        }
    }

    /** Feed one transformed sample. Cheap unless a batch is flushed. */
    suspend fun onSample(timestampNs: Long, values: FloatArray, scalar: Float) = lock.withLock {
        handleSample(timestampNs, values, scalar)
    }

    private suspend fun handleSample(timestampNs: Long, values: FloatArray, scalar: Float) {
        if (state != RecorderState.RECORDING) return
        if (firstTs == Long.MIN_VALUE) {
            firstTs = timestampNs
        } else if (timestampNs <= lastTs) {
            return // out-of-order or duplicate timestamp
        }
        if (policy.minIntervalNs > 0 && lastAcceptedTs != Long.MIN_VALUE &&
            timestampNs - lastAcceptedTs < policy.minIntervalNs * 9 / 10
        ) {
            lastTs = timestampNs
            return
        }
        if (resumePending) {
            if (lastAcceptedTs != Long.MIN_VALUE) {
                val gap = timestampNs - lastAcceptedTs
                pausedNs += (gap - nominalPeriodNs).coerceAtLeast(0)
            }
            resumePending = false
        } else if (lastAcceptedTs != Long.MIN_VALUE) {
            val dt = timestampNs - lastAcceptedTs
            nominalPeriodNs = if (nominalPeriodNs == 0L) dt else (nominalPeriodNs * 7 + dt) / 8
        }
        val elapsedNs = timestampNs - firstTs - pausedNs
        lastElapsedNs = elapsedNs
        lastTs = timestampNs
        lastAcceptedTs = timestampNs
        val wall = startedAtMs + (timestampNs - firstTs) / 1_000_000
        batch.add(
            SampleRow(
                sampleCount, elapsedNs / 1000, wall,
                values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f }, scalar,
            ),
        )
        sampleCount++
        if (batch.size >= policy.batchSize) flush()
        if (state == RecorderState.RECORDING && sampleCount >= policy.maxSamplesPerSession) {
            stopReason = StopReason.LIMIT_REACHED
            finishLocked()
        }
    }

    private suspend fun flush() {
        if (batch.isEmpty()) return
        val id = sessionId ?: return
        val rows = ArrayList(batch)
        try {
            store.append(id, rows)
            for (r in rows) persistedStats.add(r.scalar.toDouble())
            persistedElapsedNs = rows.last().tUs * 1000
            persistedCount += rows.size
            batch.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Throwable) {
        lastError = e
        stopReason = StopReason.STORAGE_ERROR
        state = RecorderState.FAILED
    }

    /**
     * Writes the remaining samples and the summary. Safe to call in any state; after a storage
     * failure it makes a best effort to mark the session INTERRUPTED so earlier data stays usable.
     */
    suspend fun finish(): SessionSummary? = lock.withLock { finishLocked() }

    private suspend fun finishLocked(): SessionSummary? {
        val id = sessionId
        if (id == null) return null
        if (state == RecorderState.FINISHED) return finalSummary
        val failed = state == RecorderState.FAILED
        if (!failed) flush()
        // Only persisted rows count (rows left in `batch` exist after a storage failure only).
        val durationMs = persistedElapsedNs / 1_000_000
        val rate = if (persistedCount > 1 && persistedElapsedNs > 0) (persistedCount - 1) / (persistedElapsedNs / 1e9) else 0.0
        val summary = SessionSummary(nowMs(), durationMs, persistedCount, rate, persistedStats.snapshot())
        val wasFailed = state == RecorderState.FAILED
        try {
            store.complete(id, summary, if (wasFailed) SessionStatus.INTERRUPTED else SessionStatus.COMPLETED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            stopReason = StopReason.STORAGE_ERROR
            state = RecorderState.FAILED
            return summary
        }
        if (!wasFailed) {
            state = RecorderState.FINISHED
            finalSummary = summary
        }
        return summary
    }
}
