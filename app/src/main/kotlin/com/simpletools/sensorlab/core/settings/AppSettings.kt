package com.simpletools.sensorlab.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage(val tag: String?) { SYSTEM(null), FRENCH("fr"), ENGLISH("en") }
enum class AngularUnit { RAD_PER_S, DEG_PER_S }
enum class PressureUnit { HPA, MMHG }
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }
enum class CsvFormat(val separator: Char, val decimal: Char) {
    /** Comma separated, dot decimals (RFC 4180, most tools). */
    INTERNATIONAL(',', '.'),
    /** Semicolon separated, comma decimals (Excel with French regional settings). */
    EXCEL_FR(';', ','),
}

/** Requested sampling rate presets. Android treats the period as a hint; the observed rate is shown. */
enum class SamplingPreset(val hz: Int) {
    ECONOMY(5), NORMAL(25), FAST(50), MAX(100);

    val periodUs: Int get() = 1_000_000 / hz
    val periodNs: Long get() = 1_000_000_000L / hz
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val angularUnit: AngularUnit = AngularUnit.RAD_PER_S,
    val pressureUnit: PressureUnit = PressureUnit.HPA,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val sampling: SamplingPreset = SamplingPreset.NORMAL,
    val chartWindowS: Int = 30,
    val csvFormat: CsvFormat = CsvFormat.INTERNATIONAL,
    /** High-pass cutoff of the vibration module (Hz). */
    val vibrationCutoffHz: Double = 1.0,
    /** RMS window of the vibration module (s). */
    val vibrationRmsWindowS: Int = 1,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sensorlab_settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val angular = stringPreferencesKey("angular_unit")
        val pressure = stringPreferencesKey("pressure_unit")
        val temperature = stringPreferencesKey("temperature_unit")
        val sampling = stringPreferencesKey("sampling")
        val window = intPreferencesKey("chart_window_s")
        val csv = stringPreferencesKey("csv_format")
        val cutoff = doublePreferencesKey("vibration_cutoff_hz")
        val rms = intPreferencesKey("vibration_rms_window_s")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[Keys.theme].toEnum(d.theme),
            language = p[Keys.language].toEnum(d.language),
            angularUnit = p[Keys.angular].toEnum(d.angularUnit),
            pressureUnit = p[Keys.pressure].toEnum(d.pressureUnit),
            temperatureUnit = p[Keys.temperature].toEnum(d.temperatureUnit),
            sampling = p[Keys.sampling].toEnum(d.sampling),
            chartWindowS = p[Keys.window] ?: d.chartWindowS,
            csvFormat = p[Keys.csv].toEnum(d.csvFormat),
            vibrationCutoffHz = p[Keys.cutoff] ?: d.vibrationCutoffHz,
            vibrationRmsWindowS = p[Keys.rms] ?: d.vibrationRmsWindowS,
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = AppSettings(
                theme = p[Keys.theme].toEnum(ThemeMode.SYSTEM),
                language = p[Keys.language].toEnum(AppLanguage.SYSTEM),
                angularUnit = p[Keys.angular].toEnum(AngularUnit.RAD_PER_S),
                pressureUnit = p[Keys.pressure].toEnum(PressureUnit.HPA),
                temperatureUnit = p[Keys.temperature].toEnum(TemperatureUnit.CELSIUS),
                sampling = p[Keys.sampling].toEnum(SamplingPreset.NORMAL),
                chartWindowS = p[Keys.window] ?: 30,
                csvFormat = p[Keys.csv].toEnum(CsvFormat.INTERNATIONAL),
                vibrationCutoffHz = p[Keys.cutoff] ?: 1.0,
                vibrationRmsWindowS = p[Keys.rms] ?: 1,
            )
            val n = transform(current)
            p[Keys.theme] = n.theme.name
            p[Keys.language] = n.language.name
            p[Keys.angular] = n.angularUnit.name
            p[Keys.pressure] = n.pressureUnit.name
            p[Keys.temperature] = n.temperatureUnit.name
            p[Keys.sampling] = n.sampling.name
            p[Keys.window] = n.chartWindowS
            p[Keys.csv] = n.csvFormat.name
            p[Keys.cutoff] = n.vibrationCutoffHz
            p[Keys.rms] = n.vibrationRmsWindowS
        }
    }

    suspend fun clear() { store.edit { it.clear() } }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
