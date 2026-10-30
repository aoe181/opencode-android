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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    fun openSession(id: String, title: String) {
        _selected.value = id to title
    }

    fun closeSession() {
        _selected.value = null
        refresh()
    }

    init {
        viewModelScope.launch {
            settings.load()?.let { saved ->
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
            val sessionsDeferred = async { api.sessions(server) }
            infoDeferred.await()
                .onSuccess { info ->
                    settings.save(server)
                    val sessions = sessionsDeferred.await().getOrElse { emptyList() }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            restoring = false,
                            connected = true,
                            message = null,
                            server = server,
                            serverInfo = info,
                            sessions = sessions,
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
