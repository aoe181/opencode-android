package dev.opencode.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val Context.dataStore by preferencesDataStore(name = "opencode_settings")

/** 每个会话的本地状态（草稿、滚动位置），合并成一个 JSON 键以减少 DataStore 读写开销。 */
data class SessionLocalState(
    val drafts: Map<String, String> = emptyMap(),
    /** 会话滚动锚点：sessionID -> "消息id:偏移"。按 id 而非索引，历史分页后索引会漂移。 */
    val scrolls: Map<String, String> = emptyMap(),
)

/** 本地设置与服务器连接信息持久化（DataStore Preferences）。 */
class SettingsStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyPassword = stringPreferencesKey("password")
    private val keyDarkTheme = booleanPreferencesKey("dark_theme")
    private val keySessionsPageSize = stringPreferencesKey("sessions_page_size")
    /** 草稿 + 滚动位置（合并 JSON，单键读写）。 */
    private val keySessionLocal = stringPreferencesKey("session_local_state")
    /** 上次打开的会话（重启后自动进入）。 */
    private val keyLastSessionId = stringPreferencesKey("last_session_id")
    private val keyLastSessionTitle = stringPreferencesKey("last_session_title")
    /** 新建会话使用的默认模型/档位。 */
    private val keyDefaultProvider = stringPreferencesKey("default_provider")
    private val keyDefaultModel = stringPreferencesKey("default_model")
    private val keyDefaultVariant = stringPreferencesKey("default_variant")
    private val keyDefaultAgent = stringPreferencesKey("default_agent")
    /** 会话列表首屏缓存（启动时先渲染，网络返回后替换）。 */
    private val keySessionsCache = stringPreferencesKey("sessions_cache")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    // ---------- 夜间模式（默认深色）----------

    suspend fun loadDarkTheme(): Boolean =
        context.dataStore.data.first()[keyDarkTheme] ?: true

    suspend fun saveDarkTheme(dark: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyDarkTheme] = dark }
    }

    // ---------- 会话列表分页 ----------

    suspend fun loadSessionsPageSize(): Int =
        context.dataStore.data.first()[keySessionsPageSize]?.toIntOrNull()
            ?: DEFAULT_PAGE_SIZE

    suspend fun saveSessionsPageSize(size: Int) {
        context.dataStore.edit { prefs -> prefs[keySessionsPageSize] = size.toString() }
    }

    // ---------- 会话本地状态（草稿 / 滚动位置，合并存储）----------

    suspend fun loadSessionLocal(): SessionLocalState {
        val raw = context.dataStore.data.first()[keySessionLocal] ?: return SessionLocalState()
        return runCatching {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return SessionLocalState()
            val drafts = (root["drafts"] as? JsonObject)?.mapValues {
                (it.value as? JsonPrimitive)?.content.orEmpty()
            }.orEmpty()
            val scrolls = (root["scrolls"] as? JsonObject)?.mapValues {
                (it.value as? JsonPrimitive)?.content.orEmpty()
            }.orEmpty()
            SessionLocalState(drafts, scrolls)
        }.getOrElse { SessionLocalState() }
    }

    suspend fun saveSessionLocal(state: SessionLocalState) {
        val payload = buildJsonObject {
            put("drafts", JsonObject(state.drafts.mapValues { JsonPrimitive(it.value) }))
            put("scrolls", JsonObject(state.scrolls.mapValues { JsonPrimitive(it.value) }))
        }
        context.dataStore.edit { prefs -> prefs[keySessionLocal] = payload.toString() }
    }

    // ---------- 上次打开的会话 ----------

    suspend fun loadLastSession(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val id = prefs[keyLastSessionId] ?: return null
        return id to (prefs[keyLastSessionTitle] ?: "")
    }

    suspend fun saveLastSession(id: String, title: String) {
        context.dataStore.edit { prefs ->
            prefs[keyLastSessionId] = id
            prefs[keyLastSessionTitle] = title
        }
    }

    suspend fun clearLastSession() {
        context.dataStore.edit { prefs -> prefs.remove(keyLastSessionId) }
    }

    // ---------- 默认模型 / 档位 / Agent ----------

    /** 返回 (providerID, modelID, variant, agent)。 */
    suspend fun loadDefaults(): Defaults {
        val prefs = context.dataStore.data.first()
        return Defaults(
            providerID = prefs[keyDefaultProvider],
            modelID = prefs[keyDefaultModel],
            variant = prefs[keyDefaultVariant],
            agent = prefs[keyDefaultAgent],
        )
    }

    suspend fun saveDefaults(defaults: Defaults) {
        context.dataStore.edit { prefs ->
            defaults.providerID?.let { prefs[keyDefaultProvider] = it }
            defaults.modelID?.let { prefs[keyDefaultModel] = it }
            defaults.variant?.let { prefs[keyDefaultVariant] = it }
            defaults.agent?.let { prefs[keyDefaultAgent] = it }
        }
    }

    // ---------- 会话列表首屏缓存 ----------

    suspend fun loadSessionsCache(): List<SessionSummary> {
        val raw = context.dataStore.data.first()[keySessionsCache] ?: return emptyList()
        return runCatching { parseSessionsCache(raw) }.getOrElse { emptyList() }
    }

    suspend fun saveSessionsCache(sessions: List<SessionSummary>) {
        val payload = buildJsonObject {
            put("sessions", kotlinx.serialization.json.JsonArray(
                sessions.take(CACHE_MAX).map {
                    buildJsonObject {
                        put("id", JsonPrimitive(it.id))
                        put("title", JsonPrimitive(it.title))
                    }
                }
            ))
        }
        context.dataStore.edit { prefs -> prefs[keySessionsCache] = payload.toString() }
    }

    private fun parseSessionsCache(raw: String): List<SessionSummary> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val arr = root["sessions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            SessionSummary(id, (o["title"] as? JsonPrimitive)?.content ?: "")
        }
    }

    /** 默认模型选择。 */
    data class Defaults(
        val providerID: String? = null,
        val modelID: String? = null,
        val variant: String? = null,
        val agent: String? = null,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        /** 首屏缓存最多保留的会话数。 */
        const val CACHE_MAX = 30
    }
}
