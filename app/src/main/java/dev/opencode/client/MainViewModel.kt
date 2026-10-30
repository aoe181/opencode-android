package dev.opencode.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.client.data.OpenCodeApi
import dev.opencode.client.data.RemoteServer
import dev.opencode.client.data.ServerInfo
import dev.opencode.client.data.SessionSummary
import dev.opencode.client.data.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val loading: Boolean = false,
    /** 启动时用已保存的服务器自动重连中（显示轻量过渡页而不是连接表单）。 */
    val restoring: Boolean = false,
    val connected: Boolean = false,
    val message: String? = null,
    val server: RemoteServer? = null,
    val serverInfo: ServerInfo? = null,
    val sessions: List<SessionSummary> = emptyList(),
    /** 会话列表下一页 cursor（null = 没有更多）。 */
    val sessionsCursor: String? = null,
    val loadingMore: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val PAGE_SIZE = 50
    }

    private val settings = SettingsStore(app)
    private val api = OpenCodeApi()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** 当前打开的会话：(sessionID, title)。null 表示停留在会话列表。 */
    private val _selected = MutableStateFlow<Pair<String, String>?>(null)
    val selected: StateFlow<Pair<String, String>?> = _selected.asStateFlow()

    /** 各会话的输入框草稿缓存：sessionID -> 文本（切对话不丢）。 */
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun saveDraft(sessionID: String, text: String) {
        _drafts.update { it + (sessionID to text) }
    }

    /** 各会话上次浏览到的消息索引（切对话回来恢复位置）。 */
    private val _scrollPositions = mutableMapOf<String, Int>()

    fun saveScrollPosition(sessionID: String, index: Int) {
        _scrollPositions[sessionID] = index
    }

    fun scrollPosition(sessionID: String): Int? = _scrollPositions[sessionID]

    fun openSession(id: String, title: String) {
        _selected.value = id to title
    }

    fun closeSession() {
        _selected.value = null
        refresh()
    }

    init {
        viewModelScope.launch {
            // 启动加速：先并行取「已保存的服务器」与「主题设置」，
            // 拿到地址后 info + sessions 也已并行（见 verify），整体冷启动更快
            val saved = settings.load()
            if (saved != null) {
                // 立即进入"正在连接"过渡态，UI 不等网络
                _uiState.update { it.copy(server = saved, loading = true, restoring = true) }
                verify(saved)
            }
        }
    }

    fun connect(baseUrl: String, password: String) {
        val server = RemoteServer(baseUrl.trim().trimEnd('/'), password.trim())
        _uiState.update { it.copy(loading = true, message = null, server = server, restoring = false) }
        viewModelScope.launch { verify(server) }
    }

    fun refresh() {
        val server = _uiState.value.server ?: return
        _uiState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch { verify(server) }
    }

    /** 加载更多会话（列表翻页）。 */
    fun loadMoreSessions() {
        val server = _uiState.value.server ?: return
        val cursor = _uiState.value.sessionsCursor ?: return
        if (_uiState.value.loadingMore) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            api.sessions(server, PAGE_SIZE, cursor)
                .onSuccess { (more, next) ->
                    _uiState.update { st ->
                        st.copy(
                            sessions = (st.sessions + more).distinctBy { it.id },
                            sessionsCursor = next,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loadingMore = false, message = "加载更多失败：${e.message}")
                    }
                }
        }
    }

    /** 重命名会话。 */
    fun renameSession(id: String, title: String) {
        val server = _uiState.value.server ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            api.renameSession(server, id, trimmed)
                .onSuccess {
                    _uiState.update { st ->
                        st.copy(sessions = st.sessions.map {
                            if (it.id == id) it.copy(title = trimmed) else it
                        })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "重命名失败：${e.message}") }
                }
        }
    }

    fun deleteSession(id: String) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            api.deleteSession(server, id)
                .onSuccess {
                    // 若删除的是当前打开的会话，退回列表
                    if (_selected.value?.first == id) _selected.value = null
                    _uiState.update { st -> st.copy(sessions = st.sessions.filterNot { it.id == id }) }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "删除失败：${e.message}") }
                }
        }
    }

    fun createSession() {        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            api.createSession(server)
                .onSuccess { id ->
                    refresh()
                    openSession(id, "新会话")
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "新建会话失败：${e.message}") }
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            settings.clear()
            _selected.value = null
            _uiState.value = MainUiState()
        }
    }

    /** 用 /api/info 验证连通性与认证；info 与会话列表并行请求。 */
    private suspend fun verify(server: RemoteServer) {
        coroutineScope {
            val infoDeferred = async { api.info(server) }
            val sessionsDeferred = async { api.sessions(server, PAGE_SIZE) }
            infoDeferred.await()
                .onSuccess { info ->
                    settings.save(server)
                    val (sessions, cursor) = sessionsDeferred.await()
                        .getOrElse { emptyList<SessionSummary>() to null }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            restoring = false,
                            connected = true,
                            message = null,
                            server = server,
                            serverInfo = info,
                            sessions = sessions,
                            sessionsCursor = cursor,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            restoring = false,
                            connected = false,
                            message = friendlyError(e),
                        )
                    }
                }
        }
    }

    private fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("401") || msg.contains("unauthorized") ->
                "认证失败：请检查访问密码（服务器端用 OPENCODE_PASSWORD 设置）"
            msg.contains("connect") || msg.contains("refused") || msg.contains("unreachable") ->
                "无法连接：请确认 Termux 里已运行 opencode serve，且地址端口正确"
            msg.contains("timeout") -> "连接超时，请稍后重试"
            else -> "连接失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
