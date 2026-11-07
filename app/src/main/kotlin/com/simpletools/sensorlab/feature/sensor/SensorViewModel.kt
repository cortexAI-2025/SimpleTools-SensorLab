package com.simpletools.sensorlab.feature.sensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.core.measurement.FieldVariationTracker
import com.simpletools.sensorlab.core.measurement.KindTransform
import com.simpletools.sensorlab.core.measurement.MultiStats
import com.simpletools.sensorlab.core.measurement.RecorderState
import com.simpletools.sensorlab.core.measurement.SampleWindow
import com.simpletools.sensorlab.core.measurement.SamplingRateEstimator
import com.simpletools.sensorlab.core.measurement.SessionRecorder
import com.simpletools.sensorlab.core.measurement.StoragePolicy
import com.simpletools.sensorlab.core.measurement.WindowedRms
import com.simpletools.sensorlab.core.sensors.RawSample
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Live measurement + optional recording for one sensor module.
 *
 * Acquisition and rendering are decoupled: samples are processed at the sensor rate (on the main
 * thread, a few dozen per second), while the UI state is republished at [UI_FPS].
 * Sensors run only while the screen is started: the listener is unregistered in [onStop].
 */
class SensorViewModel(private val kind: SensorKind, private val container: AppContainer) : ViewModel() {

    /** PRO check at the moment of the action: the UI already hides the control, this is the second lock. */
    private val isPro: Boolean get() = container.license.state.value.isPro


    private val info = container.sensorSource.inventory().first { it.kind == kind }
    private val _ui = MutableStateFlow(
        SensorUiState(info, status = if (info.available) LiveStatus.STOPPED else LiveStatus.UNAVAILABLE),
    )
    val ui: StateFlow<SensorUiState> = _ui.asStateFlow()

    private var settings = AppSettings()
    private val transform = KindTransform(info)
    private val stats = MultiStats(kind.channelCount)
    private val window = SampleWindow(kind.channelCount)
    private val rate = SamplingRateEstimator()
    private val fieldTracker = FieldVariationTracker()
    private var vibrationRms = WindowedRms(1_000_000_000L)
    private var vibrationRmsWindowS = 1
    private var vibrationPeak = Double.NaN

    private var recorder: SessionRecorder? = null
    private var streamJob: Job? = null
    private var tickJob: Job? = null
    private var runningPeriodUs = -1

    private var t0Ns = Long.MIN_VALUE
    private var lastTsNs = 0L
    private var latest: FloatArray? = null
    private var latestScalar = Float.NaN
    private var latestAccuracy = -1
    private var sawSample = false
    private var started = false
    private var errored = false

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s ->
                settings = s
                transform.vibrationCutoffHz = s.vibrationCutoffHz
                if (s.vibrationRmsWindowS != vibrationRmsWindowS) {
                    vibrationRmsWindowS = s.vibrationRmsWindowS
                    vibrationRms = WindowedRms(vibrationRmsWindowS * 1_000_000_000L)
                }
                if (started && !errored && s.sampling.periodUs != runningPeriodUs) startStream()
                publish()
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    fun onStart() {
        if (!info.available || started) return
        started = true
        errored = false
        startStream()
        startTicker()
    }

    /** [configChange]: the activity is only being recreated (rotation) — keep everything running. */
    fun onStop(configChange: Boolean) {
        if (configChange || !started) return
        started = false
        recorder?.let { if (it.state == RecorderState.RECORDING) it.pause() }
        streamJob?.cancel(); streamJob = null
        tickJob?.cancel(); tickJob = null
        _ui.update { it.copy(status = if (info.available) LiveStatus.STOPPED else LiveStatus.UNAVAILABLE) }
        publish()
    }

    override fun onCleared() {
        // Leaving the screen with a recording in progress: persist what we have (best effort).
        val r = recorder
        if (r != null && r.state != RecorderState.FINISHED && r.state != RecorderState.IDLE) {
            container.appScopeLaunch { r.finish() }
        }
        super.onCleared()
    }

    // ---- acquisition -----------------------------------------------------------------------

    private fun startStream() {
        streamJob?.cancel()
        runningPeriodUs = settings.sampling.periodUs
        sawSample = false
        // New time origin => the chart history of the previous origin must go too.
        rate.reset(); transform.reset(); window.clear(); t0Ns = Long.MIN_VALUE
        streamJob = viewModelScope.launch {
            container.sensorSource.stream(info, runningPeriodUs)
                .catch {
                    errored = true
                    _ui.update { s -> s.copy(status = LiveStatus.ERROR) }
                }
                .collect { handle(it) }
        }
        _ui.update { it.copy(status = LiveStatus.WAITING) }
    }

    private suspend fun handle(raw: RawSample) {
        val values = transform.apply(raw) ?: return
        val scalar = kind.scalarOf(values)
        if (t0Ns == Long.MIN_VALUE) t0Ns = raw.timestampNs
        val tS = (raw.timestampNs - t0Ns) / 1e9
        lastTsNs = raw.timestampNs
        sawSample = true
        latest = values; latestScalar = scalar; latestAccuracy = raw.accuracy
        window.add(tS, values, settings.chartWindowS.toDouble())
        rate.add(raw.timestampNs)
        stats.add(values, scalar)
        when (kind) {
            SensorKind.MAGNETOMETER -> fieldTracker.add(raw.timestampNs, scalar.toDouble())
            SensorKind.VIBRATION -> {
                lastRms = vibrationRms.add(raw.timestampNs, scalar.toDouble())
                vibrationPeak = if (vibrationPeak.isNaN()) scalar.toDouble() else max(vibrationPeak, scalar.toDouble())
            }
            else -> Unit
        }
        recorder?.onSample(raw.timestampNs, values, scalar)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                publish()
                delay(1000L / UI_FPS)
            }
        }
    }

    private fun publish() {
        val now = if (t0Ns == Long.MIN_VALUE) 0.0 else (lastTsNs - t0Ns) / 1e9
        val rec = recorder
        _ui.update { cur ->
            val status = when {
                !info.available -> LiveStatus.UNAVAILABLE
                errored -> LiveStatus.ERROR
                !started -> LiveStatus.STOPPED
                sawSample -> LiveStatus.LIVE
                else -> LiveStatus.WAITING
            }
            cur.copy(
                status = status,
                values = latest,
                scalar = latestScalar,
                accuracy = latestAccuracy,
                observedHz = rate.hz,
                elapsedS = now,
                window = if (started) window.snapshot(now) else cur.window,
                windowS = settings.chartWindowS,
                channelStats = stats.channels.map { it.snapshot() },
                scalarStats = stats.scalar.snapshot(),
                fieldLevel = fieldTracker.levelOf(kotlin.math.abs(fieldTracker.deviationUt)),
                fieldDeviationUt = fieldTracker.deviationUt,
                vibrationRms = lastRms,
                vibrationPeak = vibrationPeak,
                calibrated = transform.reference != null,
                recording = RecordingUi(
                    state = rec?.state ?: RecorderState.IDLE,
                    elapsedMs = rec?.elapsedMs ?: 0,
                    samples = rec?.sampleCount ?: 0,
                    stopReason = rec?.stopReason,
                    savedSessionId = if (rec?.state == RecorderState.FINISHED) rec.sessionId else null,
                ),
            )
        }
    }

    private var lastRms = Double.NaN

    // ---- user actions ----------------------------------------------------------------------

    fun resetLiveStats() {
        stats.reset(); vibrationPeak = Double.NaN; fieldTracker.reset(); vibrationRms.reset(); lastRms = Double.NaN
        publish()
    }

    fun calibrate() { transform.reference = transform.lastUp; publish() }
    fun resetCalibration() { transform.reference = null; publish() }

    fun startRecording(name: String) {
        if (!isPro) return
        if (!info.available || !started) return
        val cur = recorder
        if (cur != null && cur.state != RecorderState.FINISHED && cur.state != RecorderState.FAILED) return
        val rec = SessionRecorder(
            container.sessions, kind,
            StoragePolicy(minIntervalNs = settings.sampling.periodNs),
        )
        recorder = rec
        val summary = buildString {
            append("requested=${settings.sampling.hz}Hz")
            if (kind == SensorKind.VIBRATION) append("; highpass=${settings.vibrationCutoffHz}Hz; rmsWindow=${settings.vibrationRmsWindowS}s")
            if (kind == SensorKind.INCLINOMETER && transform.reference != null) append("; user-calibrated")
            if (info.usesFallback) append("; source=accelerometer-gravity")
        }
        viewModelScope.launch {
            rec.start(name, info.method, summary)
            publish()
        }
        publish()
    }

    fun pauseRecording() { recorder?.pause(); publish() }
    fun resumeRecording() { recorder?.resume(); publish() }

    fun finishRecording() {
        val rec = recorder ?: return
        viewModelScope.launch {
            rec.finish()
            publish()
        }
    }

    /** After a storage failure: persist what exists (flagged INTERRUPTED) and release the recorder. */
    fun closeFailed() {
        val rec = recorder ?: return
        viewModelScope.launch {
            runCatching { rec.finish() }
            recorder = null
            publish()
        }
    }

    /** Forget a finished recording so a new one can start. */
    fun dismissSaved() { if (recorder?.state == RecorderState.FINISHED || recorder?.state == RecorderState.FAILED) { recorder = null; publish() } }

    companion object {
        const val UI_FPS = 15

        fun factory(kind: SensorKind, container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SensorViewModel(kind, container) as T
        }
    }
}
