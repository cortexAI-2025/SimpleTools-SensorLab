package com.simpletools.sensorlab.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.BuildConfig
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.database.AppDatabase
import com.simpletools.sensorlab.core.settings.AngularUnit
import com.simpletools.sensorlab.core.settings.AppLanguage
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.CsvFormat
import com.simpletools.sensorlab.core.settings.PressureUnit
import com.simpletools.sensorlab.core.settings.SamplingPreset
import com.simpletools.sensorlab.core.settings.TemperatureUnit
import com.simpletools.sensorlab.core.settings.ThemeMode
import com.simpletools.sensorlab.ui.BigButton
import com.simpletools.sensorlab.ui.ConfirmDialog
import com.simpletools.sensorlab.ui.LabeledValue
import com.simpletools.sensorlab.ui.RadioGroup
import com.simpletools.sensorlab.ui.ScreenPadding
import com.simpletools.sensorlab.ui.SectionCard
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var dbBytes by remember { mutableLongStateOf(0L) }
    val sessionCount by remember { container.sessions.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(sessionCount.size, deleted) {
        val f = context.getDatabasePath(AppDatabase.FILE_NAME)
        dbBytes = listOf(f, File(f.path + "-wal")).filter { it.exists() }.sumOf { it.length() }
    }
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { container.settings.update(t) } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = stringResource(R.string.settings_theme)) {
            RadioGroup(
                listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                ),
                settings.theme,
            ) { v -> update { it.copy(theme = v) } }
        }
        SectionCard(title = stringResource(R.string.settings_language)) {
            RadioGroup(
                listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.lang_system),
                    AppLanguage.FRENCH to "Français",
                    AppLanguage.ENGLISH to "English",
                ),
                settings.language,
            ) { v -> update { it.copy(language = v) } }
        }
        SectionCard(title = stringResource(R.string.settings_units)) {
            Text(stringResource(R.string.unit_angular), style = MaterialTheme.typography.labelLarge)
            RadioGroup(listOf(AngularUnit.RAD_PER_S to "rad/s", AngularUnit.DEG_PER_S to "°/s"), settings.angularUnit) { v -> update { it.copy(angularUnit = v) } }
            Text(stringResource(R.string.unit_pressure), style = MaterialTheme.typography.labelLarge)
            RadioGroup(listOf(PressureUnit.HPA to "hPa", PressureUnit.MMHG to "mmHg"), settings.pressureUnit) { v -> update { it.copy(pressureUnit = v) } }
            Text(stringResource(R.string.unit_temperature), style = MaterialTheme.typography.labelLarge)
            RadioGroup(listOf(TemperatureUnit.CELSIUS to "°C", TemperatureUnit.FAHRENHEIT to "°F"), settings.temperatureUnit) { v -> update { it.copy(temperatureUnit = v) } }
            Text(stringResource(R.string.unit_note), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard(title = stringResource(R.string.settings_sampling)) {
            RadioGroup(
                SamplingPreset.entries.map { it to stringResource(samplingLabel(it), it.hz) },
                settings.sampling,
            ) { v -> update { it.copy(sampling = v) } }
            Text(stringResource(R.string.sampling_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.chart_window), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 60, 120).forEach { s ->
                    FilterChip(settings.chartWindowS == s, { update { it.copy(chartWindowS = s) } }, label = { Text("$s s") })
                }
            }
        }
        SectionCard(title = stringResource(R.string.settings_vibration)) {
            Text(stringResource(R.string.vib_cutoff, "%.1f".format(settings.vibrationCutoffHz)), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.vibrationCutoffHz.toFloat(),
                onValueChange = { v -> update { it.copy(vibrationCutoffHz = (Math.round(v * 2) / 2.0)) } },
                valueRange = 0.5f..5f, steps = 8,
            )
            Text(stringResource(R.string.vib_rms_window), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5).forEach { s ->
                    FilterChip(settings.vibrationRmsWindowS == s, { update { it.copy(vibrationRmsWindowS = s) } }, label = { Text("$s s") })
                }
            }
            Text(stringResource(R.string.vib_filter_doc), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard(title = stringResource(R.string.settings_csv)) {
            RadioGroup(
                listOf(
                    CsvFormat.INTERNATIONAL to stringResource(R.string.csv_international),
                    CsvFormat.EXCEL_FR to stringResource(R.string.csv_excel_fr),
                ),
                settings.csvFormat,
            ) { v -> update { it.copy(csvFormat = v) } }
        }
        SectionCard(title = stringResource(R.string.settings_storage)) {
            LabeledValue(stringResource(R.string.storage_sessions), "${sessionCount.size}")
            LabeledValue(stringResource(R.string.storage_size), "%.1f MB".format(dbBytes / 1_048_576.0))
            Text(stringResource(R.string.storage_policy), style = MaterialTheme.typography.bodySmall)
            BigButton(stringResource(R.string.storage_delete_all), { confirmDeleteAll = true }, Modifier.fillMaxWidth(), outlined = true)
            if (deleted) Text(stringResource(R.string.storage_deleted), color = MaterialTheme.colorScheme.primary)
        }
        SectionCard(title = stringResource(R.string.settings_privacy)) {
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(title = stringResource(R.string.settings_about)) {
            LabeledValue(stringResource(R.string.app_name), "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text(stringResource(R.string.by_publisher), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.accuracy_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            stringResource(R.string.storage_delete_all),
            stringResource(R.string.storage_delete_all_msg),
            stringResource(R.string.action_delete),
            onConfirm = { scope.launch { container.deleteAllData(); deleted = true }; confirmDeleteAll = false },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

private fun samplingLabel(p: SamplingPreset) = when (p) {
    SamplingPreset.ECONOMY -> R.string.sampling_economy
    SamplingPreset.NORMAL -> R.string.sampling_normal
    SamplingPreset.FAST -> R.string.sampling_fast
    SamplingPreset.MAX -> R.string.sampling_max
}
