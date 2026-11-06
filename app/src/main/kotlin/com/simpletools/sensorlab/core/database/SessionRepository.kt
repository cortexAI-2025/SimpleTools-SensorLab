package com.simpletools.sensorlab.core.database

import androidx.room.withTransaction
import com.simpletools.sensorlab.core.measurement.NewSession
import com.simpletools.sensorlab.core.measurement.SampleRow
import com.simpletools.sensorlab.core.measurement.SessionStatus
import com.simpletools.sensorlab.core.measurement.SessionStore
import com.simpletools.sensorlab.core.measurement.SessionSummary
import kotlinx.coroutines.flow.Flow

/** Room-backed [SessionStore] plus the read/update API used by the UI. */
class SessionRepository(private val db: AppDatabase) : SessionStore {
    private val dao = db.sessionDao()

    // --- SessionStore (recording) ---
    override suspend fun create(session: NewSession) {
        dao.insertSession(
            SessionEntity(
                id = session.id, name = session.name, kindKey = session.kind.key, method = session.method,
                unit = session.kind.unit, startedAtMs = session.startedAtMs, endedAtMs = null, durationMs = 0,
                sampleCount = 0, observedRateHz = 0.0, scalarMin = null, scalarMax = null, scalarMean = null,
                scalarRms = null, status = SessionStatus.RECORDING.name, note = "",
                settingsSummary = session.settingsSummary,
            ),
        )
    }

    override suspend fun append(sessionId: String, rows: List<SampleRow>) {
        dao.insertSamples(
            rows.map {
                SampleEntity(
                    sessionId = sessionId, idx = it.idx, tUs = it.tUs, wallMs = it.wallMs,
                    v0 = it.v0, v1 = it.v1, v2 = it.v2, scalar = it.scalar,
                )
            },
        )
    }

    override suspend fun complete(sessionId: String, summary: SessionSummary, status: SessionStatus) {
        db.withTransaction {
            val current = dao.get(sessionId) ?: return@withTransaction
            val s = summary.stats
            dao.updateSession(
                current.copy(
                    endedAtMs = summary.endedAtMs, durationMs = summary.durationMs,
                    sampleCount = summary.sampleCount, observedRateHz = summary.observedRateHz,
                    scalarMin = s.min.takeIf { it.isFinite() }, scalarMax = s.max.takeIf { it.isFinite() },
                    scalarMean = s.mean.takeIf { it.isFinite() }, scalarRms = s.rms.takeIf { it.isFinite() },
                    status = status.name,
                ),
            )
        }
    }

    // --- UI API ---
    fun observeAll(): Flow<List<SessionEntity>> = dao.observeAll()
    fun observeLatest(): Flow<SessionEntity?> = dao.observeLatest()
    fun observe(id: String): Flow<SessionEntity?> = dao.observe(id)
    suspend fun get(id: String): SessionEntity? = dao.get(id)
    suspend fun rename(id: String, name: String) = dao.rename(id, name.trim().ifEmpty { "—" })
    suspend fun setNote(id: String, note: String) = dao.setNote(id, note)
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteAll() {
        db.withTransaction { dao.deleteAll() }
        db.openHelper.writableDatabase.execSQL("VACUUM")
    }
    suspend fun recoverInterrupted() = dao.markDanglingInterrupted()
    suspend fun samplesPage(id: String, fromIdx: Int, limit: Int) = dao.samplesPage(id, fromIdx, limit)
    suspend fun chartSamples(id: String, sampleCount: Int, maxPoints: Int = 1500): List<SampleEntity> {
        val stride = ((sampleCount + maxPoints - 1) / maxPoints).coerceAtLeast(1)
        return dao.samplesStrided(id, stride)
    }
    suspend fun channelAggregates(id: String) = dao.channelAggregates(id)
}
