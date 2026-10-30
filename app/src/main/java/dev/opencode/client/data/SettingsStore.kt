package dev.opencode.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "opencode_settings")

/** 服务器连接信息持久化（DataStore Preferences）。 */
class SettingsStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyPassword = stringPreferencesKey("password")

    suspend fun load(): RemoteServer? {
        val prefs = context.dataStore.data.first()
        val baseUrl = prefs[keyBaseUrl] ?: return null
        return RemoteServer(baseUrl, prefs[keyPassword].orEmpty())
    }

    suspend fun save(server: RemoteServer) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = server.baseUrl
            prefs[keyPassword] = server.password
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs -> prefs.clear() }
    }
}
