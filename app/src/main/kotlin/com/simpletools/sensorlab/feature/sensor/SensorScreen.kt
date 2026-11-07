package com.simpletools.sensorlab.feature.sensor

import android.hardware.SensorManager
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.charts.ChartSeries
import com.simpletools.sensorlab.core.charts.LineChart
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.measurement.FieldVariationLevel
import com.simpletools.sensorlab.core.measurement.LightCategory
import com.simpletools.sensorlab.core.measurement.RecorderState
import com.simpletools.sensorlab.core.measurement.StatsSnapshot
import com.simpletools.sensorlab.core.measurement.StopReason
import com.simpletools.sensorlab.core.measurement.Units
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.ui.BigButton
import com.simpletools.sensorlab.ui.ChartColors
import com.simpletools.sensorlab.ui.LabeledValue
import com.simpletools.sensorlab.ui.LockedCard
import com.simpletools.sensorlab.ui.ProPaywallDialog
import com.simpletools.sensorlab.ui.MessageBox
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

@Composable
fun SensorScreen(kind: SensorKind, container: AppContainer, onOpenSession: (String) -> Unit) {
    val vm: SensorViewModel = viewModel(key = "sensor-${kind.key}", factory = SensorViewModel.factory(kind, container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val units = remember(settings) { DisplayUnits(settings) }
    // LocalActivity, not LocalContext: the context is wrapped to apply the in-app language.
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val pro by container.license.state.collectAsStateWithLifecycle()
    var showPaywall by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop(configChange = activity?.isChangingConfigurations == true) }

    // Keep the screen awake only while a measurement screen is visible (foreground measuring in V1).
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(kind.nameRes), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(kind.descriptionRes), style = MaterialTheme.typography.bodyMedium)

        if (!state.info.available) {
            MessageBox(stringResource(unavailableMessage(kind)), isError = true)
            return@Column
        }
        if (state.info.usesFallback) MessageBox(stringResource(R.string.sensor_fallback_note))

        when (state.status) {
            LiveStatus.ERROR -> MessageBox(stringResource(R.string.sensor_error_hw), isError = true)
            LiveStatus.WAITING -> MessageBox(stringResource(R.string.sensor_waiting))
            else -> Unit
        }

        LiveValuesCard(kind, state, units, settings)
        KindExtras(kind, state, units, vm)

        ChartCard(kind, state, units, settings) { w -> scope.launch { container.settings.update { it.copy(chartWindowS = w) } } }
        StatsCard(kind, state, units) { vm.resetLiveStats() }
        RecordingCard(kind, state, vm, onOpenSession, isPro = pro.isPro, onUnlock = { showPaywall = true })
        InfoCard(state, kind, units)
    }

    if (showPaywall) ProPaywallDialog(container.license) { showPaywall = false }
}

private fun unavailableMessage(kind: SensorKind) = when (kind) {
    SensorKind.AMBIENT_TEMPERATURE -> R.string.sensor_temp_unavailable
    SensorKind.GYROSCOPE -> R.string.sensor_gyro_unavailable
    SensorKind.PRESSURE -> R.string.sensor_baro_unavailable
    else -> R.string.sensor_unavailable_generic
}

@Composable
fun channelName(kind: SensorKind, index: Int): String = when (kind) {
    SensorKind.INCLINOMETER -> stringResource(
        when (index) { 0 -> R.string.ch_pitch; 1 -> R.string.ch_roll; else -> R.string.ch_tilt },
    )
    else -> kind.channelLabels[index]
}

@Composable
private fun LiveValuesCard(kind: SensorKind, s: SensorUiState, units: DisplayUnits, settings: AppSettings) {
    SectionCard(title = stringResource(R.string.live_values)) {
        val v = s.values
        if (kind.isVector) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 3) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(channelName(kind, i), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            v?.let { units.format(kind, it[i].toDouble()) } ?: "—",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.live_unit_is, units.unit(kind)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (kind != SensorKind.INCLINOMETER) {
                LabeledValue(
                    stringResource(R.string.live_norm),
                    if (v == null) "—" else "${units.format(kind, s.scalar.toDouble())} ${units.unit(kind)}",
                )
            }
            if (kind == SensorKind.GYROSCOPE && v != null) {
                val other = if (units.unit(kind) == "rad/s") "%.1f °/s".format(Units.radToDeg(s.scalar.toDouble()))
                else "%.3f rad/s".format(s.scalar.toDouble())
                LabeledValue(stringResource(R.string.live_other_unit), other)
            }
        } else {
            Text(
                if (v == null) "—" else "${units.format(kind, s.scalar.toDouble())} ${units.unit(kind)}",
                style = MaterialTheme.typography.displaySmall,
            )
            if (v != null) {
                when (kind) {
                    SensorKind.PRESSURE -> LabeledValue(
                        stringResource(R.string.live_other_unit),
                        if (settings.pressureUnit == com.simpletools.sensorlab.core.settings.PressureUnit.HPA)
                            "%.1f mmHg".format(Units.hpaToMmHg(s.scalar.toDouble()))
                        else "%.2f hPa".format(s.scalar.toDouble()),
                    )
                    SensorKind.AMBIENT_TEMPERATURE -> LabeledValue(
                        stringResource(R.string.live_other_unit),
                        if (settings.temperatureUnit == com.simpletools.sensorlab.core.settings.TemperatureUnit.CELSIUS)
                            "%.1f °F".format(Units.celsiusToFahrenheit(s.scalar.toDouble()))
                        else "%.1f °C".format(Units.fahrenheitToCelsius(s.scalar.toDouble())),
                    )
                    else -> Unit
                }
            }
        }
        LabeledValue(stringResource(R.string.live_rate), s.observedHz?.let { "%.1f Hz".format(it) } ?: "—")
        if (s.accuracy >= 0) LabeledValue(stringResource(R.string.live_accuracy), stringResource(accuracyLabel(s.accuracy)))
    }
}

@Composable
private fun KindExtras(kind: SensorKind, s: SensorUiState, units: DisplayUnits, vm: SensorViewModel) {
    when (kind) {
        SensorKind.ACCELEROMETER -> MessageBox(stringResource(R.string.accel_with_gravity))
        SensorKind.LINEAR_ACCELERATION -> MessageBox(stringResource(R.string.accel_without_gravity))
        SensorKind.MAGNETOMETER -> SectionCard(title = stringResource(R.string.mag_variation_title)) {
            val level = when (s.fieldLevel) {
                FieldVariationLevel.STABLE -> R.string.mag_level_stable
                FieldVariationLevel.MODERATE -> R.string.mag_level_moderate
                FieldVariationLevel.STRONG -> R.string.mag_level_strong
            }
            val color = when (s.fieldLevel) {
                FieldVariationLevel.STABLE -> MaterialTheme.colorScheme.primary
                FieldVariationLevel.MODERATE -> Color(0xFFB26A00)
                FieldVariationLevel.STRONG -> MaterialTheme.colorScheme.error
            }
            Text(stringResource(level), style = MaterialTheme.typography.titleMedium, color = color)
            LabeledValue(stringResource(R.string.mag_deviation), "%+.1f µT".format(s.fieldDeviationUt))
            LabeledValue(stringResource(R.string.mag_accuracy), stringResource(accuracyLabel(s.accuracy)))
            Text(stringResource(R.string.mag_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
        SensorKind.LIGHT -> SectionCard(title = stringResource(R.string.light_category_title)) {
            if (s.values != null) {
                Text(stringResource(lightCategoryLabel(LightCategory.of(s.scalar.toDouble()))), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.light_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
        SensorKind.PRESSURE -> MessageBox(stringResource(R.string.baro_no_altitude))
        SensorKind.PROXIMITY -> MessageBox(stringResource(R.string.proximity_note))
        SensorKind.INCLINOMETER -> InclinometerCard(s, vm)
        SensorKind.VIBRATION -> SectionCard(title = stringResource(R.string.vib_title)) {
            LabeledValue(stringResource(R.string.vib_rms), if (s.vibrationRms.isNaN()) "—" else "%.4f m/s²".format(s.vibrationRms))
            LabeledValue(stringResource(R.string.vib_peak), if (s.vibrationPeak.isNaN()) "—" else "%.4f m/s²".format(s.vibrationPeak))
            LabeledValue(stringResource(R.string.vib_duration), "%.1f s".format(s.elapsedS))
            Text(stringResource(R.string.vib_filter_doc), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.vib_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
}

private fun accuracyLabel(a: Int) = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.string.acc_high
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.string.acc_medium
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> R.string.acc_low
    SensorManager.SENSOR_STATUS_UNRELIABLE -> R.string.acc_unreliable
    else -> R.string.acc_unknown
}

private fun lightCategoryLabel(c: LightCategory) = when (c) {
    LightCategory.VERY_DARK -> R.string.light_very_dark
    LightCategory.DARK -> R.string.light_dark
    LightCategory.DIM_INDOOR -> R.string.light_dim_indoor
    LightCategory.NORMAL_INDOOR -> R.string.light_normal_indoor
    LightCategory.BRIGHT_INDOOR -> R.string.light_bright_indoor
    LightCategory.OVERCAST_DAY -> R.string.light_overcast
    LightCategory.FULL_DAYLIGHT -> R.string.light_daylight
}

@Composable
private fun InclinometerCard(s: SensorUiState, vm: SensorViewModel) {
    SectionCard(title = stringResource(R.string.incl_title)) {
        val pitch = s.values?.get(0) ?: 0f
        val roll = s.values?.get(1) ?: 0f
        BubbleLevel(pitch, roll, s.values != null)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton(stringResource(R.string.incl_calibrate), { vm.calibrate() }, Modifier.weight(1f), enabled = s.values != null)
            BigButton(stringResource(R.string.incl_reset), { vm.resetCalibration() }, Modifier.weight(1f), enabled = s.calibrated, outlined = true)
        }
        if (s.calibrated) Text(stringResource(R.string.incl_calibrated_on), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.incl_calibration_doc), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BubbleLevel(pitchDeg: Float, rollDeg: Float, live: Boolean) {
    val ring = MaterialTheme.colorScheme.outline
    val bubble = MaterialTheme.colorScheme.primary
    val flat = live && abs(pitchDeg) < 1f && abs(rollDeg) < 1f
    val desc = stringResource(R.string.incl_bubble_desc, "%.1f".format(pitchDeg), "%.1f".format(rollDeg))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).semantics { contentDescription = desc },
        ) {
            val c = center
            val r = min(size.width, size.height) / 2f - 4.dp.toPx()
            drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(ring, r / 3f, c, style = Stroke(1.dp.toPx()))
            drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            val maxAngle = 20f
            val k = (r * 0.85f) / maxAngle
            var bx = -rollDeg * k
            var by = -pitchDeg * k
            val len = kotlin.math.hypot(bx, by)
            val limit = r * 0.85f
            if (len > limit) { bx *= limit / len; by *= limit / len }
            drawCircle(if (flat) Color(0xFF30A46C) else bubble, r * 0.12f, Offset(c.x + bx, c.y + by))
        }
    }
}

@Composable
private fun ChartCard(
    kind: SensorKind, s: SensorUiState, units: DisplayUnits, settings: AppSettings, onWindow: (Int) -> Unit,
) {
    SectionCard(title = stringResource(R.string.chart_title)) {
        val w = s.window
        val series = remember(w, units) {
            if (w == null) emptyList()
            else w.channels.mapIndexed { i, arr ->
                ChartSeries(
                    label = kind.channelLabels[i],
                    color = when {
                        kind.channelCount == 1 -> ChartColors.single
                        i == 0 -> ChartColors.x
                        i == 1 -> ChartColors.y
                        else -> ChartColors.z
                    },
                    t = w.t,
                    v = FloatArray(arr.size) { units.convert(kind, arr[it]) },
                )
            }
        }
        val names = (0 until kind.channelCount).map { channelName(kind, it) }
        LineChart(
            series = series,
            unit = units.unit(kind),
            xFrom = -s.windowS.toFloat(),
            xTo = 0f,
            description = stringResource(R.string.chart_desc, stringResource(kind.nameRes), units.unit(kind)),
        )
        if (kind.channelCount > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                names.forEachIndexed { i, n ->
                    val c = when (i) { 0 -> ChartColors.x; 1 -> ChartColors.y; else -> ChartColors.z }
                    Text("● $n", color = c, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(stringResource(R.string.chart_window), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 60, 120).forEach { sec ->
                FilterChip(selected = settings.chartWindowS == sec, onClick = { onWindow(sec) }, label = { Text("$sec s") })
            }
        }
    }
}

@Composable
private fun StatsCard(kind: SensorKind, s: SensorUiState, units: DisplayUnits, onReset: () -> Unit) {
    SectionCard(title = stringResource(R.string.stats_title)) {
        if (kind.channelCount == 3) {
            s.channelStats.forEachIndexed { i, st -> StatBlock(channelName(kind, i), st, kind, units, showRms = kind != SensorKind.INCLINOMETER) }
        }
        val label = when {
            kind == SensorKind.INCLINOMETER -> stringResource(R.string.ch_tilt)
            kind.isVector -> stringResource(R.string.live_norm)
            else -> stringResource(R.string.stat_value)
        }
        if (kind.channelCount != 3 || kind != SensorKind.INCLINOMETER) {
            StatBlock(label, s.scalarStats, kind, units, showRms = kind != SensorKind.AMBIENT_TEMPERATURE && kind != SensorKind.PRESSURE && kind != SensorKind.LIGHT)
        }
        BigButton(stringResource(R.string.stats_reset), onReset, Modifier.fillMaxWidth(), outlined = true)
    }
}

@Composable
private fun StatBlock(label: String, st: StatsSnapshot, kind: SensorKind, units: DisplayUnits, showRms: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell(stringResource(R.string.stat_min), units.format(kind, st.min))
            StatCell(stringResource(R.string.stat_max), units.format(kind, st.max))
            StatCell(stringResource(R.string.stat_mean), units.format(kind, st.mean))
            if (showRms) StatCell(stringResource(R.string.stat_rms), units.format(kind, st.rms))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecordingCard(
    kind: SensorKind, s: SensorUiState, vm: SensorViewModel, onOpenSession: (String) -> Unit,
    isPro: Boolean, onUnlock: () -> Unit,
) {
    if (!isPro) {
        LockedCard(
            title = stringResource(R.string.rec_title),
            body = stringResource(R.string.locked_body_recording),
            onUnlock = onUnlock,
        )
        return
    }
    val rec = s.recording
    val name = "${stringResource(kind.nameRes)} ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}"
    SectionCard(title = stringResource(R.string.rec_title)) {
        when (rec.state) {
            RecorderState.IDLE -> {
                Text(stringResource(R.string.rec_foreground_note), style = MaterialTheme.typography.bodySmall)
                BigButton(stringResource(R.string.rec_start), { vm.startRecording(name) }, Modifier.fillMaxWidth(), enabled = s.status == LiveStatus.LIVE || s.status == LiveStatus.WAITING)
            }
            RecorderState.RECORDING, RecorderState.PAUSED -> {
                val paused = rec.state == RecorderState.PAUSED
                Text(
                    if (paused) stringResource(R.string.rec_paused) else stringResource(R.string.rec_recording),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (paused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                LabeledValue(stringResource(R.string.rec_elapsed), "%.1f s".format(rec.elapsedMs / 1000.0))
                LabeledValue(stringResource(R.string.rec_samples), "${rec.samples}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    if (paused) BigButton(stringResource(R.string.rec_resume), { vm.resumeRecording() }, Modifier.weight(1f))
                    else BigButton(stringResource(R.string.rec_pause), { vm.pauseRecording() }, Modifier.weight(1f), outlined = true)
                    BigButton(stringResource(R.string.rec_finish), { vm.finishRecording() }, Modifier.weight(1f))
                }
            }
            RecorderState.FINISHED -> {
                Text(stringResource(R.string.rec_saved), style = MaterialTheme.typography.titleMedium)
                if (rec.stopReason == StopReason.LIMIT_REACHED) MessageBox(stringResource(R.string.rec_limit_reached))
                LabeledValue(stringResource(R.string.rec_elapsed), "%.1f s".format(rec.elapsedMs / 1000.0))
                LabeledValue(stringResource(R.string.rec_samples), "${rec.samples}")
                rec.savedSessionId?.let { id ->
                    BigButton(stringResource(R.string.rec_view), { onOpenSession(id) }, Modifier.fillMaxWidth())
                }
                BigButton(stringResource(R.string.rec_new), { vm.dismissSaved() }, Modifier.fillMaxWidth(), outlined = true)
            }
            RecorderState.FAILED -> {
                MessageBox(stringResource(R.string.rec_storage_error), isError = true)
                BigButton(stringResource(R.string.rec_close_failed), { vm.closeFailed() }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InfoCard(s: SensorUiState, kind: SensorKind, units: DisplayUnits) {
    val i = s.info
    SectionCard(title = stringResource(R.string.info_title)) {
        i.name?.let { LabeledValue(stringResource(R.string.info_name), it) }
        i.vendor?.let { LabeledValue(stringResource(R.string.info_vendor), it) }
        i.androidType?.let { LabeledValue(stringResource(R.string.info_type), "$it") }
        i.resolution?.let { LabeledValue(stringResource(R.string.info_resolution), "$it") }
        i.maxRange?.let { LabeledValue(stringResource(R.string.info_range), "$it") }
        i.powerMilliAmp?.let { LabeledValue(stringResource(R.string.info_power), "$it mA") }
        LabeledValue(
            stringResource(R.string.info_max_rate),
            i.maxRateHz?.let { "%.0f Hz".format(it) } ?: stringResource(R.string.info_on_change),
        )
        i.reportingMode?.let { LabeledValue(stringResource(R.string.info_mode), it.name.lowercase()) }
        LabeledValue(stringResource(R.string.info_method), i.method)
        LabeledValue(stringResource(R.string.info_native_unit), kind.unit)
    }
}
