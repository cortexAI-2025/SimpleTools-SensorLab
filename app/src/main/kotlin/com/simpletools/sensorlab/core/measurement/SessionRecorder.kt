package com.simpletools.sensorlab.core.measurement

import com.simpletools.sensorlab.core.sensors.SensorKind
import kotlinx.coroutines.CancellationException
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
    private val stats = RunningStats()
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
    suspend fun onSample(timestampNs: Long, values: FloatArray, scalar: Float) {
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
        stats.add(scalar.toDouble())
        sampleCount++
        if (batch.size >= policy.batchSize) flush()
        if (state == RecorderState.RECORDING && sampleCount >= policy.maxSamplesPerSession) {
            stopReason = StopReason.LIMIT_REACHED
            finish()
        }
    }

    private suspend fun flush() {
        if (batch.isEmpty()) return
        val id = sessionId ?: return
        val rows = ArrayList(batch)
        try {
            store.append(id, rows)
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
    suspend fun finish(): SessionSummary? {
        val id = sessionId
        if (id == null) return null
        if (state == RecorderState.FINISHED) return finalSummary
        val failed = state == RecorderState.FAILED
        if (!failed) flush()
        val durationMs = lastElapsedNs / 1_000_000
        val rate = if (sampleCount > 1 && lastElapsedNs > 0) (sampleCount - 1) / (lastElapsedNs / 1e9) else 0.0
        // Rows still in `batch` were never persisted (only possible after a storage failure).
        val summary = SessionSummary(nowMs(), durationMs, sampleCount - batch.size, rate, stats.snapshot())
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
