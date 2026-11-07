package com.simpletools.sensorlab.feature.comparison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.simpletools.sensorlab.ui.LockedCard
import com.simpletools.sensorlab.ui.MessageBox
import com.simpletools.sensorlab.ui.ProPaywallDialog
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard

/** Up to this many sessions in one comparison, the reference included (fits a phone screen). */
const val MAX_COMPARED = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(container: AppContainer, initialA: String?) {
    val sessions: List<SessionEntity>? by remember { container.sessions.observeAll() }.collectAsState(initial = null)
    val pro by container.license.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val units = remember(settings) { DisplayUnits(settings) }
    var refId by remember { mutableStateOf(initialA) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var samples by remember { mutableStateOf<Map<String, List<SampleEntity>>>(emptyMap()) }
    var showPaywall by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineSmall)
        if (!pro.isPro) {
            LockedCard(
                title = stringResource(R.string.compare_title),
                body = stringResource(R.string.locked_body_compare),
                onUnlock = { showPaywall = true },
            )
            if (showPaywall) ProPaywallDialog(container.license) { showPaywall = false }
            return@Column
        }
        Text(stringResource(R.string.compare_rules), style = MaterialTheme.typography.bodySmall)

        val all = sessions
        if (all == null) { LoadingBox(); return@Column }
        if (all.size < 2) { MessageBox(stringResource(R.string.compare_need_two)); return@Column }

        val ref = all.firstOrNull { it.id == refId }
        SessionPicker(stringResource(R.string.compare_session_a), all, ref) { refId = it.id; chosen = emptySet() }
        if (ref == null) return@Column

        val candidates = SessionComparison.candidatesFor(ref, all)
        if (candidates.isEmpty()) { MessageBox(stringResource(R.string.compare_no_candidate)); return@Column }

        val group = listOf(ref) + candidates.filter { it.id in chosen }
        SectionCard(title = stringResource(R.string.compare_select)) {
            Text(stringResource(R.string.compare_pick_hint, MAX_COMPARED - 1), style = MaterialTheme.typography.bodySmall)
            candidates.forEach { c ->
                val checked = c.id in chosen
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = checked || group.size < MAX_COMPARED) {
                        chosen = if (checked) chosen - c.id else chosen + c.id
                    },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = checked || group.size < MAX_COMPARED)
                    Text(describe(c), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (group.size < 2) {
            MessageBox(stringResource(R.string.compare_select_one))
            return@Column
        }

        val kind = SensorKind.fromKey(ref.kindKey) ?: return@Column
        LaunchedEffect(group.map { it.id }) {
            samples = group.associate { it.id to container.sessions.chartSamples(it.id, it.sampleCount) }
        }
        ComparisonTable(kind, group, units)
        SectionCard(title = stringResource(R.string.compare_overlay)) {
            val colors = listOf(ChartColors.compareA, ChartColors.compareB, ChartColors.compareC, ChartColors.compareD)
            val series = group.mapIndexed { i, s ->
                val list = samples[s.id].orEmpty()
                ChartSeries(
                    s.name, colors[i],
                    FloatArray(list.size) { (list[it].tUs / 1e6).toFloat() },
                    FloatArray(list.size) { units.convert(kind, list[it].scalar) },
                )
            }
            LineChart(series, unit = units.unit(kind), description = stringResource(R.string.compare_overlay))
            group.forEachIndexed { i, s ->
                Text("● ${s.name}", color = colors[i], style = MaterialTheme.typography.labelLarge)
            }
            Text(
                stringResource(R.string.compare_overlay_note, if (kind.isVector && kind != SensorKind.INCLINOMETER) stringResource(R.string.live_norm) else stringResource(R.string.stat_value)),
                style = MaterialTheme.typography.bodySmall,
            )
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

/**
 * One column per session. Each non-reference cell shows the difference to the reference, in the
 * displayed unit (B − A), and the percentage only where it has a physical meaning.
 */
@Composable
private fun ComparisonTable(kind: SensorKind, group: List<SessionEntity>, units: DisplayUnits) {
    val ref = group.first()
    val dec = units.decimals(kind)
    val unit = units.unit(kind)
    SectionCard(title = stringResource(R.string.compare_table)) {
        Row(Modifier.fillMaxWidth()) {
            Cell("", 1.1f, header = true)
            group.forEachIndexed { i, s ->
                Cell(
                    if (i == 0) "${stringResource(R.string.compare_ref)}: ${s.name}" else s.name,
                    1f, header = true,
                )
            }
        }
        @Composable
        fun line(label: String, valueUnit: String?, decimals: Int, convert: Boolean, pick: (SessionEntity) -> Double?, percent: Boolean) {
            fun conv(v: Double?) = if (convert) v?.let { units.convert(kind, it) } else v
            val refValue = conv(pick(ref))
            Row(Modifier.fillMaxWidth()) {
                Cell(label + (valueUnit?.let { " ($it)" } ?: ""), 1.1f)
                group.forEachIndexed { i, s ->
                    val v = conv(pick(s))
                    val text = buildString {
                        append(v?.let { "%.${decimals}f".format(it) } ?: "—")
                        if (i > 0 && v != null && refValue != null) {
                            append("\nΔ ").append("%+.${decimals}f".format(v - refValue))
                            if (percent) {
                                MetricDiffPercent.of(refValue, v)?.let { append(" (").append("%+.1f %%".format(it)).append(")") }
                            }
                        }
                    }
                    Cell(text, 1f)
                }
            }
        }
        line(stringResource(R.string.report_duration), "s", 1, false, { it.durationMs / 1000.0 }, percent = true)
        line(stringResource(R.string.rec_samples), null, 0, false, { it.sampleCount.toDouble() }, percent = true)
        line(stringResource(R.string.stat_mean), unit, dec, true, { it.scalarMean }, percent = kind.percentIsMeaningful)
        line(stringResource(R.string.stat_min), unit, dec, true, { it.scalarMin }, percent = kind.percentIsMeaningful)
        line(stringResource(R.string.stat_max), unit, dec, true, { it.scalarMax }, percent = kind.percentIsMeaningful)
        if (SessionComparison.rmsApplies(kind) && kind != SensorKind.INCLINOMETER) {
            line(stringResource(R.string.stat_rms), unit, dec, true, { it.scalarRms }, percent = kind.percentIsMeaningful)
        }
        if (!kind.percentIsMeaningful) {
            Text(stringResource(R.string.compare_no_percent), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.compare_basis, ref.method),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private object MetricDiffPercent {
    fun of(a: Double, b: Double): Double? =
        if (kotlin.math.abs(a) < 1e-9) null else (b - a) / kotlin.math.abs(a) * 100.0
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) {
    Text(
        text,
        Modifier.weight(weight).padding(vertical = 2.dp, horizontal = 2.dp),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        maxLines = if (header) 1 else 3,
        overflow = TextOverflow.Ellipsis,
        color = Color.Unspecified,
    )
}
