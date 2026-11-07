package com.simpletools.sensorlab.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.database.SessionEntity
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.ui.BigButton
import com.simpletools.sensorlab.ui.ConfirmDialog
import com.simpletools.sensorlab.ui.LoadingBox
import com.simpletools.sensorlab.ui.LockedCard
import com.simpletools.sensorlab.ui.MessageBox
import com.simpletools.sensorlab.ui.ProPaywallDialog
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard
import com.simpletools.sensorlab.ui.SensorIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionsScreen(container: AppContainer, onOpen: (String) -> Unit, onCompare: () -> Unit) {
    // null = still loading, empty list = nothing recorded yet
    val sessions: List<SessionEntity>? by remember { container.sessions.observeAll() }.collectAsState(initial = null)
    val pro by container.license.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var showPaywall by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        val list = sessions
        when {
            list == null -> LoadingBox()
            !pro.isPro -> {
                // Free: nothing is deleted or hidden; the sessions stay on the phone.
                LockedCard(
                    title = stringResource(R.string.locked_title_sessions),
                    body = stringResource(R.string.locked_body_sessions, list.size),
                    onUnlock = { showPaywall = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            list.isEmpty() -> MessageBox(stringResource(R.string.sessions_empty), Modifier.padding(horizontal = 16.dp))
            else -> LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    BigButton(
                        stringResource(R.string.compare_open), onCompare, Modifier.fillMaxWidth(),
                        outlined = true, enabled = list.size >= 2,
                    )
                }
                items(list, key = { it.id }) { s ->
                    SessionRow(s, onClick = { onOpen(s.id) }, onDelete = { toDelete = s })
                }
            }
        }
    }

    if (showPaywall) ProPaywallDialog(container.license) { showPaywall = false }

    toDelete?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.delete_session_title),
            message = stringResource(R.string.delete_session_msg, s.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { scope.launch { container.sessions.delete(s.id) }; toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun SessionRow(s: SessionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val kind = SensorKind.fromKey(s.kindKey)
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind != null) Icon(SensorIcons.of(kind), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${kind?.let { stringResource(it.nameRes) } ?: s.kindKey} · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${"%.1f".format(s.durationMs / 1000.0)} s · ${s.sampleCount} ${stringResource(R.string.samples_unit)}" +
                        if (s.status == "INTERRUPTED") " · ${stringResource(R.string.status_interrupted)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
        }
    }
}
