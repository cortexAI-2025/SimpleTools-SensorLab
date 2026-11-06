package com.simpletools.sensorlab.feature.sensor

import com.simpletools.sensorlab.core.measurement.FieldVariationLevel
import com.simpletools.sensorlab.core.measurement.RecorderState
import com.simpletools.sensorlab.core.measurement.StatsSnapshot
import com.simpletools.sensorlab.core.measurement.StopReason
import com.simpletools.sensorlab.core.measurement.WindowSnapshot
import com.simpletools.sensorlab.core.sensors.SensorInfo

enum class LiveStatus { UNAVAILABLE, WAITING, LIVE, ERROR, STOPPED }

data class RecordingUi(
    val state: RecorderState = RecorderState.IDLE,
    val elapsedMs: Long = 0,
    val samples: Int = 0,
    val stopReason: StopReason? = null,
    val savedSessionId: String? = null,
)

data class SensorUiState(
    val info: SensorInfo,
    val status: LiveStatus = LiveStatus.STOPPED,
    /** Latest channel values in native units (null until the first sample). */
    val values: FloatArray? = null,
    val scalar: Float = Float.NaN,
    val accuracy: Int = -1,
    val observedHz: Double? = null,
    val elapsedS: Double = 0.0,
    val window: WindowSnapshot? = null,
    val windowS: Int = 30,
    val channelStats: List<StatsSnapshot> = emptyList(),
    val scalarStats: StatsSnapshot = StatsSnapshot.EMPTY,
    // Kind specific
    val fieldLevel: FieldVariationLevel = FieldVariationLevel.STABLE,
    val fieldDeviationUt: Double = 0.0,
    val vibrationRms: Double = Double.NaN,
    val vibrationPeak: Double = Double.NaN,
    val calibrated: Boolean = false,
    val recording: RecordingUi = RecordingUi(),
)
