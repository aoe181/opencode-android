package dev.opencode.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "opencode_settings")

/** 本地设置与服务器连接信息持久化（DataStore Preferences）。 */
class SettingsStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyPassword = stringPreferencesKey("password")
    private val keyDarkTheme = booleanPreferencesKey("dark_theme")
    private val keySessionsPageSize = stringPreferencesKey("sessions_page_size")

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

    // ---------- 夜间模式持久化（默认深色）----------

    suspend fun loadDarkTheme(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[keyDarkTheme] ?: true
    }

    suspend fun saveDarkTheme(dark: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyDarkTheme] = dark }
    }

    // ---------- 会话列表分页 ----------

    suspend fun loadSessionsPageSize(): Int {
        val prefs = context.dataStore.data.first()
        return prefs[keySessionsPageSize]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
    }

    suspend fun saveSessionsPageSize(size: Int) {
        context.dataStore.edit { prefs -> prefs[keySessionsPageSize] = size.toString() }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
