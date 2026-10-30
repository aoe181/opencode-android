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
import dev.opencode.client.data.ModelInfo
import dev.opencode.client.data.ModelSelection
import dev.opencode.client.data.OpenCodeApi
import dev.opencode.client.data.OpenCodeEvent
import dev.opencode.client.data.RemoteServer
import dev.opencode.client.data.Tokens
import dev.opencode.client.data.ToolCall
import dev.opencode.client.data.deriveToolTitle
import dev.opencode.client.data.extractMcpToolName
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
)

/**
 * 会话详情：历史消息 + SSE 实时流 + agent/模型/档位切换 + 附件 + 斜杠命令。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val api = OpenCodeApi()
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
        startStream()
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
        val info = buildString {
            append("**MCP 服务器**\n\n")
            if (servers.isNullOrEmpty()) {
                append("未配置 MCP 服务器。\n")
            } else {
                servers.forEach { sv ->
                    append("- `").append(sv.name).append("` · ").append(sv.status ?: "unknown").append('\n')
                }
            }
        }
        appendLocalNotice(info)
        return Result.success(Unit)
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
                val kind = if (event.type == "session.text.delta") "text" else "reasoning"
                upsertBlock(mid, "t:$ordinal") { existing ->
                    (existing as? Block.Text)?.copy(text = existing.text + delta)
                        ?: Block.Text(kind, delta, ordinal)
                }
                publish()
            }
            "session.text.ended" -> {
                val mid = data.str("assistantMessageID") ?: return
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val text = data.str("text") ?: return
                upsertBlock(mid, "t:$ordinal") { Block.Text("text", text, ordinal) }
                publish()
            }
            "session.tool.input.started" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                toolStart[callId] = event.created ?: System.currentTimeMillis()
                upsertBlock(mid, "c:$callId") { existing ->
                    (existing as? Block.Tool)?.call?.copy(name = data.str("name") ?: existing.call.name)
                        ?.let { Block.Tool(it) }
                        ?: Block.Tool(ToolCall(callId, data.str("name") ?: "tool", "running"))
                }
                publish()
            }
            "session.tool.input.delta" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val delta = data.str("delta") ?: data.str("text") ?: return
                upsertTool(mid, callId) { it.copy(input = (it.input ?: "") + delta) }
                publish()
            }
            "session.tool.input.ended" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val text = data.str("text") ?: return
                upsertTool(mid, callId) { it.copy(input = text) }
                publish()
            }
            "session.tool.called" -> {
                val mid = data.str("assistantMessageID") ?: return
                val callId = data.str("id") ?: return
                val inputObj = data["input"] as? JsonObject
                toolStart.putIfAbsent(callId, event.created ?: System.currentTimeMillis())
                val mcpTool = if (data.str("name") == "execute") extractMcpToolName(inputObj) else null
                upsertTool(mid, callId) {
                    it.copy(
                        name = if (mcpTool != null) "mcp" else it.name,
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
                reasoningStart["$mid:$ordinal"] = event.created ?: System.currentTimeMillis()
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
                    liveBlocks[mid]?.remove("t:$ordinal")
                    publish()
                    return
                }
                upsertBlock(mid, "t:$ordinal") { existing ->
                    (existing as? Block.Text)?.copy(
                        text = text?.takeIf { it.isNotBlank() } ?: existing.text,
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
                val inboxID = data.str("inboxID") ?: return
                val item = data["item"] as? JsonObject ?: return
                if (item.str("type") != "user") return
                val text = ((item["payload"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: return
                pendingUser[inboxID] = text
                pendingDelivery[inboxID] = data.str("delivery") ?: "steer"
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
     * Compose 反复重排、Markwon 反复解析 Markdown。这里做 16ms（≈60fps）合并刷新：
     * 状态照常累积，只限制 UI 刷新频率；非流式时立即刷新保证交互即时。
     */
    private fun publish() {
        if (streamingNow()) {
            if (publishScheduled) return
            publishScheduled = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(16)
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
            it.copy(messages = (history + live + pending).distinctBy { m -> m.id })
        }
    }

    companion object {
        private const val MAX_ATTACH_BYTES = 20 * 1024 * 1024
    }
}
