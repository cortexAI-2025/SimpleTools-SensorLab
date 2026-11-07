package com.simpletools.sensorlab.core.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Last licence state confirmed by Google Play. Used offline; never invented. */
data class CachedLicense(val pro: Boolean, val token: String?, val verifiedAtMs: Long)

interface LicenseStore {
    val cached: Flow<CachedLicense>
    suspend fun save(license: CachedLicense)
}

private val Context.licenseDataStore: DataStore<Preferences> by preferencesDataStore(name = "sensorlab_license")

/** Separate DataStore file: the licence never shares a file with user settings. */
class DataStoreLicenseStore(context: Context) : LicenseStore {
    private val store = context.applicationContext.licenseDataStore

    private object Keys {
        val pro = booleanPreferencesKey("pro")
        val token = stringPreferencesKey("token")
        val verified = longPreferencesKey("verified_at_ms")
    }

    override val cached: Flow<CachedLicense> = store.data.map { p ->
        CachedLicense(
            pro = p[Keys.pro] == true,
            token = p[Keys.token],
            verifiedAtMs = p[Keys.verified] ?: 0L,
        )
    }

    override suspend fun save(license: CachedLicense) {
        store.edit { p ->
            p[Keys.pro] = license.pro
            if (license.token != null) p[Keys.token] = license.token else p.remove(Keys.token)
            p[Keys.verified] = license.verifiedAtMs
        }
    }
}
