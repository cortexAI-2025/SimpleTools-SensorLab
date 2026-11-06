package com.simpletools.sensorlab.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kindKey: String,
    /** Computation method (see SensorKind.method): part of the comparability check. */
    val method: String,
    val unit: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val durationMs: Long,
    val sampleCount: Int,
    val observedRateHz: Double,
    val scalarMin: Double?,
    val scalarMax: Double?,
    val scalarMean: Double?,
    val scalarRms: Double?,
    /** RECORDING, COMPLETED or INTERRUPTED. */
    val status: String,
    val note: String,
    /** Human readable acquisition/filter settings used for this session. */
    val settingsSummary: String,
)

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "idx"], unique = true)],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val idx: Int,
    /** Active recording time in µs (pauses excluded). */
    val tUs: Long,
    /** Wall clock, epoch ms. */
    val wallMs: Long,
    val v0: Float,
    val v1: Float,
    val v2: Float,
    val scalar: Float,
)

/** SQL aggregates for every channel of one session. */
data class ChannelAggregates(
    @ColumnInfo(name = "min0") val min0: Double?, @ColumnInfo(name = "max0") val max0: Double?,
    @ColumnInfo(name = "avg0") val avg0: Double?, @ColumnInfo(name = "msq0") val msq0: Double?,
    @ColumnInfo(name = "min1") val min1: Double?, @ColumnInfo(name = "max1") val max1: Double?,
    @ColumnInfo(name = "avg1") val avg1: Double?, @ColumnInfo(name = "msq1") val msq1: Double?,
    @ColumnInfo(name = "min2") val min2: Double?, @ColumnInfo(name = "max2") val max2: Double?,
    @ColumnInfo(name = "avg2") val avg2: Double?, @ColumnInfo(name = "msq2") val msq2: Double?,
)
