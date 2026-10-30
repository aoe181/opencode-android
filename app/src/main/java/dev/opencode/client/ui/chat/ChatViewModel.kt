package dev.opencode.client.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.client.data.AgentInfo
import dev.opencode.client.data.Block
import dev.opencode.client.data.ChatMessage
import dev.opencode.client.data.CommandInfo
import dev.opencode.client.data.EventStream
import dev.opencode.client.data.FileAttachment
import dev.opencode.client.data.McpServerInfo
import dev.opencode.client.data.ModelInfo
import dev.opencode.client.data.ModelSelection
import dev.opencode.client.data.OpenCodeApi
import dev.opencode.client.data.OpenCodeEvent
import dev.opencode.client.data.RemoteServer
import dev.opencode.client.data.Tokens
import dev.opencode.client.data.ToolCall
import dev.opencode.client.data.deriveToolTitle
import dev.opencode.client.data.extractMcpToolName
import dev.opencode.client.data.extractMcpToolNameFromRaw
import dev.opencode.client.data.extractMcpToolNameFromText
import dev.opencode.client.data.inferToolNameFromInput
import dev.opencode.client.data.parseToolInput
import dev.opencode.client.data.joinContentText
import dev.opencode.client.data.num
import dev.opencode.client.data.parseFileChanges
import dev.opencode.client.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val sending: Boolean = false,
    val compacting: Boolean = false, // 正在压缩上下文
    val connectionState: String = "connected", // SSE: connected / reconnecting / error
    val error: String? = null,
    // agent / model / 档位
    val agents: List<AgentInfo> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val commands: List<CommandInfo> = emptyList(),
    val currentAgent: String? = null,
    val currentProvider: String? = null,
    val currentModel: String? = null,
    val currentVariant: String? = null,
    // 用量
    val usage: Tokens? = null,        // 会话累计（服务器权威）
    val usageCost: Double? = null,
    val apiCalls: Int = 0,            // LLM 请求次数（assistant 消息数近似）
    val contextTokens: Long? = null,  // 最近一次请求的上下文占用（input + cache read）
    // 附件
    val attachments: List<FileAttachment> = emptyList(),
    /** 服务器工作目录（附件上传 location 参数）。 */
    val serverDirectory: String? = null,
    // 待投递输入（agent 执行中发来的新输入）：inboxID -> 文本
    val pendingInputs: Map<String, String> = emptyMap(),
    /** inboxID -> 投递方式（steer 插入 / queue 排队），长按面板据此显示切换文案。 */
    val pendingDeliveries: Map<String, String> = emptyMap(),
    /** MCP 服务器列表与连接状态（顶栏连接图标点开时加载）。 */
    val mcpServers: List<McpServerInfo> = emptyList(),
    /** 正在重连的 MCP 服务器名。 */
    val reconnectingMcp: String? = null,
    /** 正在执行回退（revert）。 */
    val reverting: Boolean = false,
)

/**
 * 会话详情：历史消息 + SSE 实时流 + agent/模型/档位切换 + 附件 + 斜杠命令。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val api = OpenCodeApi()
    private val settings = dev.opencode.client.data.SettingsStore(app)
    private var server: RemoteServer? = null
    private var sessionID: String? = null
    private var streamJob: Job? = null

    private var history: List<ChatMessage> = emptyList()
    private var historyIds: Set<String> = emptySet()
    private var liveApiCalls = 0

    /** 流式渲染节流标记（16ms 合并刷新）。 */
    private var publishScheduled = false

    /** 最近一次发送失败的内容（供重试）。 */
    private var failedSend: Pair<String, List<FileAttachment>>? = null

    /** 长会话只保留最近 N 条在内存中（避免上千条全部驻留导致卡顿）。 */
    private val maxHistoryInMemory = 500

    /** mid -> blockKey -> Block；blockKey："t:<ordinal>" 文本 | "c:<callID>" 工具 */
    private val liveBlocks = LinkedHashMap<String, LinkedHashMap<String, Block>>()

    /** callID -> 起始时间戳（服务器 created），用于计算工具耗时 */
    private val toolStart = HashMap<String, Long>()

    /** "mid:ordinal" -> 思考起始时间戳，用于计算思考耗时 */
    private val reasoningStart = HashMap<String, Long>()

    /** inboxID -> 待投递输入文本 */
    private val pendingUser = LinkedHashMap<String, String>()

    /** inboxID -> 投递方式（steer/queue） */
    private val pendingDelivery = HashMap<String, String>()

    /** shellID -> callID / callID -> mid：shell 事件流与工具调用的关联 */
    private val lastShellCall = HashMap<String, String>()
    private val lastShellCallMid = HashMap<String, String>()

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    fun start(server: RemoteServer, sessionID: String) {
        if (this.sessionID == sessionID && streamJob?.isActive == true) return
        stop()
        this.server = server
        this.sessionID = sessionID
        liveBlocks.clear()
        toolStart.clear()
        reasoningStart.clear()
        pendingUser.clear()
        pendingDelivery.clear()
        lastShellCall.clear()
        lastShellCallMid.clear()
        history = emptyList()
        _ui.value = ChatUiState()
        viewModelScope.launch { loadHistory() }
        viewModelScope.launch { loadPickerData() }
        // 切换会话后恢复执行状态：新实例不知道服务器端是否仍在跑，
        // 若不同步，停止按钮会消失（但任务其实还在继续）
        syncStreamingState()
        syncInbox()
        startStream()
    }

    /** 从服务器同步当前会话是否正在执行（GET /api/session/active）。 */
    private fun syncStreamingState() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            api.activeSessions(s).onSuccess { map ->
                val running = map[sid] == "running"
                _ui.update { it.copy(streaming = running) }
                // 兜底：若在查询与 SSE 建立之间任务已结束，会错过 execution.succeeded
                // 事件导致停止按钮一直挂着，延迟复查一次
                if (running) {
                    delay(5000)
                    api.activeSessions(s).onSuccess { m2 ->
                        _ui.update { it.copy(streaming = m2[sid] == "running") }
                    }
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        sessionID = null
    }

    private fun startStream() {
        val s = server ?: return
        streamJob = viewModelScope.launch {
            var connected = false
            while (isActive) {
                try {
                    EventStream(s).events().collect { event ->
                        if (!connected) {
                            connected = true
                            _ui.update { it.copy(connectionState = "connected") }
                        }
                        handleEvent(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 断流：标记状态并延迟重连
                }
                if (!isActive) break
                connected = false
                _ui.update { it.copy(connectionState = "reconnecting") }
                delay(2000)
                loadHistory()
            }
        }
    }

    // ---------- agent / model / 档位 ----------

    private fun loadPickerData() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            coroutineScope {
                val agentsD = async { api.agents(s) }
                val modelsD = async { api.models(s) }
                val commandsD = async { api.commands(s) }
                val detailD = async { api.sessionDetail(s, sid) }

                agentsD.await().onSuccess { list ->
                    val primary = list.filter { it.mode == "primary" && !it.hidden }
                    _ui.update { it.copy(agents = primary.ifEmpty { list }) }
                }
                modelsD.await().onSuccess { list ->
                    _ui.update { it.copy(models = list.sortedBy { m -> m.providerID + m.modelID }) }
                }
                commandsD.await().onSuccess { serverCommands ->
                    // 内置命令 + 服务器命令去重合并
                    val builtin = listOf(
                        CommandInfo("compact", "压缩会话上下文"),
                        CommandInfo("mcps", "查看 MCP 服务器与工具"),
                        CommandInfo("skills", "查看可用技能"),
                    )
                    val builtinNames = builtin.map { it.name }.toSet()
                    _ui.update {
                        it.copy(commands = builtin + serverCommands.filter { c -> c.name !in builtinNames })
                    }
                }.onFailure {
                    _ui.update {
                        it.copy(
                            commands = listOf(
                                CommandInfo("compact", "压缩会话上下文"),
                                CommandInfo("mcps", "查看 MCP 服务器与工具"),
                                CommandInfo("skills", "查看可用技能"),
                            )
                        )
                    }
                }
                detailD.await().onSuccess { d ->
                    _ui.update {
                        it.copy(
                            currentAgent = d.agent ?: it.currentAgent,
                            currentProvider = d.providerID,
                            currentModel = d.modelID,
                            currentVariant = d.variant,
                        )
                    }
                }
                // 服务器工作目录（上传附件时作为 location）
                api.info(s).onSuccess { info ->
                    _ui.update { it.copy(serverDirectory = info.directory) }
                }
            }
        }
    }

    fun setAgent(agentID: String) {
        val s = server ?: return
        val sid = sessionID ?: return
        _ui.update { it.copy(currentAgent = agentID) }
        viewModelScope.launch {
            api.setAgent(s, sid, agentID).onFailure { e ->
                _ui.update { it.copy(error = "切换 Agent 失败：${e.message}") }
                loadSessionDetail()
            }
        }
    }

    fun setModel(providerID: String, modelID: String, variant: String?) {
        val s = server ?: return
        val sid = sessionID ?: return
        _ui.update {
            it.copy(currentProvider = providerID, currentModel = modelID, currentVariant = variant)
        }
        viewModelScope.launch {
            val result = api.setModel(s, sid, providerID, modelID, variant)
            result
                .onSuccess {
                    // 权威回读：确认服务器真的切过去了（这是图片/能力生效的前提）
                    val detail = api.sessionDetail(s, sid).getOrNull()
                    if (detail != null && detail.modelID != null &&
                        !detail.modelID.endsWith(modelID) && detail.modelID != modelID
                    ) {
                        _ui.update { st ->
                            st.copy(error = "模型切换未生效：服务器仍是 ${detail.modelID}")
                        }
                    }
                    applyDetail(detail)
                    // 记住该模型/档位，下次新建会话沿用
                    settings.saveDefaults(
                        dev.opencode.client.data.SettingsStore.Defaults(
                            providerID = providerID,
                            modelID = modelID,
                            variant = variant,
                            agent = _ui.value.currentAgent,
                        )
                    )
                }
                .onFailure { e ->
                    _ui.update { it.copy(error = "切换模型失败：${e.message}") }
                    loadSessionDetail()
                }
        }
    }

    private fun applyDetail(detail: dev.opencode.client.data.SessionDetail?) {
        if (detail == null) return
        _ui.update {
            it.copy(
                currentAgent = detail.agent ?: it.currentAgent,
                currentProvider = detail.providerID ?: it.currentProvider,
                currentModel = detail.modelID ?: it.currentModel,
                currentVariant = detail.variant?.takeIf { v -> v != "default" },
                usage = detail.tokens ?: it.usage,
                usageCost = detail.cost ?: it.usageCost,
            )
        }
    }

    private fun loadSessionDetail() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            api.sessionDetail(s, sid).onSuccess { d -> applyDetail(d) }
        }
    }

    // ---------- 附件 ----------

    fun onPicked(uri: Uri) {
        val context = getApplication<Application>()
        val s = server ?: return
        viewModelScope.launch {
            val result: Result<FileAttachment> = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "file"
                    var mime = resolver.getType(uri)
                        ?: android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                        ?: "application/octet-stream"

                    // 流式读取，避免 readBytes() 一次性分配导致 OOM（相册大图常见）
                    val buffer = java.io.ByteArrayOutputStream()
                    resolver.openInputStream(uri)?.use { input ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n <= 0) break
                            buffer.write(chunk, 0, n)
                            if (buffer.size() > MAX_ATTACH_BYTES) {
                                return@withContext Result.failure(
                                    IllegalStateException(
                                        "文件过大（超过 ${MAX_ATTACH_BYTES / 1024 / 1024}MB），请先压缩"
                                    )
                                )
                            }
                        }
                    } ?: return@withContext Result.failure(
                        IllegalStateException("无法打开所选文件")
                    )

                    var bytes = buffer.toByteArray()

                    // 大图降采样：压到 1600px、JPEG 85，上传更快、视觉模型更稳
                    if (mime.startsWith("image/") && bytes.size > 512 * 1024) {
                        runCatching {
                            val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (original != null) {
                                val maxSide = 1600
                                val scale = minOf(
                                    maxSide.toFloat() / original.width,
                                    maxSide.toFloat() / original.height,
                                    1f
                                )
                                val scaled = if (scale < 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(
                                        original,
                                        (original.width * scale).toInt().coerceAtLeast(1),
                                        (original.height * scale).toInt().coerceAtLeast(1),
                                        true
                                    )
                                } else original
                                java.io.ByteArrayOutputStream().use { out ->
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                    val compressed = out.toByteArray()
                                    if (compressed.size < bytes.size) {
                                        bytes = compressed
                                        mime = "image/jpeg"
                                    }
                                }
                                if (scaled !== original) scaled.recycle()
                                original.recycle()
                            }
                        }
                    }

                    // 图片与文件统一走"上传服务器 + 路径引用"（用户确认的 Web 版本答案）
                    val directory = _ui.value.serverDirectory.orEmpty()
                    val uploadedPath = api.uploadAttachment(
                        server = s,
                        directory = directory,
                        fileName = name,
                        bytes = bytes,
                    ).getOrNull()

                    Result.success(
                        FileAttachment(
                            uri = uploadedPath?.let { "file://$it" }
                                ?: ("data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)),
                            name = name,
                            mime = mime,
                            serverPath = uploadedPath,
                            isImage = mime.startsWith("image/"),
                        )
                    )
                } catch (e: OutOfMemoryError) {
                    Result.failure(IllegalStateException("内存不足，图片太大，请换小图"))
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("读取失败：${e.message ?: e.javaClass.simpleName}"))
                }
            }
            _ui.update { st ->
                result.fold(
                    onSuccess = { attachment ->
                        st.copy(attachments = st.attachments + attachment, error = null)
                    },
                    onFailure = { e ->
                        st.copy(error = e.message ?: "附件读取失败")
                    }
                )
            }
        }
    }

    fun removeAttachment(uri: String) {
        _ui.update { it.copy(attachments = it.attachments.filterNot { a -> a.uri == uri }) }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    // ---------- 发送 ----------

    fun send(text: String) {
        val s = server ?: return
        val sid = sessionID ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val files = _ui.value.attachments
        _ui.update { it.copy(sending = true, error = null) }
        val localId = "local-${System.currentTimeMillis()}"
        // 乐观上屏：发出去立刻显示，不等服务器
        _ui.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    localId, "user",
                    listOf(Block.Text("text", trimmed, 0))
                ),
                attachments = emptyList(),
            )
        }
        viewModelScope.launch {
            routeRequest(s, sid, trimmed, files)
                .onSuccess {
                    _ui.update { st ->
                        st.copy(sending = false, messages = st.messages.filterNot { m -> m.id == localId })
                    }
                    // 只刷新最近一页：全量翻页在长会话要 1~2 秒，是发送后卡顿的根因
                    refreshRecent()
                }
                .onFailure { e ->
                    failedSend = trimmed to files
                    _ui.update { st ->
                        st.copy(
                            sending = false,
                            error = "发送失败：${e.message}",
                            messages = st.messages.filterNot { m -> m.id == localId },
                            attachments = files,
                        )
                    }
                }
        }
    }

    /** 增量刷新：只拉最新一页消息合并进来（发送后、结束一轮时用），避免全量翻页阻塞。 */
    private fun refreshRecent() {
        val s = server ?: return
        val sid = sessionID ?: return
        syncInbox()
        viewModelScope.launch {
            api.recentMessages(s, sid).onSuccess { recent ->
                if (recent.isEmpty()) return@onSuccess
                // 用最新一页替换尾部，头部历史保留（避免整表重建导致闪动）
                val recentIds = recent.map { it.id }.toSet()
                val head = history.filterNot { it.id in recentIds }
                history = head + recent
                historyIds = history.map { it.id }.toSet()
                liveBlocks.keys.removeAll(historyIds)
                publish()
            }
        }
    }

    /** "/name args"：compact 走专用端点，已知命令走 command 端点，否则 prompt。 */
    private suspend fun routeRequest(
        s: RemoteServer,
        sid: String,
        text: String,
        files: List<FileAttachment>,
    ): Result<Unit> {
        if (text.startsWith("/")) {
            val name = text.trimStart('/').substringBefore(' ')
            if (name == "compact") return api.compact(s, sid)
            if (name == "mcps") return showMcps(s)
            if (name == "skills") return showSkills(s)
            val known = _ui.value.commands.any { it.name == name }
            if (known) {
                val args = text.trimStart('/').removePrefix(name).trim()
                return api.runCommand(s, sid, name, args.ifEmpty { null }, files)
            }
        }
        // 带当前选中的 agent/model（与 Web 端提交一致），避免服务器沿用旧模型
        val st = _ui.value
        val selection = st.currentProvider?.let { provider ->
            st.currentModel?.let { model ->
                ModelSelection(provider, model, st.currentVariant)
            }
        }
        return api.prompt(s, sid, text, files, st.currentAgent, selection)
    }

    /** `/mcps`：展示 MCP 服务器连接状态（客户端本地渲染，不发给模型）。 */
    private suspend fun showMcps(s: RemoteServer): Result<Unit> {
        val servers = api.mcpServers(s).getOrNull()
        _ui.update { it.copy(mcpServers = servers.orEmpty()) }
        val info = buildString {
            append("**MCP 服务器**\n\n")
            if (servers.isNullOrEmpty()) {
                append("未配置 MCP 服务器。\n")
            } else {
                servers.forEach { sv ->
                    append("- `").append(sv.name).append("` · ").append(sv.status ?: "unknown").append('\n')
                }
                append("\n点顶栏连接图标可重连失败的服务器。")
            }
        }
        appendLocalNotice(info)
        return Result.success(Unit)
    }

    /** 拉取 MCP 服务器状态（顶栏连接图标点开时调用）。 */
    fun loadMcpServers() {
        val s = server ?: return
        viewModelScope.launch {
            api.mcpServers(s).onSuccess { servers ->
                _ui.update { it.copy(mcpServers = servers) }
            }
        }
    }

    /** 同步待投递输入（启动/刷新时调用，保证重启后长按撤回依然可用）。 */
    fun syncInbox() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            api.inboxList(s, sid).onSuccess { items ->
                pendingUser.clear()
                pendingDelivery.clear()
                items.forEach { item ->
                    pendingUser[item.id] = item.text
                    pendingDelivery[item.id] = item.delivery
                }
                publish()
            }
        }
    }

    /**
     * 回退到指定消息（Web 的 revert）。
     *
     * 与 inbox 撤回不同：revert 面向已投递的消息，删除该消息之后的所有内容；
     * restoreFiles=true 时同时把文件改动还原到当时的快照。
     */
    fun revertTo(messageID: String, restoreFiles: Boolean) {
        val s = server ?: return
        val sid = sessionID ?: return
        _ui.update { it.copy(reverting = true, error = null) }
        viewModelScope.launch {
            api.revertStage(s, sid, messageID, restoreFiles)
                .onSuccess { api.revertCommit(s, sid) }
                .onSuccess {
                    _ui.update { it.copy(reverting = false, error = null) }
                    // 回退会改动历史与文件，必须全量重载
                    loadHistory()
                    syncInbox()
                }
                .onFailure { e ->
                    // 失败时清掉暂存，避免留下半成品状态
                    api.revertClear(s, sid)
                    _ui.update { it.copy(reverting = false, error = "回退失败：${e.message}") }
                }
        }
    }

    /**
     * 重连一个 MCP 服务器。
     *
     * 服务器端行为：MCP 若在 opencode 启动之后才起来，该目录的连接会一直停在 failed 不再重试，
     * 于是 code mode 里没有这个服务器的工具，工具卡片只能显示 execute。手动触发 connect 即可恢复。
     */
    fun reconnectMcp(name: String) {
        val s = server ?: return
        val dir = _ui.value.serverDirectory
        _ui.update { it.copy(reconnectingMcp = name) }
        viewModelScope.launch {
            val result = api.reconnectMcp(s, name, dir)
            result
                .onSuccess {
                    _ui.update { it.copy(reconnectingMcp = null, error = null) }
                    // 重连后刷新状态（status 会变成 connected）
                    api.mcpServers(s).onSuccess { servers ->
                        _ui.update { it.copy(mcpServers = servers) }
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(reconnectingMcp = null, error = "重连 $name 失败：${e.message}") }
                }
        }
    }

    /** `/skills`：展示已加载的技能。 */
    private suspend fun showSkills(s: RemoteServer): Result<Unit> {
        val list = api.skills(s).getOrNull()
        val info = buildString {
            append("**Skills**\n\n")
            if (list.isNullOrEmpty()) {
                append("未发现可用技能。\n")
            } else {
                list.forEach { sk ->
                    append("- `").append(sk.name).append('`')
                    sk.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append('\n')
                }
            }
        }
        appendLocalNotice(info)
        return Result.success(Unit)
    }

    /** 本地提示：以合成消息形式插入当前会话视图（不发给模型，仅本地展示）。 */
    private fun appendLocalNotice(text: String) {
        val id = "local-notice-${System.currentTimeMillis()}"
        history = history + ChatMessage(id, "assistant", listOf(Block.Text("text", text, 0)))
        historyIds = historyIds + id
        publish()
    }

    fun interrupt() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            api.interrupt(s, sid).onFailure { e ->
                _ui.update { it.copy(error = "中断失败：${e.message}") }
            }
        }
    }

    /** 撤回一条尚未投递的输入。 */
    fun cancelInbox(inboxID: String) {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            api.cancelInbox(s, sid, inboxID)
                .onSuccess {
                    pendingUser.remove(inboxID)
                    pendingDelivery.remove(inboxID)
                    publish()
                }
                .onFailure { e -> _ui.update { it.copy(error = "撤回失败：${e.message}") } }
        }
    }

    /** 切换待投递输入的投递方式：插入（steer）⇄ 排队（queue）。 */
    fun toggleInboxDelivery(inboxID: String) {
        val s = server ?: return
        val sid = sessionID ?: return
        val current = pendingDelivery[inboxID] ?: "steer"
        val next = if (current == "steer") "queue" else "steer"
        viewModelScope.launch {
            api.updateInboxDelivery(s, sid, inboxID, next)
                .onSuccess {
                    pendingDelivery[inboxID] = next
                    _ui.update { it.copy(error = null) }
                }
                .onFailure { e -> _ui.update { it.copy(error = "切换投递失败：${e.message}") } }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    /** 重试上次失败的发送。 */
    fun retryFailedSend() {
        val (text, files) = failedSend ?: return
        failedSend = null
        _ui.update { it.copy(error = null, attachments = files) }
        send(text)
    }

    // ---------- SSE 事件处理 ----------

    private fun handleEvent(event: OpenCodeEvent) {
        val sid = sessionID ?: return
        val data = event.data
        if (data.str("sessionID") != sid) return

        when (event.type) {
            "session.text.delta", "session.reasoning.delta" -> {
                val mid = data.str("assistantMessageID") ?: return
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val delta = data.str("delta") ?: return
                val isReasoning = event.type == "session.reasoning.delta"
                // 思考与正文的 ordinal 各自从 0 开始，必须用不同的 key 命名空间，
                // 否则正文会覆盖思考块（表现为"思考完折叠块直接消失"，刷新后才又出现）
                val key = if (isReasoning) "r:$ordinal" else "t:$ordinal"
                val kind = if (isReasoning) "reasoning" else "text"
                if (isReasoning) {
                    // started 事件偶尔缺失，导致思考耗时一直空白（卡住）；
                    // 用首个 delta 兜底记录起点，与服务器 part.time.created 基本一致
                    reasoningStart.putIfAbsent(
                        "$mid:$ordinal",
                        event.created ?: System.currentTimeMillis(),
                    )
                }
                upsertBlock(mid, key) { existing ->
                    (existing as? Block.Text)?.copy(text = existing.text + delta)
                        ?: Block.Text(kind, delta, ordinal)
                }
                publish()
            }
            "session.text.ended" -> {
                val mid = data.str("assistantMessageID") ?: return
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val text = data.str("text") ?: return
                upsertBlock(mid, "t:$ordinal") { existing ->
                    (existing as? Block.Text)?.copy(text = text, type = "text")
                        ?: Block.Text("text", text, ordinal)
                }
                publish()
            }
            "session.tool.input.started" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                // 兜底起点：真正的锚点在 tool.called（对应服务器 part.time.ran）
                toolStart.putIfAbsent(callId, event.created ?: System.currentTimeMillis())
                upsertBlock(mid, "c:$callId") { existing ->
                    val cur = (existing as? Block.Tool)?.call
                    when {
                        cur == null ->
                            Block.Tool(ToolCall(callId, data.str("name") ?: "tool", "running"))
                        // 已识别出 MCP 工具名的不回退：started 事件偶尔晚到，
                        // 会把已经显示的 mcp + 工具名覆盖回 execute（刷新前一直错）
                        cur.name == "mcp" || cur.title != null -> Block.Tool(cur)
                        else -> Block.Tool(
                            cur.copy(name = data.str("name") ?: cur.name, state = "running")
                        )
                    }
                }
                publish()
            }
            "session.tool.input.delta" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val delta = data.str("delta") ?: data.str("text") ?: return
                upsertTool(mid, callId) { cur ->
                    val merged = (cur.input ?: "") + delta
                    // 输入边到达边尝试识别：一旦 code 里出现 tools["srv"].toolName，
                    // 立刻把卡片从 execute 切到 mcp + 真实工具名，不必等 input.ended
                    val mcpTool = if (cur.name == "execute" || cur.name == "tool") {
                        extractMcpToolNameFromText(merged)
                    } else null
                    cur.copy(
                        input = merged,
                        name = if (mcpTool != null) "mcp" else cur.name,
                        title = mcpTool ?: cur.title,
                    )
                }
                publish()
            }
            "session.tool.input.ended" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val text = data.str("text") ?: return
                // 输入完整到手：用完整文本重新识别（input.delta 阶段可能只拿到部分）
                val mcpTool = extractMcpToolNameFromRaw(text)
                upsertTool(mid, callId) {
                    it.copy(
                        input = text,
                        name = when {
                            mcpTool != null -> "mcp"
                            // 已经是 mcp 的保持（delta 阶段已识别，完整文本解析失败也不回退）
                            it.name == "mcp" -> "mcp"
                            it.name == "tool" || it.name == "execute" ->
                                inferToolNameFromInput(parseToolInput(text)) ?: it.name
                            else -> it.name
                        },
                        // 已识别过的 title 不回退成 null
                        title = mcpTool ?: it.title,
                    )
                }
                publish()
            }
            "session.tool.called" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val inputObj = data["input"] as? JsonObject
                // 计时锚点：called 对应服务器 part.time.ran（输入流式传输已完成），
                // 覆盖 input.started 的临时起点，让实时耗时与刷新后的历史耗时一致
                toolStart[callId] = event.created ?: System.currentTimeMillis()
                val declared = data.str("name")
                val mcpTool = if (declared == "execute") extractMcpToolName(inputObj) else null
                upsertTool(mid, callId) {
                    it.copy(
                        // 工具名必须在这里回写：input.started 事件偶尔不带 name，
                        // 不补就会一直显示占位名 "tool"，直到历史刷新才变回 edit/write
                        name = when {
                            mcpTool != null -> "mcp"
                            declared != null && declared != "tool" -> declared
                            else -> inferToolNameFromInput(inputObj) ?: it.name
                        },
                        input = inputObj?.toString() ?: it.input,
                        title = mcpTool ?: deriveToolTitle(inputObj) ?: it.title,
                        // 真实开始执行：覆盖此前的 running 占位状态
                        state = "running",
                    )
                }
                publish()
            }
            "session.tool.success" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val metadata = data["metadata"] as? JsonObject
                val outputText = joinContentText(data["content"] as? JsonArray)
                // 命令转入后台（metadata 只有 shellID，无 exit，输出为 moved to the background）
                val background = metadata?.str("shellID") != null &&
                    (metadata["exit"] as? JsonPrimitive) == null &&
                    outputText?.startsWith("Command moved to the background") == true
                upsertTool(mid, callId) {
                    it.copy(
                        // 后台运行中不标记完成；普通命令仍按 success
                        state = if (background) "running" else "success",
                        output = outputText ?: it.output,
                        exit = (metadata?.get("exit") as? JsonPrimitive)?.content?.toIntOrNull(),
                        durationMs = if (background) null else (durationFor(callId, event.created) ?: it.durationMs),
                        truncated = (metadata?.get("truncated") as? JsonPrimitive)?.content == "true",
                        fileChanges = parseFileChanges(metadata),
                        background = background,
                        shellID = metadata?.str("shellID") ?: it.shellID,
                        title = if (background) "已转入后台" else it.title,
                    )
                }
                publish()
            }
            "session.tool.failed" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                upsertTool(mid, callId) {
                    it.copy(
                        state = "failed",
                        output = joinContentText(data["content"] as? JsonArray) ?: it.output,
                        durationMs = durationFor(callId, event.created) ?: it.durationMs,
                    )
                }
                publish()
            }
            "session.reasoning.started" -> {
                val mid = data.str("assistantMessageID") ?: return
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                reasoningStart.putIfAbsent(
                    "$mid:$ordinal",
                    event.created ?: System.currentTimeMillis(),
                )
            }
            "session.reasoning.ended" -> {
                val mid = data.str("assistantMessageID") ?: return
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val text = data.str("text")
                val duration = reasoningStart.remove("$mid:$ordinal")?.let { start ->
                    event.created?.let { end -> (end - start).takeIf { it >= 0 } }
                }
                // 服务器常见：思考部件只有空白内容（" "）。此时不落地任何块，
                // 避免出现"展开了却是空的"折叠框（Web 端同样不显示）
                if (text != null && text.isBlank()) {
                    liveBlocks[mid]?.remove("r:$ordinal")
                    publish()
                    return
                }
                // 思考结束：按 reasoning 命名空间更新，保留 started 记录的耗时
                upsertBlock(mid, "r:$ordinal") { existing ->
                    (existing as? Block.Text)?.copy(
                        text = text?.takeIf { it.isNotBlank() } ?: existing.text,
                        type = "reasoning",
                        durationMs = duration ?: existing.durationMs,
                    ) ?: Block.Text("reasoning", text.orEmpty(), ordinal, duration)
                }
                publish()
            }
            "session.step.started" -> {
                // 新的一步 = 新的 LLM 请求
                liveApiCalls += 1
                _ui.update { it.copy(apiCalls = liveApiCalls) }
            }
            "session.step.ended" -> {
                val tokens = data["tokens"] as? JsonObject
                if (tokens != null) {
                    // 官方 contextUsage 口径（TUI packages/tui/src/util/session.ts）:
                    // tokens = input + output + reasoning + cache.read + cache.write
                    val total = (tokens.num("input") ?: 0) + (tokens.num("output") ?: 0) +
                        (tokens.num("reasoning") ?: 0) +
                        ((tokens["cache"] as? JsonObject)?.num("read") ?: 0) +
                        ((tokens["cache"] as? JsonObject)?.num("write") ?: 0)
                    _ui.update { st ->
                        st.copy(contextTokens = total.takeIf { it > 0 } ?: st.contextTokens)
                    }
                }
                _ui.update { it.copy(apiCalls = liveApiCalls) }
            }
            "session.usage.updated" -> {
                // 该事件 tokens 是「会话累计账单口径」（cache.read 跨步累加），
                // 不能用于上下文占用。上下文只认 step.ended（单步）与历史恢复。
            }
            // shell 工具的实时输出走独立 shell 事件流（不经 tool.progress），
            // ended 带完整 output；App 在 success 时往往只拿到 "(no output)" 占位
            "session.shell.ended" -> {
                val shellID = (data["shell"] as? JsonObject)?.str("id") ?: return
                val outputObj = data["output"] as? JsonObject
                val output = outputObj?.str("output") ?: return
                val truncated = outputObj.str("truncated") == "true"
                // shell 真正结束：用它的 exit 状态决定卡片终态（含耗时），
                // 避免 tool.success 先到时把仍在运行的命令显示成"已完成"
                val exitCode = (outputObj["exit"] as? JsonPrimitive)?.content?.toIntOrNull()
                synchronized(lastShellCall) {
                    val callId = lastShellCall[shellID] ?: lastShellCall.values.lastOrNull() ?: return
                    val mid = lastShellCallMid[callId] ?: return
                    upsertTool(mid, callId) {
                        it.copy(
                            state = if (exitCode == null || exitCode == 0) "success" else "failed",
                            output = if (output.length >= (it.output?.length ?: 0)) output else it.output,
                            truncated = truncated || it.truncated,
                            durationMs = if (it.durationMs == null && it.timestampMs != null && event.created != null) {
                                (event.created - it.timestampMs).takeIf { d -> d > 0 }
                            } else it.durationMs,
                        )
                    }
                }
                publish()
            }
            "session.tool.progress" -> {
                // shell 工具的 progress.metadata 带 shellID，建立 shellID -> callID 映射
                val metadata = data["metadata"] as? JsonObject
                val shellID = metadata?.str("shellID")
                val callId = data.str("id") ?: return
                if (shellID != null) {
                    synchronized(lastShellCall) { lastShellCall[shellID] = callId }
                }
                val mid = data.str("assistantMessageID") ?: return
                synchronized(lastShellCall) { lastShellCallMid[callId] = mid }
            }
            "session.inbox.enqueued" -> {
                // 事件结构可能是 {inboxID, item} 也可能直接就是 Inbox.Item 本身，
                // 两种都兼容，避免字段名不匹配时漏处理（表现为长按只有复制、无法撤回）
                val item = (data["item"] as? JsonObject) ?: data
                val inboxID = data.str("inboxID") ?: item.str("id") ?: return
                if (item.str("type") != "user") return
                val text = ((item["payload"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: return
                pendingUser[inboxID] = text
                pendingDelivery[inboxID] = data.str("delivery")
                    ?: item.str("delivery") ?: "steer"
                // 撤下乐观气泡（文本匹配的第一条 local-）
                _ui.update { st ->
                    val idx = st.messages.indexOfFirst {
                        it.id.startsWith("local-") && it.textContent() == text
                    }
                    if (idx >= 0) st.copy(messages = st.messages.toMutableList().apply { removeAt(idx) })
                    else st
                }
                publish()
            }
            "session.inbox.delivered" -> {
                val inboxID = data.str("inboxID") ?: return
                if (pendingUser.remove(inboxID) != null) {
                    pendingDelivery.remove(inboxID)
                    publish()
                    refreshRecent()
                }
            }
            "session.inbox.cancelled" -> {
                val inboxID = data.str("inboxID") ?: return
                if (pendingUser.remove(inboxID) != null) {
                    pendingDelivery.remove(inboxID)
                    publish()
                }
            }
            "session.execution.started" -> {
                _ui.update { it.copy(streaming = true) }
            }
            "session.execution.succeeded", "session.execution.failed",
            "session.execution.interrupted" -> {
                // interrupted：用户点停止后服务器的收尾事件，必须复位 streaming
                _ui.update { it.copy(streaming = false) }
                refreshRecent()
                loadSessionDetail()
            }
            // 压缩上下文：给出明确进度与结果反馈
            "session.compaction.started" -> {
                _ui.update { it.copy(compacting = true, error = null) }
            }
            "session.compaction.ended" -> {
                _ui.update { it.copy(compacting = false) }
                loadHistory()
                loadSessionDetail()
            }
            "session.compaction.failed" -> {
                val msg = ((data["error"] as? JsonObject)?.str("message"))
                    ?: data.str("message")
                    ?: "未知错误"
                _ui.update { it.copy(compacting = false, error = "压缩失败：$msg") }
            }
            "session.model.selected", "session.agent.selected" -> loadSessionDetail()
        }
    }

    private fun durationFor(callId: String, endMs: Long?): Long? {
        val start = toolStart.remove(callId) ?: return null
        val end = endMs ?: return null
        return (end - start).takeIf { it >= 0 }
    }

    /** 取或建块后用 lambda 更新（LinkedHashMap.put 已有键保持原插入位置）。
     *  历史里已有的消息不再进 live 列表——迟到事件是 LazyColumn 重复 key 崩溃的根因。 */
    private fun upsertBlock(mid: String, key: String, update: (Block?) -> Block) {
        if (mid in historyIds) return
        val blocks = liveBlocks.getOrPut(mid) { LinkedHashMap() }
        blocks[key] = update(blocks[key])
    }

    private fun upsertTool(mid: String, callId: String, update: (ToolCall) -> ToolCall) {
        upsertBlock(mid, "c:$callId") { existing ->
            when (existing) {
                is Block.Tool -> Block.Tool(update(existing.call))
                else -> Block.Tool(update(ToolCall(callId, "tool", "running")))
            }
        }
    }

    private fun loadHistory() {
        val s = server ?: return
        val sid = sessionID ?: return
        viewModelScope.launch {
            // 首屏优先：第一页到手立即渲染，后台继续翻页补齐统计（B 方案）
            api.messages(s, sid, onFirstPage = { firstPage ->
                history = firstPage
                historyIds = firstPage.map { it.id }.toSet()
                liveBlocks.keys.removeAll(historyIds)
                publish()
            }).onSuccess { loaded ->
                // 长会话只保留最近 N 条，避免上千条全部驻留内存
                history = if (loaded.size > maxHistoryInMemory) {
                    loaded.takeLast(maxHistoryInMemory)
                } else loaded
                historyIds = history.map { it.id }.toSet()
                liveBlocks.keys.removeAll(historyIds)
                // API 次数 = 该会话 assistant 消息数（stats.steps 是全局口径，不适用单会话）
                val assistantCount = loaded.count { it.role == "assistant" }
                liveApiCalls = maxOf(liveApiCalls, assistantCount)
                // 上下文占用：从历史恢复（官方 contextUsage —— 最后一条带 tokens 的 assistant，
                // input+output+reasoning+cache.read+cache.write，即打开会话立即可见）
                loaded.lastOrNull { it.role == "assistant" && it.totalTokens != null }?.let { m ->
                    val total = m.totalTokens!!
                    if (total > 0) _ui.update { it.copy(contextTokens = total) }
                }
                _ui.update { it.copy(apiCalls = liveApiCalls) }
                publish()
                // 累计用量（输入/输出/思考/缓存）来自会话详情；历史加载后主动拉一次，
                // 保证打开会话时明细就有数据（之前只在打开瞬间拉，常为 null）
                api.sessionDetail(s, sid).onSuccess { applyDetail(it) }
            }
        }
    }

    /**
     * 历史 + 正在流式生成的合成消息 + 待投递输入，合并为 UI 列表。
     *
     * 流式期间 delta 事件非常密集（一次回复可达数百次），每次都全量重建 UI 列表会让
     * Compose 反复重排、Markwon 反复解析 Markdown。这里做 32ms（≈30fps）合并刷新：
     * 状态照常累积，只限制 UI 刷新频率；非流式时立即刷新保证交互即时。
     */
    private fun publish() {
        if (streamingNow()) {
            if (publishScheduled) return
            publishScheduled = true
            viewModelScope.launch {
                // 32ms（约 30fps）：流式期间 Markdown/表格重解析开销大，
                // 提高间隔可显著减轻表格抖动与闪烁
                kotlinx.coroutines.delay(32)
                publishScheduled = false
                publishNow()
            }
            return
        }
        publishNow()
    }

    private fun streamingNow(): Boolean = _ui.value.streaming

    private fun publishNow() {
        publishScheduled = false
        val live = liveBlocks.map { (mid, blocks) ->
            ChatMessage(
                id = mid,
                role = "assistant",
                blocks = blocks.values.filter {
                    when (it) {
                        // blank 也算空：服务器会给出 " " 这类占位，不能渲染成空折叠框
                        is Block.Text -> it.text.isNotBlank()
                        is Block.Tool -> true
                    }
                }
            )
        }.filter { it.blocks.isNotEmpty() }
        val pending = pendingUser.map { (id, text) ->
            ChatMessage(id, "user", listOf(Block.Text("text", text, 0)))
        }
        _ui.update {
            // distinctBy 兜底：任何来源的重复 id 都不允许进列表（LazyColumn key 崩溃防护）
            it.copy(
                messages = (history + live + pending).distinctBy { m -> m.id },
                // 让长按面板能识别"尚未投递"的输入，从而提供撤回/插队
                pendingInputs = pendingUser.toMap(),
                pendingDeliveries = pendingDelivery.toMap(),
            )
        }
    }

    companion object {
        private const val MAX_ATTACH_BYTES = 20 * 1024 * 1024
    }
}
