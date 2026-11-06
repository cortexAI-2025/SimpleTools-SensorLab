package com.simpletools.sensorlab.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatest(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("UPDATE sessions SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE sessions SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Sessions left in RECORDING by a crash / kill are kept and flagged. */
    @Query(
        "UPDATE sessions SET status = 'INTERRUPTED', sampleCount = " +
            "(SELECT COUNT(*) FROM samples WHERE sessionId = sessions.id), " +
            "durationMs = COALESCE((SELECT MAX(tUs) / 1000 FROM samples WHERE sessionId = sessions.id), 0) " +
            "WHERE status = 'RECORDING'",
    )
    suspend fun markDanglingInterrupted()

    @Insert
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE sessionId = :id AND idx >= :fromIdx ORDER BY idx LIMIT :limit")
    suspend fun samplesPage(id: String, fromIdx: Int, limit: Int): List<SampleEntity>

    /** Down-sampled series for charts: keeps one sample out of [stride]. */
    @Query("SELECT * FROM samples WHERE sessionId = :id AND (idx % :stride) = 0 ORDER BY idx")
    suspend fun samplesStrided(id: String, stride: Int): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id")
    suspend fun countSamples(id: String): Int

    @Query(
        "SELECT MIN(v0) AS min0, MAX(v0) AS max0, AVG(v0) AS avg0, AVG(v0 * v0) AS msq0, " +
            "MIN(v1) AS min1, MAX(v1) AS max1, AVG(v1) AS avg1, AVG(v1 * v1) AS msq1, " +
            "MIN(v2) AS min2, MAX(v2) AS max2, AVG(v2) AS avg2, AVG(v2 * v2) AS msq2 " +
            "FROM samples WHERE sessionId = :id",
    )
    suspend fun channelAggregates(id: String): ChannelAggregates
}
