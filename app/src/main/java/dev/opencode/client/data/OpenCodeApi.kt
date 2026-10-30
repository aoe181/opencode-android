package dev.opencode.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 一台 opencode serve 服务器：基地址 + 可选的 Basic Auth 密码。 */
data class RemoteServer(
    val baseUrl: String,
    val password: String,
)

/** /api/info 的宽松解析结果：只提取展示需要的字段，其余留在 raw 里。 */
data class ServerInfo(val raw: JsonObject) {

    private val payload: JsonObject
        get() = (raw["data"] as? JsonObject) ?: raw

    val version: String? get() = payload.string("version")

    /** 服务器临时目录（附件上传目标，Web 端 /api/experimental/fs/write 的根）。 */
    val tmpPath: String? get() = (payload["paths"] as? JsonObject)?.string("tmp")

    /** 服务器工作目录（location.directory，fs/write 需要）。 */
    val directory: String? get() =
        payload.string("directory")
            ?: ((payload["location"] as? JsonObject)?.string("directory"))

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]
            if (value is JsonPrimitive && value.isString) return value.content
        }
        return null
    }
}

data class SessionSummary(
    val id: String,
    val title: String,
)

/**
 * OpenCode HTTP API 的最小客户端。
 * 完整接口契约见 https://opencode.ai/v2/openapi.json
 */
class OpenCodeApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(server: RemoteServer, path: String): Request.Builder {
        val builder = Request.Builder().url("${server.baseUrl}$path")
        if (server.password.isNotEmpty()) {
            // 与 `curl -u opencode:<密码>` 一致
            builder.header("Authorization", Credentials.basic("opencode", server.password))
        }
        return builder
    }

    private suspend fun get(server: RemoteServer, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(server, path).build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    body
                }
            }
        }

    private suspend fun post(server: RemoteServer, path: String, body: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = request(server, path)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    text
                }
            }
        }

    suspend fun info(server: RemoteServer): Result<ServerInfo> =
        get(server, "/api/info").mapCatching { body ->
            val element = json.parseToJsonElement(body)
            ServerInfo(element as? JsonObject ?: JsonObject(emptyMap()))
        }

    /**
     * 会话列表（分页）。返回 (会话, 是否有更多)，cursor 由调用方保存下一页。
     * 服务器默认只返回最新 50 个会话，长列表必须翻页才能看到旧会话。
     */
    suspend fun sessions(
        server: RemoteServer,
        limit: Int = 50,
        cursor: String? = null,
    ): Result<Pair<List<SessionSummary>, String?>> =
        get(
            server,
            buildString {
                append("/api/session?limit=").append(limit)
                if (cursor != null) append("&cursor=").append(cursor)
            }
        ).mapCatching { body ->
            val root = json.parseToJsonElement(body)
            val arr = when {
                root is JsonArray -> root
                root is JsonObject && root["data"] is JsonArray -> root["data"] as JsonArray
                else -> JsonArray(emptyList())
            }
            val list = arr.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val title = (obj["title"] as? JsonPrimitive)?.content
                SessionSummary(id, title?.takeIf { it.isNotBlank() } ?: "(未命名会话)")
            }
            val next = (root as? JsonObject)?.let { r ->
                (r["cursor"] as? JsonObject)?.get("next")
                    ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            }
            list to next
        }

    suspend fun createSession(server: RemoteServer): Result<String> =
        post(server, "/api/session", "{}").mapCatching { body ->
            parseSessionId(body) ?: error("响应缺少会话 ID")
        }

    /** 历史消息：服务器 limit 上限 200，需按 cursor 翻页取全量（API 次数等统计依赖完整历史）。 */
    /**
     * 历史消息，分两阶段返回（首屏优先）：
     * 1. [onFirstPage]：第一页（200 条）立刻回调，UI 可以立即渲染；
     * 2. 返回值：全部翻页后的完整列表（用于统计 api 次数等）。
     *
     * 长会话（上千条）串行翻页会阻塞 1~2 秒，首屏优先能让用户几乎无感。
     */
    suspend fun messages(
        server: RemoteServer,
        sessionID: String,
        onFirstPage: (suspend (List<ChatMessage>) -> Unit)? = null,
    ): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val all = ArrayList<ChatMessage>(512)
                var cursor: String? = null
                var pages = 0
                do {
                    val path = buildString {
                        append("/api/session/").append(sessionID).append("/message?limit=200")
                        // cursor 不能与 order 同时传（服务器会返回 InvalidCursorError）
                        if (cursor == null) append("&order=desc") else append("&cursor=").append(cursor)
                    }
                    val body = get(server, path).getOrThrow()
                    val page = parseMessages(body)
                    all += page
                    if (pages == 0 && onFirstPage != null) {
                        // 第一页已到手：先让 UI 显示（此时是倒序的第一页，需反转）
                        onFirstPage(page.reversed())
                    }
                    val root = json.parseToJsonElement(body) as? JsonObject
                    cursor = (root?.get("cursor") as? JsonObject)
                        ?.get("next")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    pages++
                } while (!cursor.isNullOrBlank() && pages < MAX_HISTORY_PAGES)
                all.reversed() // desc 拉取的，反转成时间正序
            }
        }

    /** 发送一轮对话输入（可带附件）。409 表示会话正在执行中。 */
    /** 发送一轮对话输入（可带附件）。同时带上 metadata.agent/model，与 Web 端 admission 一致。 */
    suspend fun prompt(
        server: RemoteServer,
        sessionID: String,
        text: String,
        files: List<FileAttachment> = emptyList(),
        agent: String? = null,
        model: ModelSelection? = null,
    ): Result<Unit> =
        post(server, "/api/session/$sessionID/prompt", promptBody(text, files, agent, model)).map { }

    /** 执行斜杠命令（如 /init /review）。 */
    suspend fun runCommand(
        server: RemoteServer,
        sessionID: String,
        name: String,
        text: String? = null,
        files: List<FileAttachment> = emptyList(),
    ): Result<Unit> {
        val body = buildMap<String, JsonElement> {
            put("name", JsonPrimitive(name))
            if (!text.isNullOrBlank()) put("text", JsonPrimitive(text))
            if (files.isNotEmpty()) put("files", attachmentsJson(files))
        }
        return post(server, "/api/session/$sessionID/command", JsonObject(body).toString()).map { }
    }

    /** 切换会话 Agent。 */
    suspend fun setAgent(server: RemoteServer, sessionID: String, agentID: String): Result<Unit> =
        post(server, "/api/session/$sessionID/agent", """{"agent":"$agentID"}""").map { }

    /** 切换会话模型与思考档位（variant 为 null 表示用模型默认）。 */
    suspend fun setModel(
        server: RemoteServer,
        sessionID: String,
        providerID: String,
        modelID: String,
        variant: String?,
    ): Result<Unit> {
        val model = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(modelID))
            put("providerID", JsonPrimitive(providerID))
            variant?.let { put("variant", JsonPrimitive(it)) }
        }
        val body = JsonObject(mapOf("model" to JsonObject(model)))
        return post(server, "/api/session/$sessionID/model", body.toString()).map { }
    }

    suspend fun sessionDetail(server: RemoteServer, sessionID: String): Result<SessionDetail> =
        get(server, "/api/session/$sessionID").mapCatching { body ->
            parseSessionDetail(body) ?: error("会话详情解析失败")
        }

    suspend fun agents(server: RemoteServer): Result<List<AgentInfo>> =
        get(server, "/api/agent").mapCatching(::parseAgents)

    suspend fun models(server: RemoteServer): Result<List<ModelInfo>> =
        get(server, "/api/model").mapCatching(::parseModels)

    suspend fun commands(server: RemoteServer): Result<List<CommandInfo>> =
        get(server, "/api/command").mapCatching(::parseCommands)

    /** MCP 服务器列表（/api/mcp）。 */
    suspend fun mcpServers(server: RemoteServer): Result<List<McpServerInfo>> =
        get(server, "/api/mcp").mapCatching(::parseMcpServers)

    /** 技能列表（/api/skill）。 */
    suspend fun skills(server: RemoteServer): Result<List<SkillInfo>> =
        get(server, "/api/skill").mapCatching(::parseSkills)

    private fun attachmentsJson(files: List<FileAttachment>): JsonArray =        JsonArray(files.map { f ->
            // 客户端只发 uri（同 Web 的 admission.files）：服务器 materializeAttachment
            // 会按 data: / file: 前缀自行解码，额外字段会被 additionalProperties:false 拒绝
            JsonObject(buildMap<String, JsonElement> {
                put("uri", JsonPrimitive(f.uri))
                put("name", JsonPrimitive(f.name))
            })
        })

    private fun promptBody(
        text: String,
        files: List<FileAttachment>,
        agent: String?,
        model: ModelSelection?,
    ): String {
        // Web 同款（request.ts）：所有附件统一在 text 末尾追加 "Attached file: `path`" 引用，
        // 模型据此读取服务器上的文件（图片同样适用）
        val pathRefs = files.filter { it.serverPath != null }
        val fullText = if (pathRefs.isEmpty()) {
            text
        } else {
            buildString {
                append(text)
                pathRefs.forEach { append("\nAttached file: `").append(it.serverPath).append('`') }
            }
        }
        val body = buildMap<String, JsonElement> {
            put("text", JsonPrimitive(fullText))
            if (files.isNotEmpty()) put("files", attachmentsJson(files))
            // Web 端每次提交都带 metadata.agent/model（admission.metadata），
            // 否则服务器可能沿用会话里旧的模型（切换过模型时会用错）
            if (agent != null || model != null) {
                val metadata = buildMap<String, JsonElement> {
                    agent?.let { put("agent", JsonPrimitive(it)) }
                    model?.let {
                        put("model", JsonObject(buildMap<String, JsonElement> {
                            put("providerID", JsonPrimitive(it.providerID))
                            put("id", JsonPrimitive(it.modelID))
                            it.variant?.let { v -> put("variant", JsonPrimitive(v)) }
                        }))
                    }
                }
                put("metadata", JsonObject(metadata))
            }
        }
        return JsonObject(body).toString()
    }

    /** 只取最新一页消息（200 条）：发送后/一轮结束时增量刷新用，避免长会话全量翻页。 */
    suspend fun recentMessages(server: RemoteServer, sessionID: String): Result<List<ChatMessage>> =
        get(server, "/api/session/$sessionID/message?order=desc&limit=200")
            .mapCatching { body -> parseMessages(body).reversed() }

    /** 上传文件到服务器临时目录（Web 同款：POST /api/experimental/fs/write）。返回服务器绝对路径。 */
    suspend fun uploadAttachment(
        server: RemoteServer,
        directory: String,
        fileName: String,
        bytes: ByteArray,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // 与 Web 一致：<server tmp>/uploads/<uuid>/<filename>，一个目录一次上传避免同名冲突
            val info = info(server).getOrThrow()
            val tmp = info.tmpPath ?: error("服务器未返回 tmp 路径")
            val uuid = java.util.UUID.randomUUID().toString()
            val target = "$tmp/uploads/$uuid/$fileName"

            val base = server.baseUrl.trimEnd('/').toHttpUrlOrNull()
                ?: error("服务器地址无效")
            val url = base.newBuilder()
                .addPathSegments("api/experimental/fs/write")
                .addQueryParameter("path", target)
                .addQueryParameter("location[directory]", directory)
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            if (server.password.isNotEmpty()) {
                reqBuilder.header(
                    "Authorization",
                    Credentials.basic("opencode", server.password)
                )
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("上传失败 HTTP ${resp.code}: ${body.take(200)}")
                val json = json.parseToJsonElement(body) as? JsonObject
                ((json?.get("data") as? JsonObject)?.str("path")) ?: target
            }
        }
    }

    /** 重命名会话。 */
    suspend fun renameSession(server: RemoteServer, sessionID: String, title: String): Result<Unit> {
        val body = kotlinx.serialization.json.JsonObject(
            mapOf("title" to kotlinx.serialization.json.JsonPrimitive(title))
        )
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = request(server, "/api/session/$sessionID")
                    .patch(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                }
            }
        }
    }

    /** 删除会话（含其子会话）。 */
    suspend fun deleteSession(server: RemoteServer, sessionID: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(server, "/api/session/$sessionID").delete().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    }
            }
        }

    suspend fun interrupt(server: RemoteServer, sessionID: String): Result<Unit> =
        post(server, "/api/session/$sessionID/interrupt", "{}").map { }

    /** 压缩会话上下文。 */
    suspend fun compact(server: RemoteServer, sessionID: String): Result<Unit> =
        post(server, "/api/session/$sessionID/compact", "{}").map { }

    /** 撤回（取消）一条尚未投递的输入。 */
    suspend fun cancelInbox(server: RemoteServer, sessionID: String, inboxID: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(server, "/api/session/$sessionID/inbox/$inboxID").delete().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    }
            }
        }

    /** 切换待投递输入的投递方式：steer（打断插入）⇄ queue（排队）。 */
    suspend fun updateInboxDelivery(
        server: RemoteServer,
        sessionID: String,
        inboxID: String,
        delivery: String,
    ): Result<Unit> =
        post(server, "/api/session/$sessionID/inbox/$inboxID", """{"delivery":"$delivery"}""").map { }

    /**
     * 会话列表解析。响应可能是裸数组，也可能是 { data: [...] } 包裹，两种都兼容。
     */
    private fun parseSessions(body: String): List<SessionSummary> {
        val root = json.parseToJsonElement(body)
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> root["data"] as JsonArray
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.content
            SessionSummary(id, title?.takeIf { it.isNotBlank() } ?: "(未命名会话)")
        }
    }

    private companion object {
        /** 历史翻页上限（每页 200 条，40 页 = 8000 条，足够覆盖超长会话）。 */
        const val MAX_HISTORY_PAGES = 40
    }
}
