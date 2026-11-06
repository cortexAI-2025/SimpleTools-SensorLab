package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.measurement.NewSession
import com.simpletools.sensorlab.core.measurement.SampleRow
import com.simpletools.sensorlab.core.measurement.SessionStatus
import com.simpletools.sensorlab.core.measurement.SessionStore
import com.simpletools.sensorlab.core.measurement.SessionSummary
import com.simpletools.sensorlab.core.sensors.HardwareSensor
import com.simpletools.sensorlab.core.sensors.RawSample
import com.simpletools.sensorlab.core.sensors.SensorInfo
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.sensors.SensorSource
import com.simpletools.sensorlab.core.sensors.SensorUnavailableException
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * SIMULATED sensor source. It lives in the test source set only and is never packaged in the APK:
 * the production UI can only ever read real hardware.
 */
class SimulatedSensorSource(
    private val present: Set<SensorKind>,
    private val failOnStream: Boolean = false,
) : SensorSource {
    val samples = MutableSharedFlow<RawSample>(extraBufferCapacity = 1024)
    var streamsOpened = 0
    var streamsClosed = 0

    override fun inventory(): List<SensorInfo> = SensorKind.entries.map {
        when {
            it !in present -> SensorInfo(it, false)
            // same rule as the real source: no rotation vector => gravity fallback on the accelerometer
            it == SensorKind.INCLINOMETER ->
                SensorInfo(it, true, SensorKind.ACCELEROMETER.androidType, "Simulated accelerometer", "Test", usesFallback = true, method = "gravity-tilt")
            else -> SensorInfo(it, true, it.androidType, "Simulated ${it.key}", "Test", 0.01f, 100f, 0.1f, 10_000)
        }
    }

    override fun hardwareSensors(): List<HardwareSensor> = emptyList()

    override fun stream(info: SensorInfo, samplingPeriodUs: Int): Flow<RawSample> = flow {
        if (failOnStream) throw SensorUnavailableException("simulated hardware failure")
        streamsOpened++
        try {
            samples.collect { emit(it) }
        } finally {
            streamsClosed++
        }
    }
}

class InMemoryStore : SessionStore {
    val created = mutableListOf<NewSession>()
    val rows = mutableListOf<SampleRow>()
    var summary: SessionSummary? = null
    var status: SessionStatus? = null
    var failAppend = false
    var appendCalls = 0

    override suspend fun create(session: NewSession) { created += session }
    override suspend fun append(sessionId: String, rows: List<SampleRow>) {
        appendCalls++
        if (failAppend) throw android.database.sqlite.SQLiteFullException("disk full (simulated)")
        this.rows += rows
    }
    override suspend fun complete(sessionId: String, summary: SessionSummary, status: SessionStatus) {
        this.summary = summary; this.status = status
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}
