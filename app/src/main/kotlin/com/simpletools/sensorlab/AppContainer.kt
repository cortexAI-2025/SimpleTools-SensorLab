package com.simpletools.sensorlab

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.simpletools.sensorlab.core.backup.BackupService
import com.simpletools.sensorlab.core.billing.BillingGateway
import com.simpletools.sensorlab.core.billing.DataStoreLicenseStore
import com.simpletools.sensorlab.core.billing.LicenseManager
import com.simpletools.sensorlab.core.billing.LicenseStore
import com.simpletools.sensorlab.core.billing.PlayBillingGateway
import com.simpletools.sensorlab.core.database.AppDatabase
import com.simpletools.sensorlab.core.database.SessionRepository
import com.simpletools.sensorlab.core.export.ExportService
import com.simpletools.sensorlab.core.sensors.AndroidSensorSource
import com.simpletools.sensorlab.core.sensors.SensorSource
import com.simpletools.sensorlab.core.settings.AppLanguage
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.DataStoreSettingsRepository
import com.simpletools.sensorlab.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/** Manual dependency container (no DI framework: small app, fewer moving parts). */
class AppContainer(
    val app: Application,
    /** Overridable so automated tests can inject simulated sensors. Production always uses Android's. */
    val sensorSource: SensorSource = AndroidSensorSource(app),
    databaseFactory: () -> AppDatabase = { AppDatabase.create(app) },
    val settings: SettingsRepository = DataStoreSettingsRepository(app),
    billing: BillingGateway = PlayBillingGateway(app),
    licenseStore: LicenseStore = DataStoreLicenseStore(app),
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy(databaseFactory)
    val sessions: SessionRepository by lazy { SessionRepository(database) }
    val exports: ExportService by lazy { ExportService(app, sessions, settings, ::localizedContext) }
    val backup: BackupService by lazy { BackupService(sessions) }

    /** PRO entitlement (Google Play, cached for offline use). Starts verifying immediately. */
    val license: LicenseManager = LicenseManager(billing, licenseStore, appScope)

    init {
        // A session left in RECORDING by a crash or a kill is kept and flagged INTERRUPTED.
        appScope.launch { runCatching { sessions.recoverInterrupted() } }
    }

    /** Fire-and-forget work that must outlive a ViewModel (e.g. saving a recording). */
    fun appScopeLaunch(block: suspend () -> Unit) {
        appScope.launch { runCatching { block() } }
    }

    /**
     * Deletes every session and cached export. The PRO licence is not a user data: it is not touched
     * (Google Play owns the purchase and restores it).
     */
    suspend fun deleteAllData() {
        sessions.deleteAll()
        exports.clearCache()
    }

    companion object {
        /** Context whose resources follow the language chosen in the settings. */
        fun localizedContext(base: Context, settings: AppSettings): Context {
            val tag = settings.language.tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }

        fun isFrench(settings: AppSettings) = settings.language == AppLanguage.FRENCH
    }
}
