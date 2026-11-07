package com.simpletools.sensorlab.feature.dashboard

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.sensors.HardwareSensor
import com.simpletools.sensorlab.core.sensors.SensorInfo
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.ui.BigButton
import com.simpletools.sensorlab.ui.LabeledValue
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard
import com.simpletools.sensorlab.ui.SensorIcons
import java.text.DateFormat
import java.util.Date

@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenSensor: (SensorKind) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    // Inventory is a cheap SensorManager query: no sensor is started on this screen.
    val inventory = remember { container.sensorSource.inventory() }
    val hardware = remember { container.sensorSource.hardwareSensors() }
    val latest by remember { container.sessions.observeLatest() }.collectAsState(initial = null)
    val pro by container.license.state.collectAsStateWithLifecycle()
    val battery = remember { batteryPercent(context) }
    val available = inventory.count { it.available }
    var showHardware by remember { mutableStateOf(false) }
    var showSensors by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.by_publisher),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            SectionCard(title = stringResource(R.string.dash_phone_state)) {
                LabeledValue(stringResource(R.string.dash_device), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
                LabeledValue(stringResource(R.string.dash_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                LabeledValue(stringResource(R.string.dash_battery), battery?.let { "$it %" } ?: "—")
                LabeledValue(
                    stringResource(R.string.dash_sensors_available),
                    stringResource(R.string.dash_sensors_count, available, inventory.size),
                )
            }
        }
        item {
            SectionCard(title = stringResource(R.string.dash_last_session)) {
                val s = latest
                if (!pro.isPro) {
                    Text(stringResource(R.string.dash_pro_hint), style = MaterialTheme.typography.bodyMedium)
                } else if (s == null) {
                    Text(stringResource(R.string.dash_no_session), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val kind = SensorKind.fromKey(s.kindKey)
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${kind?.let { stringResource(it.nameRes) } ?: s.kindKey} · " +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAtMs)) +
                            " · ${"%.1f".format(s.durationMs / 1000.0)} s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onOpenSession(s.id) }) { Text(stringResource(R.string.action_open)) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(stringResource(R.string.dash_new_measure), { showSensors = true }, Modifier.weight(1f))
                BigButton(stringResource(R.string.dash_my_sessions), onOpenSessions, Modifier.weight(1f), outlined = true)
            }
        }
        item {
            Text(stringResource(R.string.dash_detected_sensors), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dash_sensors_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(inventory, key = { it.kind.key }) { info ->
            SensorCard(info, onOpen = { onOpenSensor(info.kind) })
        }
        item {
            TextButton(onClick = { showHardware = !showHardware }) {
                Text(
                    if (showHardware) stringResource(R.string.dash_hide_hardware)
                    else stringResource(R.string.dash_show_hardware, hardware.size),
                )
            }
        }
        if (showHardware) {
            items(hardware.sortedBy { it.androidType }) { HardwareRow(it) }
        }
    }

    if (showSensors) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSensors = false },
            title = { Text(stringResource(R.string.dash_choose_sensor)) },
            text = {
                Column {
                    inventory.filter { it.available }.forEach { info ->
                        TextButton(onClick = { showSensors = false; onOpenSensor(info.kind) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(info.kind.nameRes), Modifier.fillMaxWidth())
                        }
                    }
                    if (available == 0) Text(stringResource(R.string.dash_none_available))
                }
            },
            confirmButton = {
                TextButton(onClick = { showSensors = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SensorCard(info: SensorInfo, onOpen: () -> Unit) {
    val kind = info.kind
    SectionCard(
        container = if (info.available) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                SensorIcons.of(kind), contentDescription = null, modifier = Modifier.size(32.dp),
                tint = if (info.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(kind.nameRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (info.available) stringResource(R.string.sensor_available) else stringResource(R.string.sensor_absent),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (info.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(stringResource(kind.descriptionRes), style = MaterialTheme.typography.bodySmall)
        if (info.available) {
            info.name?.let {
                Text(
                    "$it · ${info.vendor.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (info.usesFallback) Text(stringResource(R.string.sensor_fallback_note), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.sensor_last_value_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigButton(stringResource(R.string.action_open), onOpen, Modifier.fillMaxWidth(), outlined = true)
        }
    }
}

@Composable
private fun HardwareRow(h: HardwareSensor) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(h.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${h.vendor} · type ${h.androidType} · ${h.stringType}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun batteryPercent(context: android.content.Context): Int? {
    // Sticky broadcast: no permission and no receiver left registered.
    val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}
