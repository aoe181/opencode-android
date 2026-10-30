package dev.opencode.client.data

import androidx.compose.runtime.Immutable
import dev.opencode.client.ui.chat.AnsiStripper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 消息内容块：文本（正文/思考）与工具调用按原始顺序穿插。
 */
sealed class Block {
    /** type: "text" 正文 | "reasoning" 思考过程（durationMs 仅 reasoning 有值） */
    @Immutable
    data class Text(
        val type: String,
        val text: String,
        val ordinal: Int,
        val durationMs: Long? = null,
    ) : Block()

    @Immutable
    data class Tool(val call: ToolCall) : Block()
}

/**
 * 压缩记录（服务器 type="compaction" 消息）。
 *
 * 摘要由服务器生成并自动注入后续对话上下文（包成 <conversation-checkpoint>），
 * App 只负责展示，不参与注入。
 */
@Immutable
data class CompactionInfo(
    val summary: String,
    val reason: String? = null,
    val status: String = "completed",
    val keptMessages: Int? = null,
)

@Immutable
data class ToolFileChange(
    val file: String,
    val patch: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
)

@Immutable
data class ToolCall(
    val id: String,
    val name: String,
    val state: String, // "running" | "success" | "failed"
    val title: String? = null,    // 输入摘要（bash 命令 / 文件路径等）
    val input: String? = null,    // 原始输入 JSON 文本
    val output: String? = null,   // 执行输出
    val exit: Int? = null,
    val durationMs: Long? = null, // 执行耗时
    val truncated: Boolean = false,
    val fileChanges: List<ToolFileChange> = emptyList(), // edit 类工具的 diff
    val timestampMs: Long? = null, // 消息创建时间（无耗时时显示时刻）
    val background: Boolean = false, // 命令转入后台继续运行（非完成态）
    val shellID: String? = null, // 关联的 shell 会话（后台命令可据此追踪）
)

@Immutable
data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant"
    val blocks: List<Block>,
    val totalTokens: Long? = null, // 官方 contextUsage 分子（assistant 带 tokens 时）
    val images: List<MessageImage> = emptyList(), // user 消息附带的图片（内联 base64）
    val attachments: List<MessageAttachment> = emptyList(), // 路径引用的附件（Web 的 metadata.attachments）
    val timestampMs: Long? = null, // 消息创建时间（长按面板显示）
    /** 压缩摘要（type=compaction 消息）：非空时渲染为可折叠的压缩卡片。 */
    val compaction: CompactionInfo? = null,
) {
    fun textContent(): String =
        blocks.filterIsInstance<Block.Text>()
            .filter { it.type == "text" }
            .joinToString("\n") { it.text }

    /** 长按复制用的纯文本。 */
    fun plainText(): String = blocks.mapNotNull { block ->
        when (block) {
            is Block.Text -> block.text
            is Block.Tool -> block.call.title?.let { title -> "${block.call.name}: $title" }
        }
    }.joinToString("\n\n")
}

/** shell 命令的最终结果（来自 synthetic 回执，后台命令跑完后只在这里）。 */
data class ShellResult(
    val output: String,
    val exit: Int?,
    val state: String,
    val truncated: Boolean,
    val completedAt: Long?,
)

/** 路径引用的附件（服务器侧文件，Web 渲染为附件芯片）。 */
@Immutable
data class MessageAttachment(
    val name: String,
    val mime: String,
    val path: String,
)

/** 消息里的内联图片（base64，渲染为缩略图）。 */
@Immutable
data class MessageImage(
    val base64: String,
    val mime: String,
    val name: String?,
)

/**
 * 附件（Web 同款语义）：
 * - 已上传到服务器时：uri = "file://<服务器绝对路径>"（模型/工具按路径读取）
 * - 兜底内联时：uri = "data:<mime>;base64,..."
 */
data class FileAttachment(
    val uri: String,
    val name: String,
    val mime: String = "application/octet-stream",
    /** 服务器侧绝对路径（文件上传成功后填写）。 */
    val serverPath: String? = null,
    /** 图片走内联 dataUrl（Web 同款），文件走路径引用。 */
    val isImage: Boolean = false,
)

/** 选中的模型与档位（提交时随 metadata 下发，与 Web 端 admission.metadata.model 一致）。 */
data class ModelSelection(
    val providerID: String,
    val modelID: String,
    val variant: String? = null,
)

data class AgentInfo(
    val id: String,
    val name: String,
    val description: String?,
    val mode: String?,
    val hidden: Boolean,
)

data class ModelInfo(
    val modelID: String,
    val providerID: String,
    val name: String?,
    val variants: List<String>,
    val contextLimit: Long? = null,
    val outputLimit: Long? = null,
    val inputTypes: List<String> = emptyList(),
) {
    /** 与会话当前模型（composite id，如 "loomy/GLM-5.3-Flash"）匹配。 */
    fun matches(providerID: String?, modelId: String?): Boolean {
        if (providerID == null || modelId == null) return false
        if (this.providerID != providerID) return false
        return modelId == modelID || modelId.endsWith("/$modelID")
    }
}

/** 会话累计 token 用量（服务器权威数据）。 */
data class Tokens(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
)

/** token 数展示：< 1k 原样，1k~1M 一位小数 k，≥1M M。 */
fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0)
    else -> n.toString()
}

data class CommandInfo(
    val name: String,
    val description: String?,
)

/** 会话当前 agent / model / 思考档位。 */
data class SessionDetail(
    val agent: String?,
    val providerID: String?,
    val modelID: String?,
    val variant: String?,
    val tokens: Tokens? = null,
    val cost: Double? = null,
)

/** 一条 /api/event SSE 事件（信封已拆开，data 为载荷，created 为服务器时间戳 ms）。 */
data class OpenCodeEvent(
    val type: String,
    val data: JsonObject,
    val created: Long? = null,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.num(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

/** 耗时格式：< 1s 显示毫秒，≥ 1s 显示秒（一位小数，10s 以上取整）。 */
fun formatDuration(ms: Long?): String = when {
    ms == null || ms < 0 -> ""
    ms < 1000 -> "${ms}ms"
    else -> {
        val seconds = ms / 1000.0
        if (seconds < 10) String.format(java.util.Locale.ROOT, "%.1fs", seconds)
        else "${ms / 1000}s"
    }
}

/** 从工具输入里提取一句话摘要。 */
internal fun deriveToolTitle(input: JsonObject?): String? {
    if (input == null) return null
    for (key in listOf("command", "path", "filePath", "file", "pattern", "query", "url")) {
        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    }
    return null
}

internal fun joinContentText(content: JsonArray?): String? =
    content?.mapNotNull { (it as? JsonObject)?.str("text") }
        ?.joinToString("\n")
        ?.takeIf { it.isNotBlank() }
        ?.let { dev.opencode.client.ui.chat.AnsiStripper.strip(it) }

/** 解析工具 metadata 里的文件变更（edit/write 工具带统一 diff）。 */
internal fun parseFileChanges(metadata: JsonObject?): List<ToolFileChange> {
    val files = metadata?.get("files") as? JsonArray ?: return emptyList()
    return files.mapNotNull { el ->
        val f = el as? JsonObject ?: return@mapNotNull null
        ToolFileChange(
            file = f.str("file") ?: return@mapNotNull null,
            patch = f.str("patch") ?: "",
            status = f.str("status") ?: "modified",
            additions = (f["additions"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            deletions = (f["deletions"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }
}

/** 去掉 user 文本里的附件说明（Web 会把 "Attached file: ..." 追加在正文末尾，显示时整段剔除）。 */
private fun stripAttachmentNote(text: String): String {
    val marker = "Attached file:"
    val idx = text.indexOf(marker)
    return if (idx < 0) text.trim() else text.substring(0, idx).trim()
}

/** 解析历史消息里的工具部件（{type:"tool", id, name, state:{...}, time:{created,ran,completed}}）。 */
private fun parseToolPart(obj: JsonObject, index: Int, shellResults: Map<String, ShellResult> = emptyMap()): Block.Tool {
    val state = obj["state"] as? JsonObject
    val status = state?.str("status")
    val inputObj = state?.get("input") as? JsonObject
    val metadata = state?.get("metadata") as? JsonObject
    val time = obj["time"] as? JsonObject
    val created = time?.num("created")
    val ran = time?.num("ran") ?: created
    val completed = time?.num("completed")
    val outputText = joinContentText(state?.get("content") as? JsonArray)
    val shellID = metadata?.str("shellID")
    val exit = (metadata?.get("exit") as? JsonPrimitive)?.content?.toIntOrNull()

    // 命令转入后台：metadata 只有 shellID、没有 exit，输出为 "Command moved to the background"
    // 这类不是"已完成"。若 synthetic 回执里有该 shell 的最终结果，则用它补全输出/退出码
    val wasBackground = shellID != null && exit == null &&
        outputText?.startsWith("Command moved to the background") == true
    val shellResult = if (wasBackground && shellID != null) shellResults[shellID] else null
    val background = wasBackground && shellResult == null
    val finalExit = exit ?: shellResult?.exit
    val finalOutput = shellResult?.output?.takeIf { it.isNotBlank() } ?: outputText
    val finalTruncated = shellResult?.truncated ?: ((metadata?.get("truncated") as? JsonPrimitive)?.content == "true")

    val rawName = obj.str("name") ?: "tool"
    // 工具名优先级：
    // 1) metadata.toolCalls —— 服务器权威数据（"local-mcp.mt_apk_search"），
    //    一次 execute 并发调多个工具时标题为 "第一个 +N"
    // 2) input.code 正则提取（老数据没有 toolCalls 时兜底）
    val metaDisplay = toolDisplayFromMetadata(metadata)
    val mcpTool = if (metaDisplay == null && rawName == "execute") extractMcpToolName(inputObj) else null

    val call = ToolCall(
        id = obj.str("id") ?: "tool-$index",
        name = metaDisplay?.first ?: if (mcpTool != null) "mcp" else rawName,
        state = when {
            background -> "running"
            shellResult != null -> if (finalExit == null || finalExit == 0) "success" else "failed"
            status == "completed" -> "success"
            status == "error" || status == "failed" -> "failed"
            else -> "running"
        },
        title = metaDisplay?.second
            ?: mcpTool
            ?: deriveToolTitle(inputObj)
            ?: if (background) "已转入后台" else null,
        input = inputObj?.toString(),
        output = finalOutput,
        exit = finalExit,
        durationMs = when {
            background -> null
            // 后台命令完成后：用开始执行到回执完成的时长
            shellResult != null && shellResult.completedAt != null && ran != null ->
                (shellResult.completedAt - ran).takeIf { it >= 0 }
            completed != null && ran != null -> (completed - ran).takeIf { it >= 0 }
            else -> null
        },
        truncated = finalTruncated,
        fileChanges = parseFileChanges(metadata),
        timestampMs = created,
        background = background,
        shellID = shellID,
    )
    return Block.Tool(call)
}

/**
 * 从 execute 工具的 input.code 中提取真实工具名。
 *
 * 卡片头部已经用 "mcp" 徽标标明来源，服务器名（local-mcp）对使用者没有意义，只显示工具名。
 * 支持两种写法：
 *   tools["local-mcp"].mt_apk_list_available_apks({...})
 *   tools["local-mcp"]["mt_apk_list_available_apks"]({...})
 * 也支持内置 code mode 工具：tools.opencode.list_mcp_resources({...})
 */
internal fun extractMcpToolName(input: JsonObject?): String? {
    val code = input?.str("code") ?: return null
    MCP_TOOL_BRACKET_REGEX.find(code)?.let { return it.groupValues[2] }
    // 点号写法：第 1 组是服务器名（local-mcp），第 2 组才是工具名
    MCP_TOOL_DOT_REGEX.find(code)?.let { return it.groupValues[2] }
    MCP_BUILTIN_DOT_REGEX.find(code)?.let { return it.groupValues[1] }
    return null
}

/**
 * 从**原始文本**里提取 MCP 工具名，可在流式分片（JSON 尚不完整）阶段使用。
 *
 * 实时事件送来的 input 是 JSON 文本，引号被转义成 `\"`，直接跑正则会失配；
 * 这里先把 `\"` 还原成 `"` 再匹配，因此不需要完整合法的 JSON 也能提前识别。
 */
internal fun extractMcpToolNameFromText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    // 完整 JSON 优先（能正确还原 JSON 字符串里的转义）
    parseToolInput(raw)?.let { obj -> extractMcpToolName(obj)?.let { return it } }
    // 分片阶段：还原转义后直接匹配。
    // 注意此时工具名可能还没写完（如 mt_apk_worksp），后面必须紧跟非标识符字符才算完整，
    // 否则先返回 null，等下一个分片。
    val text = raw.replace("\\\"", "\"")
    MCP_TOOL_BRACKET_REGEX.find(text)?.let { m ->
        if (isCompleteIdent(text, m.range.last + 1)) return m.groupValues[2]
    }
    MCP_TOOL_DOT_REGEX.find(text)?.let { m ->
        if (isCompleteIdent(text, m.range.last + 1)) return m.groupValues[2]
    }
    MCP_BUILTIN_DOT_REGEX.find(text)?.let { m ->
        if (isCompleteIdent(text, m.range.last + 1)) return m.groupValues[1]
    }
    return null
}

/** 工具名后面必须是非标识符字符（`(` / `[` / 空白 / 引号等），才认为名字已完整。 */
private fun isCompleteIdent(text: String, indexAfterName: Int): Boolean {
    if (indexAfterName >= text.length) return false
    val c = text[indexAfterName]
    return !(c.isLetterOrDigit() || c == '_')
}

/**
 * 从**原始 JSON 字符串**形式的工具输入里提取 MCP 工具名。
 *
 * 实时事件（session.tool.called / input.ended）送来的 input 是 JSON 文本，
 * 里面 `tools[\"local-mcp\"]` 的引号是转义的，直接跑正则会失配——所以先解析成 JsonObject。
 * 历史消息路径拿到的本来就是 JsonObject，走 [extractMcpToolName]。
 */
internal fun extractMcpToolNameFromRaw(raw: String?): String? {
    val obj = parseToolInput(raw) ?: return null
    return extractMcpToolName(obj)
}

/**
 * 从 metadata.toolCalls 提取权威显示（badge to title）。
 *
 * 进度事件（session.tool.progress）与历史消息的 state.metadata 都带这个字段，
 * 格式形如 "local-mcp.mt_apk_search"，是服务器给出的权威工具名，
 * 比从 input.code 里跑正则可靠得多。无命名空间（不含点）时返回 null 交给推断。
 */
internal fun toolDisplayFromMetadata(metadata: JsonObject?): Pair<String, String>? {
    val calls = metadata?.get("toolCalls") as? JsonArray ?: return null
    val names = calls.mapNotNull { (it as? JsonObject)?.str("tool") }
    if (names.isEmpty()) return null
    val primary = names.first()
    if ('.' !in primary) return null
    val namespace = primary.substringBefore('.')
    val tool = primary.substringAfter('.')
    if (tool.isEmpty()) return null
    // 一次 execute 里并发调了多个工具时，标题显示 "第一个 +N"
    val title = if (names.size > 1) "$tool +${names.size - 1}" else tool
    // 内置 code mode 工具（opencode.*）保持 execute 徽标，MCP 服务器显示 mcp
    val badge = if (namespace == "opencode") "execute" else "mcp"
    return badge to title
}

/** 宽松解析工具输入 JSON（流式分片可能不完整，失败返回 null）。 */
internal fun parseToolInput(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/**
 * 从工具输入参数推断工具名。
 *
 * 实时事件里 session.tool.input.started 偶尔不带 name，占位名会一直停留在 "tool"，
 * 直到历史刷新才变回 edit/write——用户看到的就是"标签先显示 tool，过一会才变"。
 * 这里按输入参数特征做保底推断，让标签在流式阶段就正确。
 */
internal fun inferToolNameFromInput(input: JsonObject?): String? {
    if (input == null) return null
    return when {
        input.containsKey("code") -> "execute"
        input.containsKey("oldString") || input.containsKey("newString") ||
            input.containsKey("patch") -> "edit"
        input.containsKey("content") && input.containsKey("path") -> "write"
        input.containsKey("command") -> "shell"
        input.containsKey("pattern") -> "grep"
        input.containsKey("filePath") || (input.containsKey("path") && input.containsKey("offset")) -> "read"
        else -> null
    }
}

/** tools["server"].toolName(...) */
private val MCP_TOOL_DOT_REGEX =
    Regex("tools\\[\\s*[\"']([^\"']+)[\"']\\s*\\]\\s*\\.\\s*([A-Za-z0-9_]+)")

/** tools["server"]["toolName"](...) */
private val MCP_TOOL_BRACKET_REGEX =
    Regex("tools\\[\\s*[\"']([^\"']+)[\"']\\s*\\]\\s*\\[\\s*[\"']([A-Za-z0-9_]+)[\"']\\s*\\]")

/** tools.opencode.toolName(...) —— code mode 内置工具 */
private val MCP_BUILTIN_DOT_REGEX =
    Regex("tools\\.opencode\\.([A-Za-z0-9_]+)")

/** 解析 GET /api/session/{id}/message 响应。
 * user 消息文本在顶层 text 字段（payload.text 是 prompt 响应形状，作兜底），
 * assistant 在 content[]；content[] 数组顺序即渲染顺序。
 * synthetic / idle 等类型无文本块，解析后自然被过滤。
 */
fun parseMessages(body: String): List<ChatMessage> {
    val root = lenientJson.parseToJsonElement(body)
    val array: JsonArray = when {
        root is JsonArray -> root
        root is JsonObject && root["data"] is JsonArray -> root["data"] as JsonArray
        else -> return emptyList()
    }
    // 先收集 shell 回执（synthetic）的最终状态：后台命令跑完后，结果只在这里
    val shellResults = HashMap<String, ShellResult>()
    array.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        if (obj.str("type") != "synthetic") return@forEach
        val meta = obj["metadata"] as? JsonObject ?: return@forEach
        if (meta.str("source") != "shell") return@forEach
        val shellID = meta.str("shellID") ?: return@forEach
        val raw = obj.str("text") ?: return@forEach
        val output = Regex(">([\\s\\S]*)</shell>").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        shellResults[shellID] = ShellResult(
            output = AnsiStripper.strip(output),
            exit = (meta["exit"] as? JsonPrimitive)?.content?.toIntOrNull(),
            state = meta.str("state") ?: "completed",
            truncated = (meta["truncated"] as? JsonPrimitive)?.content == "true",
            completedAt = (obj["time"] as? JsonObject)?.num("created"),
        )
    }

    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id") ?: return@mapNotNull null
        val type = obj.str("type") ?: "assistant"

        // 压缩记录：服务器生成摘要并自动注入后续对话，这里只做展示（可折叠卡片）
        if (type == "compaction") {
            val status = obj.str("status") ?: "completed"
            val summary = obj.str("summary").orEmpty()
            if (status != "completed" || summary.isBlank()) return@mapNotNull null
            return@mapNotNull ChatMessage(
                id = id,
                role = "system",
                blocks = emptyList(),
                timestampMs = (obj["time"] as? JsonObject)?.num("created"),
                compaction = CompactionInfo(
                    summary = summary,
                    reason = obj.str("reason"),
                    status = status,
                    keptMessages = (obj["kept"] as? JsonArray)?.size,
                ),
            )
        }

        val blocks = mutableListOf<Block>()

        if (type == "user") {
            val text = obj.str("text")
                ?: ((obj["payload"] as? JsonObject)?.get("text") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            if (text != null && !text.startsWith("<shell ")) {
                // 图片附件在 displayText 里是纯文本路径，正文只留用户真正打的字
                blocks += Block.Text("text", stripAttachmentNote(text), 0)
            }
        }

        (obj["content"] as? JsonArray)?.forEachIndexed { index, partEl ->
            val partObj = partEl as? JsonObject ?: return@forEachIndexed
            when (val partType = partObj.str("type")) {
                "text", "reasoning" -> {
                    val text = partObj.str("text") ?: return@forEachIndexed
                    // 服务器常见：思考部件只有空白内容（text=" "），不渲染空折叠框
                    if (partType == "reasoning" && text.isBlank()) return@forEachIndexed
                    // 思考过程耗时：历史里在 part.time{created,completed}
                    val durationMs = if (partType == "reasoning") {
                        val time = partObj["time"] as? JsonObject
                        val created = time?.num("created")
                        val completed = time?.num("completed")
                        if (created != null && completed != null) {
                            (completed - created).takeIf { it >= 0 }
                        } else null
                    } else null
                    blocks += Block.Text(partType, text, index, durationMs)
                }
                "tool" -> blocks += parseToolPart(partObj, index, shellResults)
            }
        }

        // shell 回执（synthetic）：<shell id state command>输出</shell>
        if (type == "synthetic") {
            val raw = obj.str("text") ?: return@mapNotNull null
            val meta = obj["metadata"] as? JsonObject
            if (meta?.str("source") == "shell") {
                val command = Regex("<shell[^>]*command=\"(.*?)\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
                    .find(raw)?.groupValues?.get(1)?.replace("&quot;", "\"").orEmpty()
                val output = Regex(">([\\s\\S]*)</shell>").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                val state = meta.str("state") ?: "completed"
                val exit = (meta["exit"] as? JsonPrimitive)?.content?.toIntOrNull()
                // synthetic 回执没有独立时间字段，用消息 created 与 metadata 完成时间兜底
                val created = (obj["time"] as? JsonObject)?.num("created")
                blocks += Block.Tool(
                    ToolCall(
                        id = id,
                        name = "shell",
                        state = if (state == "completed" && (exit == null || exit == 0)) "success" else "failed",
                        title = command.lineSequence().firstOrNull()?.take(80),
                        input = command,
                        output = AnsiStripper.strip(output).ifBlank { "(无输出)" },
                        exit = exit,
                        truncated = (meta["truncated"] as? JsonPrimitive)?.content == "true",
                        durationMs = null, // shell 回执无耗时数据，不显示比显示错误值好
                        timestampMs = created,
                    )
                )
            }
        }

        val images = (obj["files"] as? JsonArray)?.mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val data = f.str("data") ?: return@mapNotNull null
            val mime = f.str("mime") ?: return@mapNotNull null
            if (!mime.startsWith("image/") || data.isEmpty()) return@mapNotNull null
            MessageImage(base64 = data, mime = mime, name = f.str("name"))
        }.orEmpty()

        // Web 用 metadata.attachments 渲染附件芯片（路径引用，非内联字节）
        val attachments = ((obj["metadata"] as? JsonObject)?.get("attachments") as? JsonArray)
            ?.mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val path = a.str("path") ?: return@mapNotNull null
                MessageAttachment(
                    name = a.str("name") ?: path.substringAfterLast('/'),
                    mime = a.str("mime") ?: "application/octet-stream",
                    path = path,
                )
            }.orEmpty()

        if (blocks.isEmpty() && images.isEmpty() && attachments.isEmpty()) return@mapNotNull null

        // 官方 contextUsage 分子（TUI session.ts）: input+output+reasoning+cache.read+cache.write
        val tokensObj = obj["tokens"] as? JsonObject
        val totalTokens = tokensObj?.let {
            (it.num("input") ?: 0) + (it.num("output") ?: 0) + (it.num("reasoning") ?: 0) +
                ((it["cache"] as? JsonObject)?.num("read") ?: 0) +
                ((it["cache"] as? JsonObject)?.num("write") ?: 0)
        }?.takeIf { it > 0 }
        ChatMessage(
            id = id,
            role = type,
            blocks = blocks,
            totalTokens = totalTokens,
            images = images,
            attachments = attachments,
            timestampMs = (obj["time"] as? JsonObject)?.num("created"),
        )
    }
}

/** 解析 POST /api/session 响应里的新会话 ID。 */
fun parseSessionId(body: String): String? {
    val root = lenientJson.parseToJsonElement(body)
    val data = root as? JsonObject ?: return null
    return (data["data"] as? JsonObject)?.str("id") ?: data.str("id")
}

/** 解析 GET /api/session/{id}（会话详情，取当前 agent/model/variant）。 */
fun parseSessionDetail(body: String): SessionDetail? {
    val root = lenientJson.parseToJsonElement(body)
    val data = ((root as? JsonObject)?.get("data") as? JsonObject) ?: (root as? JsonObject) ?: return null
    val model = data["model"] as? JsonObject
    val tokensObj = data["tokens"] as? JsonObject
    val tokens = tokensObj?.let {
        Tokens(
            input = it.num("input") ?: 0,
            output = it.num("output") ?: 0,
            reasoning = it.num("reasoning") ?: 0,
            cacheRead = (it["cache"] as? JsonObject)?.num("read") ?: 0,
        )
    }
    return SessionDetail(
        agent = data.str("agent"),
        providerID = model?.str("providerID"),
        modelID = model?.str("id"),
        variant = model?.str("variant"),
        tokens = tokens,
        cost = (data["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
    )
}

private inline fun <reified T> parseDataArray(body: String, map: (JsonObject) -> T?): List<T> {
    val root = lenientJson.parseToJsonElement(body)
    val array: JsonArray = when {
        root is JsonArray -> root
        root is JsonObject && root["data"] is JsonArray -> root["data"] as JsonArray
        else -> return emptyList()
    }
    return array.mapNotNull { (it as? JsonObject)?.let(map) }
}

fun parseAgents(body: String): List<AgentInfo> = parseDataArray(body) { obj ->
    AgentInfo(
        id = obj.str("id") ?: return@parseDataArray null,
        name = obj.str("name") ?: obj.str("id") ?: "",
        description = obj.str("description"),
        mode = obj.str("mode"),
        hidden = (obj["hidden"] as? JsonPrimitive)?.content == "true",
    )
}

fun parseModels(body: String): List<ModelInfo> = parseDataArray(body) { obj ->
    val modelId = obj.str("modelID") ?: obj.str("id") ?: return@parseDataArray null
    val providerId = obj.str("providerID") ?: return@parseDataArray null
    ModelInfo(
        modelID = modelId,
        providerID = providerId,
        name = obj.str("name"),
        variants = (obj["variants"] as? JsonArray)
            ?.mapNotNull { v -> (v as? JsonObject)?.str("id") }
            .orEmpty(),
        contextLimit = ((obj["limit"] as? JsonObject)?.get("context") as? JsonPrimitive)
            ?.content?.toLongOrNull(),
        outputLimit = ((obj["limit"] as? JsonObject)?.get("output") as? JsonPrimitive)
            ?.content?.toLongOrNull(),
        inputTypes = ((obj["capabilities"] as? JsonObject)?.get("input") as? JsonArray)
            ?.mapNotNull { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .orEmpty(),
    )
}

/** MCP 服务器信息。 */
data class McpServerInfo(
    val name: String,
    val status: String?,
)

/** 技能信息。 */
data class SkillInfo(
    val name: String,
    val description: String?,
)

fun parseMcpServers(body: String): List<McpServerInfo> = parseDataArray(body) { obj ->
    McpServerInfo(
        name = obj.str("name") ?: return@parseDataArray null,
        status = (obj["status"] as? JsonObject)?.str("status")
            ?: obj.str("status"),
    )
}

fun parseSkills(body: String): List<SkillInfo> = parseDataArray(body) { obj ->
    SkillInfo(
        name = obj.str("name") ?: obj.str("id") ?: return@parseDataArray null,
        description = obj.str("description"),
    )
}

/** 待投递输入（inbox 里尚未送达的用户消息）。 */
data class PendingInboxItem(
    val id: String,
    val text: String,
    val delivery: String,
)

/**
 * 解析单个表单对象（`GET /api/session/{id}/form` 的数组元素，
 * 或 SSE `form.created` 事件里的 `data.form`）。
 *
 * 字段类型见服务器 Form.Field：string / multiselect / boolean / number /
 * integer / external。question 工具只产生 string 与 multiselect，
 * 其余类型（MCP elicitation）也一并支持，避免提交时被判为非法答案。
 */
fun parseFormObject(obj: JsonObject): FormDetail? {
    val fields = (obj["fields"] as? JsonArray)?.mapNotNull { f ->
        val fo = f as? JsonObject ?: return@mapNotNull null
        val key = fo.str("key") ?: return@mapNotNull null
        val defaults = when (val d = fo["default"]) {
            is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOfNotNull(d.takeIf { it.isString }?.content)
            else -> emptyList()
        }
        FormFieldInfo(
            key = key,
            type = fo.str("type") ?: "string",
            title = fo.str("title"),
            description = fo.str("description"),
            placeholder = fo.str("placeholder"),
            options = ((fo["options"] as? JsonArray)?.mapNotNull { o ->
                (o as? JsonObject)?.let {
                    val value = it.str("value") ?: it.str("label") ?: ""
                    FormOption(
                        value = value,
                        label = it.str("label") ?: value,
                        description = it.str("description"),
                    )
                }
            } ?: emptyList()),
            custom = (fo["custom"] as? JsonPrimitive)?.content == "true",
            required = (fo["required"] as? JsonPrimitive)?.content == "true",
            hidden = (fo["hidden"] as? JsonPrimitive)?.content == "true",
            minItems = fo.num("minItems")?.toInt() ?: 0,
            maxItems = fo.num("maxItems")?.toInt() ?: 0,
            default = defaults,
        )
    } ?: emptyList()
    if (fields.isEmpty()) return null
    return FormDetail(
        id = obj.str("id") ?: return null,
        sessionID = obj.str("sessionID") ?: "",
        title = obj.str("title") ?: "Question",
        kind = (obj["metadata"] as? JsonObject)?.str("kind"),
        fields = fields,
    )
}

fun parseForms(body: String): List<FormDetail> = parseDataArray(body) { parseFormObject(it) }

/** 待回答表单（question 工具创建）。 */
data class FormDetail(
    val id: String,
    val sessionID: String,
    val title: String,
    /** metadata.kind：question / websearch.provider / mcp-elicitation … */
    val kind: String? = null,
    val fields: List<FormFieldInfo>,
)

data class FormFieldInfo(
    val key: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val placeholder: String? = null,
    val options: List<FormOption> = emptyList(),
    val custom: Boolean = false,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val minItems: Int = 0,
    val maxItems: Int = 0,
    val default: List<String> = emptyList(),
) {
    /** 多选（服务器 Form.MultiselectField）。 */
    val multiple: Boolean get() = type == "multiselect"
}

data class FormOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/**
 * 提交值，与服务器 Form.Value 对应：
 * 字符串 / 字符串数组（多选）/ 布尔 / 数字。
 *
 * 必须区分类型：多选字段提交字符串会被服务器判为 FormInvalidAnswerError。
 */
sealed interface FormValue {
    data class Text(val value: String) : FormValue
    data class Multi(val values: List<String>) : FormValue
    data class Flag(val value: Boolean) : FormValue
    data class Num(val value: Double) : FormValue
}

fun parseInboxItems(body: String): List<PendingInboxItem> = parseDataArray(body) { obj ->
    // 只关心用户输入项；synthetic/compaction/move 等系统项忽略
    if (obj.str("type") != "user") return@parseDataArray null
    val id = obj.str("id") ?: return@parseDataArray null
    val text = ((obj["payload"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content
        ?: return@parseDataArray null
    PendingInboxItem(
        id = id,
        text = text,
        delivery = obj.str("delivery") ?: "steer",
    )
}

fun parseCommands(body: String): List<CommandInfo> = parseDataArray(body) { obj ->
    CommandInfo(
        name = obj.str("name") ?: return@parseDataArray null,
        description = obj.str("description"),
    )
}
