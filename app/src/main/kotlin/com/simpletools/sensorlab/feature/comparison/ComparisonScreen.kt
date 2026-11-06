package com.simpletools.sensorlab.feature.comparison

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.charts.ChartSeries
import com.simpletools.sensorlab.core.charts.LineChart
import com.simpletools.sensorlab.core.database.SampleEntity
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.ui.ChartColors
import com.simpletools.sensorlab.ui.LoadingBox
import com.simpletools.sensorlab.ui.MessageBox
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(container: AppContainer, initialA: String?) {
    val sessions: List<SessionEntity>? by remember { container.sessions.observeAll() }.collectAsState(initial = null)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val units = remember(settings) { DisplayUnits(settings) }
    var aId by remember { mutableStateOf(initialA) }
    var bId by remember { mutableStateOf<String?>(null) }
    var samplesA by remember { mutableStateOf<List<SampleEntity>?>(null) }
    var samplesB by remember { mutableStateOf<List<SampleEntity>?>(null) }

    val all = sessions
    val a = all?.firstOrNull { it.id == aId }
    val candidates = if (a != null && all != null) SessionComparison.candidatesFor(a, all) else emptyList()
    val b = candidates.firstOrNull { it.id == bId }

    LaunchedEffect(a?.id, b?.id) {
        samplesA = null; samplesB = null
        if (a != null && b != null) {
            samplesA = container.sessions.chartSamples(a.id, a.sampleCount)
            samplesB = container.sessions.chartSamples(b.id, b.sampleCount)
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.compare_rules), style = MaterialTheme.typography.bodySmall)

        if (all == null) { LoadingBox(); return@Column }
        if (all.size < 2) { MessageBox(stringResource(R.string.compare_need_two)); return@Column }

        SessionPicker(stringResource(R.string.compare_session_a), all, a) { aId = it.id; bId = null }
        if (a != null) {
            if (candidates.isEmpty()) MessageBox(stringResource(R.string.compare_no_candidate))
            else SessionPicker(stringResource(R.string.compare_session_b), candidates, b) { bId = it.id }
        }

        if (a != null && b != null) {
            val kind = SensorKind.fromKey(a.kindKey) ?: return@Column
            val diff = SessionComparison.compare(a, b)
            ComparisonTable(kind, a, b, diff, units)
            SectionCard(title = stringResource(R.string.compare_overlay)) {
                val sa = samplesA; val sb = samplesB
                if (sa == null || sb == null) LoadingBox()
                else {
                    fun series(list: List<SampleEntity>, name: String, color: androidx.compose.ui.graphics.Color) = ChartSeries(
                        name, color,
                        FloatArray(list.size) { (list[it].tUs / 1e6).toFloat() },
                        FloatArray(list.size) { units.convert(kind, list[it].scalar) },
                    )
                    LineChart(
                        listOf(series(sa, a.name, ChartColors.compareA), series(sb, b.name, ChartColors.compareB)),
                        unit = units.unit(kind),
                        description = stringResource(R.string.compare_overlay),
                    )
                    Text("● ${a.name}", color = ChartColors.compareA, style = MaterialTheme.typography.labelLarge)
                    Text("● ${b.name}", color = ChartColors.compareB, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.compare_overlay_note, if (kind.isVector && kind != SensorKind.INCLINOMETER) stringResource(R.string.live_norm) else stringResource(R.string.stat_value)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPicker(label: String, options: List<SessionEntity>, selected: SessionEntity?, onSelect: (SessionEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { describe(it) } ?: "",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { s ->
                DropdownMenuItem(text = { Text(describe(s)) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

@Composable
private fun describe(s: SessionEntity): String {
    val kind = SensorKind.fromKey(s.kindKey)
    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(s.startedAtMs))
    return "${s.name} — ${kind?.let { stringResource(it.nameRes) } ?: s.kindKey} — $date"
}

@Composable
private fun ComparisonTable(kind: SensorKind, a: SessionEntity, b: SessionEntity, d: ComparisonResult, units: DisplayUnits) {
    SectionCard(title = stringResource(R.string.compare_table)) {
        val unit = units.unit(kind)
        Row(Modifier.fillMaxWidth()) {
            Cell("", 1.2f); Cell("A", 1f, true); Cell("B", 1f, true); Cell("Δ", 1.1f, true); Cell("Δ %", 0.9f, true)
        }
        fun conv(v: Double?) = v?.let { units.convert(kind, it) }
        @Composable fun line(label: String, m: MetricDiff, valueUnit: String?, decimals: Int, convert: Boolean, percent: Boolean) {
            val av = if (convert) conv(m.a) else m.a
            val bv = if (convert) conv(m.b) else m.b
            val delta = if (av != null && bv != null) bv - av else null
            val pct = if (percent) MetricDiff(av, bv).percent(true) else null
            fun f(v: Double?) = v?.let { "%.${decimals}f".format(it) } ?: "—"
            Row(Modifier.fillMaxWidth()) {
                Cell(label + (valueUnit?.let { " ($it)" } ?: ""), 1.2f)
                Cell(f(av), 1f); Cell(f(bv), 1f)
                Cell(delta?.let { "%+.${decimals}f".format(it) } ?: "—", 1.1f)
                Cell(pct?.let { "%+.1f %%".format(it) } ?: "—", 0.9f)
            }
        }
        val dec = units.decimals(kind)
        line(stringResource(R.string.report_duration), d.duration, "s", 1, convert = false, percent = true)
        line(stringResource(R.string.rec_samples), d.count, null, 0, convert = false, percent = true)
        line(stringResource(R.string.stat_mean), d.mean, unit, dec, true, kind.percentIsMeaningful)
        line(stringResource(R.string.stat_min), d.min, unit, dec, true, kind.percentIsMeaningful)
        line(stringResource(R.string.stat_max), d.max, unit, dec, true, kind.percentIsMeaningful)
        if (SessionComparison.rmsApplies(kind) && kind != SensorKind.INCLINOMETER) {
            line(stringResource(R.string.stat_rms), d.rms, unit, dec, true, kind.percentIsMeaningful)
        }
        if (!kind.percentIsMeaningful) {
            Text(stringResource(R.string.compare_no_percent), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.compare_basis, a.method),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) {
    Text(
        text, Modifier.weight(weight).padding(vertical = 2.dp),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
    )
}
