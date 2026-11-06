package com.simpletools.sensorlab.feature.sessions

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.charts.ChartSeries
import com.simpletools.sensorlab.core.database.ChannelAggregates
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.export.ExportFormat
import com.simpletools.sensorlab.core.charts.LineChart
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.ui.BigButton
import com.simpletools.sensorlab.ui.ChartColors
import com.simpletools.sensorlab.ui.ConfirmDialog
import com.simpletools.sensorlab.ui.LabeledValue
import com.simpletools.sensorlab.ui.LoadingBox
import com.simpletools.sensorlab.ui.MessageBox
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.ScreenScaffold
import com.simpletools.sensorlab.ui.SectionCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.sqrt

@Composable
fun SessionDetailScreen(
    id: String,
    container: AppContainer,
    onBack: () -> Unit,
    onCompare: (String) -> Unit,
) {
    val vm: SessionDetailViewModel = viewModel(key = "session-$id", factory = SessionDetailViewModel.factory(id, container))
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val units = remember(settings) { DisplayUnits(settings) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    val exportedOk = stringResource(R.string.export_done)
    val exportedFail = stringResource(R.string.export_failed)
    val shareTitle = stringResource(R.string.export_share_title)

    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime)) { uri ->
        if (uri != null) scope.launch { snackbar.showSnackbar(if (writeTo(context, container, id, ExportFormat.CSV, uri)) exportedOk else exportedFail) }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)) { uri ->
        if (uri != null) scope.launch { snackbar.showSnackbar(if (writeTo(context, container, id, ExportFormat.PDF, uri)) exportedOk else exportedFail) }
    }
    fun share(format: ExportFormat) = scope.launch {
        try {
            val uri = container.exports.createShareUri(id, format)
            // The Activity (not the language-wrapped context) launches the Sharesheet.
            (activity ?: context).startActivity(container.exports.shareIntent(uri, format, shareTitle))
        } catch (e: Exception) {
            snackbar.showSnackbar(exportedFail)
        }
    }

    val loaded = state as? DetailState.Loaded
    ScreenScaffold(loaded?.session?.name ?: stringResource(R.string.session_title), onBack, snackbar) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val st = state) {
                DetailState.Loading -> LoadingBox()
                DetailState.NotFound -> MessageBox(stringResource(R.string.session_not_found), isError = true)
                is DetailState.Error -> {
                    MessageBox(stringResource(R.string.session_load_error), isError = true)
                    BigButton(stringResource(R.string.action_retry), { vm.load() })
                }
                is DetailState.Loaded -> {
                    val s = st.session
                    val kind = SensorKind.fromKey(s.kindKey)
                    if (kind == null) {
                        MessageBox(stringResource(R.string.session_unknown_sensor), isError = true)
                        return@Column
                    }
                    SummaryCard(s, kind, units) { renaming = true }
                    StatsTable(kind, s, st.aggregates, units)
                    SectionCard(title = stringResource(R.string.chart_title)) {
                        val chart = st.chart
                        if (chart.size < 2) Text(stringResource(R.string.report_no_chart))
                        else {
                            val t = FloatArray(chart.size) { (chart[it].tUs / 1e6).toFloat() }
                            val series = if (kind.channelCount == 3) listOf(
                                ChartSeries("X", ChartColors.x, t, FloatArray(chart.size) { units.convert(kind, chart[it].v0) }),
                                ChartSeries("Y", ChartColors.y, t, FloatArray(chart.size) { units.convert(kind, chart[it].v1) }),
                                ChartSeries("Z", ChartColors.z, t, FloatArray(chart.size) { units.convert(kind, chart[it].v2) }),
                            ) else listOf(ChartSeries("", ChartColors.single, t, FloatArray(chart.size) { units.convert(kind, chart[it].v0) }))
                            LineChart(series, units.unit(kind), description = stringResource(R.string.chart_desc, s.name, units.unit(kind)))
                        }
                    }
                    NoteCard(s) { vm.saveNote(it) }
                    SectionCard(title = stringResource(R.string.export_title)) {
                        Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            BigButton(stringResource(R.string.export_save_csv), { saveCsv.launch(com.simpletools.sensorlab.core.export.ExportService.fileName(s, ExportFormat.CSV)) }, Modifier.weight(1f), outlined = true)
                            BigButton(stringResource(R.string.export_save_pdf), { savePdf.launch(com.simpletools.sensorlab.core.export.ExportService.fileName(s, ExportFormat.PDF)) }, Modifier.weight(1f), outlined = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            BigButton(stringResource(R.string.export_share_csv), { share(ExportFormat.CSV) }, Modifier.weight(1f))
                            BigButton(stringResource(R.string.export_share_pdf), { share(ExportFormat.PDF) }, Modifier.weight(1f))
                        }
                    }
                    BigButton(stringResource(R.string.compare_with), { onCompare(s.id) }, Modifier.fillMaxWidth(), outlined = true)
                    BigButton(stringResource(R.string.action_delete), { confirmDelete = true }, Modifier.fillMaxWidth(), outlined = true)

                    if (renaming) RenameDialog(s.name, { renaming = false }) { vm.rename(it); renaming = false }
                    if (confirmDelete) ConfirmDialog(
                        stringResource(R.string.delete_session_title),
                        stringResource(R.string.delete_session_msg, s.name),
                        stringResource(R.string.action_delete),
                        onConfirm = { scope.launch { container.sessions.delete(s.id); onBack() } },
                        onDismiss = { confirmDelete = false },
                    )
                }
            }
        }
    }
}

private suspend fun writeTo(context: Context, container: AppContainer, id: String, format: ExportFormat, uri: Uri): Boolean =
    try {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { container.exports.write(id, format, it) }
        true
    } catch (e: Exception) {
        false
    }

@Composable
private fun SummaryCard(s: SessionEntity, kind: SensorKind, units: DisplayUnits, onRename: () -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onRename) { Text(stringResource(R.string.action_rename)) }
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        LabeledValue(stringResource(R.string.report_sensor), stringResource(kind.nameRes))
        LabeledValue(stringResource(R.string.report_date), df.format(Date(s.startedAtMs)))
        s.endedAtMs?.let { LabeledValue(stringResource(R.string.session_ended), df.format(Date(it))) }
        LabeledValue(stringResource(R.string.report_duration), "%.1f s".format(s.durationMs / 1000.0))
        LabeledValue(stringResource(R.string.rec_samples), "${s.sampleCount}")
        LabeledValue(stringResource(R.string.live_rate), "%.1f Hz".format(s.observedRateHz))
        LabeledValue(stringResource(R.string.report_unit), units.unit(kind))
        LabeledValue(stringResource(R.string.report_settings), s.settingsSummary.ifBlank { "—" })
        if (s.status == "INTERRUPTED") Text(stringResource(R.string.session_interrupted_note), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatsTable(kind: SensorKind, s: SessionEntity, a: ChannelAggregates?, units: DisplayUnits) {
    SectionCard(title = stringResource(R.string.stats_title)) {
        val rmsOk = kind != SensorKind.AMBIENT_TEMPERATURE && kind != SensorKind.PRESSURE && kind != SensorKind.LIGHT
        @Composable fun block(label: String, mn: Double?, mx: Double?, avg: Double?, rms: Double?) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                fun f(v: Double?) = v?.let { units.format(kind, it) } ?: "—"
                Cell(stringResource(R.string.stat_min), f(mn)); Cell(stringResource(R.string.stat_max), f(mx))
                Cell(stringResource(R.string.stat_mean), f(avg))
                if (rmsOk) Cell(stringResource(R.string.stat_rms), f(rms))
            }
        }
        if (kind.channelCount == 3 && a != null) {
            val rows = listOf(
                listOf(a.min0, a.max0, a.avg0, a.msq0), listOf(a.min1, a.max1, a.avg1, a.msq1), listOf(a.min2, a.max2, a.avg2, a.msq2),
            )
            kind.channelLabels.forEachIndexed { i, l ->
                val r = rows[i]
                val name = if (kind == SensorKind.INCLINOMETER) stringResource(when (i) { 0 -> R.string.ch_pitch; 1 -> R.string.ch_roll; else -> R.string.ch_tilt }) else l
                block(name, r[0], r[1], r[2], r[3]?.let { sqrt(it) }.takeIf { kind != SensorKind.INCLINOMETER })
            }
        }
        if (kind != SensorKind.INCLINOMETER) {
            block(
                if (kind.isVector) stringResource(R.string.live_norm) else stringResource(R.string.stat_value),
                s.scalarMin, s.scalarMax, s.scalarMean, s.scalarRms,
            )
        }
    }
}

@Composable
private fun Cell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoteCard(s: SessionEntity, onSave: (String) -> Unit) {
    var text by remember(s.id, s.note) { mutableStateOf(s.note) }
    SectionCard(title = stringResource(R.string.report_observations)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(600) },
            label = { Text(stringResource(R.string.note_hint)) },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        BigButton(stringResource(R.string.action_save), { onSave(text) }, Modifier.fillMaxWidth(), enabled = text != s.note, outlined = true)
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
