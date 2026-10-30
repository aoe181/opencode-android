package dev.opencode.client.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.opencode.client.MainViewModel
import dev.opencode.client.data.Block
import dev.opencode.client.data.ChatMessage
import dev.opencode.client.data.CommandInfo
import dev.opencode.client.data.MessageImage
import dev.opencode.client.data.formatDuration
import dev.opencode.client.data.formatTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(mainVm: MainViewModel) {
    val sel by mainVm.selected.collectAsState()
    val server = mainVm.uiState.collectAsState().value.server ?: return
    val sessionId = sel?.first ?: return
    val sessionTitle = sel?.second ?: "会话"

    val chatVm: ChatViewModel = viewModel(key = "chat_$sessionId")
    val context = LocalContext.current
    LaunchedEffect(sessionId, server) { chatVm.start(server, sessionId) }
    DisposableEffect(sessionId) { onDispose { chatVm.stop() } }
    BackHandler { mainVm.closeSession() }

    val state by chatVm.ui.collectAsState()
    // 输入草稿：切会话不丢（输入变化即时存）
    var input by remember(sessionId) { mutableStateOf(mainVm.drafts.value[sessionId] ?: "") }
    LaunchedEffect(input) { mainVm.saveDraft(sessionId, input) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val scope = rememberCoroutineScope()
    var showSessionSwitcher by remember { mutableStateOf(false) }
    var showMcpPanel by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatVm.onPicked(uri)
    }

    // 普通布局 + 智能吸底。
    //
    // 跟随模式（autoScroll）下，列表尾部始终贴住输入框：流式文本增长、新消息、
    // 工具卡片展开、键盘弹出导致视口变矮，都会重新贴底。之前只在消息数量变化时
    // 滚一次，所以键盘弹起后最后一条会被输入框盖住（"尾部不跟着起来"）。
    // 用户手指一拖就退出跟随，拖回底部再恢复，上翻阅读不被打断。
    val listState = rememberLazyListState()
    val autoScroll = remember { mutableStateOf(true) }
    // 手指是否正按在列表上（程序化滚动不会置位，用来区分用户意图）
    var userTouching by remember { mutableStateOf(false) }
    // 自己发消息时强制回到底部（即使之前在上翻阅读）
    var forceScrollTrigger by remember { mutableStateOf(0) }

    // 首次进入会话：优先恢复上次位置；没有记录则回到底部
    var restoredOnce by remember(sessionId) { mutableStateOf(false) }
    // 视口锚点：记录首条可见消息的 id 与偏移。
    // 历史分页会在顶部插入更旧的消息，所有索引随之漂移；若按索引定位，
    // 视口内容会瞬间变成"上面"的消息——这就是进入会话/发送时屏幕向上跳的原因。
    var anchorId by remember(sessionId) { mutableStateOf<String?>(null) }
    var anchorOffset by remember(sessionId) { mutableStateOf(0) }

    // 是否真的在底部：最后一条完整可见（8px 容差，避免像素误差反复切换）
    fun atBottom(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        if (last.index != info.totalItemsCount - 1) return false
        return last.offset + last.size <= info.viewportEndOffset + 8
    }

    // 贴到底：先定位到末条，再向前多滚一点（会被钳制在内容末尾）。
    // 只用 scrollToItem 会把末条的"顶部"对齐到视口顶部，
    // 长消息时新内容仍在屏幕外，必须再补一次到底。
    suspend fun stickToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        listState.scrollToItem(last)
        listState.scrollBy(BOTTOM_SCROLL_OVERSHOOT)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && userTouching) {
                    // 手指拖动中实时判断：一离开底部就停止跟随
                    autoScroll.value = atBottom()
                } else if (!scrolling && !userTouching && atBottom()) {
                    // 松手/惯性结束回到底部：恢复跟随
                    autoScroll.value = true
                }
            }
    }

    // 贴底引擎：布局每变化一次就检查尾部是否露出，不够就补滚。
    // 键盘弹出（视口变矮）、流式文本增长、新消息都走这里，尾部始终跟着输入框。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                if (!autoScroll.value || userTouching || !restoredOnce) return@collect
                val lastIndex = info.totalItemsCount - 1
                if (lastIndex < 0) return@collect
                val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@collect
                if (lastVisible.index != lastIndex) {
                    // 下方还有没露出来的条目：直接贴到底
                    stickToBottom()
                    return@collect
                }
                val delta = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
                if (delta < 0) listState.scrollBy(-delta.toFloat())
            }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            info?.key to (info?.offset ?: 0)
        }.collect { (id, offset) ->
            // LazyListItemInfo.key 是 Any?，必须安全转型为消息 id（String?）
            val messageId = id as? String
            if (restoredOnce && messageId != null) {
                anchorId = messageId
                anchorOffset = offset
            }
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!restoredOnce) {
            restoredOnce = true
            val anchor = mainVm.scrollPosition(sessionId)
            val restoredIndex = anchor?.let { a ->
                val id = a.substringBefore(':')
                val off = a.substringAfter(':', "0").toIntOrNull() ?: 0
                val idx = state.messages.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    listState.scrollToItem(idx.coerceAtMost(state.messages.lastIndex), off)
                    idx
                } else null
            }
            if (restoredIndex != null) {
                // 恢复到非底部位置时立即关掉吸底，否则后台分页完成后会被拉到底部
                autoScroll.value = restoredIndex >= state.messages.lastIndex - 1
            } else {
                stickToBottom()
            }
            return@LaunchedEffect
        }
        // 历史分页在顶部插入旧消息后按 id 重新锚定，避免索引漂移造成内容上跳
        val id = anchorId
        if (id != null) {
            val idx = state.messages.indexOfFirst { it.id == id }
            if (idx >= 0 && idx != listState.firstVisibleItemIndex) {
                listState.scrollToItem(idx, anchorOffset)
            }
        }
        // 吸底跟随交给上面的贴底引擎（它连流式增长一起处理）
    }
    // 离开会话时保存视口锚点：仅在上翻阅读时保存（吸底时无需记忆，
    // 恢复时直接回到底部——否则从底部切走再回来会落在视口顶部而非底部）
    DisposableEffect(sessionId) {
        onDispose {
            if (!autoScroll.value && anchorId != null) {
                mainVm.saveScrollPosition(sessionId, "$anchorId:$anchorOffset")
            }
        }
    }
    LaunchedEffect(forceScrollTrigger) {
        if (forceScrollTrigger <= 0 || state.messages.isEmpty() || !restoredOnce) return@LaunchedEffect
        // 自己发消息：强制回到底部（即使之前在上翻阅读）
        autoScroll.value = true
        stickToBottom()
    }

    Scaffold(
        topBar = {
            // 紧凑自绘顶栏：内容 32dp（比上版 44dp 再砍一半）+ 状态栏内边距
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(start = 2.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { mainVm.closeSession() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = sessionTitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showSessionSwitcher = true }
                        .padding(vertical = 6.dp)
                )
                // 上下文占用指示器：>70% 显示橙色警示
                state.contextTokens?.let { ctx ->
                    val limit = state.models.firstOrNull {
                        it.matches(state.currentProvider, state.currentModel)
                    }?.contextLimit
                    val pct = if (limit != null && limit > 0) (ctx * 100.0 / limit).toInt() else 0
                    Box {
                        Text(
                            text = contextLabel(state, ctx),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pct >= 70) Color(0xFFFFA726)
                                    else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable { showUsage = !showUsage }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        DropdownMenu(
                            expanded = showUsage,
                            onDismissRequest = { showUsage = false }
                        ) {
                            UsageDetails(state)
                        }
                    }
                }
                // 连接状态：绿色对号 / 橙色圈（重连中） / 红色叉；点开可查看并重连 MCP
                val connColor = when (state.connectionState) {
                    "connected" -> Color(0xFF4ADE80)
                    "reconnecting" -> Color(0xFFFFA726)
                    else -> Color(0xFFFF5C7A)
                }
                Box {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {
                                if (showMcpPanel) showMcpPanel = false
                                else {
                                    chatVm.loadMcpServers()
                                    showMcpPanel = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (state.connectionState) {
                                "connected" -> "✓"
                                "reconnecting" -> "○"
                                else -> "✕"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = connColor
                        )
                    }
                    DropdownMenu(
                        expanded = showMcpPanel,
                        onDismissRequest = { showMcpPanel = false }
                    ) {
                        McpPanel(
                            servers = state.mcpServers,
                            reconnecting = state.reconnectingMcp,
                            onReconnect = { chatVm.reconnectMcp(it) },
                            onRefresh = { chatVm.loadMcpServers() },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 只观察不消费：记录手指是否按在列表上，用于区分
                    // "用户上翻" 与 "程序化贴底"，避免流式时把用户顶回去
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.any { it.pressed }
                                if (pressed != userTouching) userTouching = pressed
                            }
                        }
                    },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        // 只捕获状态写入器（稳定），避免每条消息每帧生成新 lambda
                        onLongPress = { actionMessage = it },
                        // 只有正在流式增长的最后一条 assistant 消息才降级表格渲染
                        streaming = state.streaming && message.id == state.messages.lastOrNull()?.id,
                        pendingDelivery = state.pendingDeliveries[message.id],
                    )
                }
                if (state.streaming && state.messages.lastOrNull()?.role != "assistant") {
                    item(key = "thinking") {
                        Text(
                            "正在思考…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 右下角悬浮按钮组：上"回到底部"、下"停止"（停止在发送按钮正上方，二者留 8dp 间距）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !autoScroll.value && state.messages.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
                ) {
                    Surface(
                        onClick = {
                            autoScroll.value = true
                            scope.launch {
                                if (state.messages.isNotEmpty()) stickToBottom()
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("↓", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                // 停止悬浮按钮：会话执行中随时可中断，切换会话后依然可用
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.streaming,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
                ) {
                    var stopping by remember { mutableStateOf(false) }
                    LaunchedEffect(state.streaming) { if (!state.streaming) stopping = false }
                    Surface(
                        onClick = {
                            if (!stopping) {
                                stopping = true
                                chatVm.interrupt()
                            }
                        },
                        shape = CircleShape,
                        color = if (stopping) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.surface
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onError,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }
            }

            // question 表单：agent 向用户提问（Web 同款交互：单选 + 可自由输入）
            state.pendingForm?.let { form ->
                QuestionFormCard(
                    form = form,
                    onAnswer = { values -> chatVm.answerForm(form, values) },
                    onIgnore = { chatVm.ignoreForm(form.id) },
                )
            }

            state.error?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    // 发送失败可一键重试
                    if (err.startsWith("发送失败")) {
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { chatVm.retryFailedSend() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            // 压缩上下文的进度提示
            if (state.compacting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在压缩上下文…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 斜杠命令提示
            if (input.startsWith("/") && !input.contains(' ')) {
                CommandHint(
                    commands = state.commands.filter {
                        it.name.startsWith(input.removePrefix("/"), ignoreCase = true)
                    },
                    onPick = { name -> input = "/$name " }
                )
            }

            // 附件 chips
            if (state.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.attachments.forEach { attachment ->
                        AssistChip(
                            onClick = { chatVm.removeAttachment(attachment.uri) },
                            label = {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = attachment.name
                            }
                        )
                    }
                }
            }

            // 输入行：紧凑输入框 + 附件 + 发送（停止已改为悬浮按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                FilledIconButton(
                    onClick = { pickFile.launch("*/*") },
                    modifier = Modifier.padding(bottom = 1.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加附件")
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "输入消息或 / 命令…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        chatVm.send(input)
                        input = ""
                        mainVm.saveDraft(sessionId, "")
                        // 自己发消息：强制回到底部
                        autoScroll.value = true
                        forceScrollTrigger++
                    },
                    enabled = !state.sending && input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }

            // agent / 模型 / 档位：三个真正平级的紧凑下拉（输入栏正下方，左右撑满对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // agent
                Box(modifier = Modifier.weight(0.28f)) {
                    PickerChip(
                        text = state.currentAgent ?: "agent",
                        expanded = openMenu == "agent",
                        onClick = { openMenu = if (openMenu == "agent") null else "agent" }
                    )
                    DropdownMenu(
                        expanded = openMenu == "agent",
                        onDismissRequest = { openMenu = null }
                    ) {
                        state.agents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                                        agent.description?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    if (state.currentAgent == agent.id) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "当前",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    chatVm.setAgent(agent.id)
                                    openMenu = null
                                }
                            )
                        }
                    }
                }

                // 模型（不含 provider 前缀）
                Box(modifier = Modifier.weight(0.44f)) {
                    PickerChip(
                        text = displayModelId(state.currentModel),
                        expanded = openMenu == "model",
                        onClick = { openMenu = if (openMenu == "model") null else "model" }
                    )
                    DropdownMenu(
                        expanded = openMenu == "model",
                        onDismissRequest = { openMenu = null }
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            state.models.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = model.name ?: displayModelId(model.modelID),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            // 上下文规格：与 TUI/Web 一致，取 model.limit.context / output
                                            Text(
                                                text = buildString {
                                                    append("   ") // 缩进
                                                    append(model.providerID)
                                                    model.contextLimit?.let {
                                                        append("  ·  输入 ").append(formatTokens(it))
                                                    }
                                                    model.outputLimit?.let {
                                                        append(" · 输出 ").append(formatTokens(it))
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (model.matches(state.currentProvider, state.currentModel)) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "当前",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        chatVm.setModel(model.providerID, model.modelID, null)
                                        openMenu = null
                                    }
                                )
                            }
                        }
                    }
                }

                // 档位（仅当前模型有 variants 时显示）
                val currentModelInfo = state.models.firstOrNull {
                    it.matches(state.currentProvider, state.currentModel)
                }
                if (currentModelInfo != null && currentModelInfo.variants.isNotEmpty()) {
                    Box(modifier = Modifier.weight(0.28f)) {
                        PickerChip(
                            text = state.currentVariant ?: "默认",
                            expanded = openMenu == "variant",
                            onClick = { openMenu = if (openMenu == "variant") null else "variant" }
                        )
                        DropdownMenu(
                            expanded = openMenu == "variant",
                            onDismissRequest = { openMenu = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("默认") },
                                trailingIcon = {
                                    if (state.currentVariant == null) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "当前",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    chatVm.setModel(currentModelInfo.providerID, currentModelInfo.modelID, null)
                                    openMenu = null
                                }
                            )
                            currentModelInfo.variants.forEach { variant ->
                                DropdownMenuItem(
                                    text = { Text(variant) },
                                    trailingIcon = {
                                        if (state.currentVariant == variant) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "当前",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        chatVm.setModel(currentModelInfo.providerID, currentModelInfo.modelID, variant)
                                        openMenu = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSessionSwitcher) {
        val sessions = mainVm.uiState.collectAsState().value.sessions
        ModalBottomSheet(onDismissRequest = { showSessionSwitcher = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "切换对话",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn {
                    items(sessions, key = { it.id }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSessionSwitcher = false
                                    if (s.id != sessionId) mainVm.openSession(s.id, s.title)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (s.id == sessionId) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "当前",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (actionMessage != null) {
        val message = actionMessage
        ModalBottomSheet(onDismissRequest = { actionMessage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Text(
                    text = buildString {
                        append(
                            when {
                                message?.compaction != null -> "压缩记录"
                                message?.role == "user" -> "这条输入"
                                else -> "这条回复"
                            }
                        )
                        message?.timestampMs?.let { ts ->
                            append("  ·  ")
                            append(formatTimestamp(ts))
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                SheetAction("复制全文") {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("opencode", message?.plainText().orEmpty())
                    )
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    actionMessage = null
                }
                // 复制消息里的代码块（``` 围栏内内容）
                val codeBlocks = message?.blocks
                    ?.filterIsInstance<dev.opencode.client.data.Block.Text>()
                    ?.filter { it.type == "text" }
                    ?.flatMap { b -> extractCodeBlocks(b.text) }
                    .orEmpty()
                if (codeBlocks.isNotEmpty()) {
                    SheetAction(
                        if (codeBlocks.size == 1) "复制代码" else "复制代码（${codeBlocks.size} 段）"
                    ) {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("opencode-code", codeBlocks.joinToString("\n\n"))
                        )
                        Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                        actionMessage = null
                    }
                }
                if (message != null && message.id in state.pendingInputs) {
                    SheetAction("撤回这条输入") {
                        chatVm.cancelInbox(message.id)
                        actionMessage = null
                    }
                    SheetAction(
                        if (state.pendingDeliveries[message.id] == "queue") {
                            "改为插入（下一步立即送达）"
                        } else {
                            "改为排队（当前执行完再送达）"
                        }
                    ) {
                        chatVm.toggleInboxDelivery(message.id)
                        actionMessage = null
                    }
                }
                // 回退（revert）：面向已投递的消息，删除该消息之后的所有内容。
                // 与上面的撤回不同：撤回只对尚未投递的输入有效。
                if (message != null && message.id !in state.pendingInputs && !message.id.startsWith("local-")) {
                    if (state.reverting) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "正在回退…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        SheetAction("回退到这里（删除之后的内容）") {
                            chatVm.revertTo(message.id, restoreFiles = false)
                            actionMessage = null
                        }
                        SheetAction("回退到这里（含文件还原）") {
                            chatVm.revertTo(message.id, restoreFiles = true)
                            actionMessage = null
                        }
                    }
                }
            }
        }
    }
}

/**
 * question 表单卡片：与 Web 的 question dock 对齐。
 *
 * 协议（服务器 Form 契约，见 /openapi.json 的 Form.* schema）：
 * - 表单由 question 工具创建，SSE `form.created` 事件带完整表单，立即弹出；
 * - 字段类型：string（单选 + 可自定义）/ multiselect（多选 + 可自定义）/
 *   boolean / number / integer；
 * - 回答 POST .../form/{formID}/reply，body {"answer": {fieldKey: value}}；
 *   单选提交选项的 value（字符串），多选提交 value 数组，
 *   自定义输入按原始文本追加（多选时并入数组）。
 */
@Composable
private fun QuestionFormCard(
    form: dev.opencode.client.data.FormDetail,
    onAnswer: (Map<String, dev.opencode.client.data.FormValue>) -> Unit,
    onIgnore: () -> Unit,
) {
    // 每个字段的选择/输入状态按 key 存，多字段表单不会互相串
    var picked by remember(form.id) {
        mutableStateOf(
            form.fields.associate { it.key to it.default }.filterValues { it.isNotEmpty() }
        )
    }
    var texts by remember(form.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var flags by remember(form.id) {
        mutableStateOf(form.fields.associate { it.key to it.default.contains("true") })
    }

    fun toggle(field: dev.opencode.client.data.FormFieldInfo, value: String) {
        val cur = picked[field.key].orEmpty()
        picked = picked + (field.key to if (field.multiple) {
            if (value in cur) cur - value else cur + value
        } else {
            listOf(value)
        })
    }

    // 一个字段的提交值；null 表示未回答（提交时省略该字段）
    fun answerOf(field: dev.opencode.client.data.FormFieldInfo): dev.opencode.client.data.FormValue? {
        val text = texts[field.key].orEmpty().trim()
        return when (field.type) {
            "multiselect" -> {
                val list = picked[field.key].orEmpty() +
                    listOfNotNull(text.takeIf { it.isNotEmpty() })
                if (list.isEmpty()) null else dev.opencode.client.data.FormValue.Multi(list.distinct())
            }
            "boolean" -> {
                val on = flags[field.key] == true
                if (on || field.required) dev.opencode.client.data.FormValue.Flag(on) else null
            }
            "number", "integer" ->
                text.toDoubleOrNull()?.let { dev.opencode.client.data.FormValue.Num(it) }
            "external" -> null
            else -> {
                val value = text.ifEmpty { picked[field.key].orEmpty().firstOrNull().orEmpty() }
                if (value.isEmpty()) null else dev.opencode.client.data.FormValue.Text(value)
            }
        }
    }

    val visibleFields = form.fields.filterNot { it.hidden }
    val answers = visibleFields.associate { it.key to answerOf(it) }
    val answeredCount = answers.count { it.value != null }
    // 必填项必须有值；多选题还要满足 minItems / maxItems
    val canSubmit = answeredCount > 0 && visibleFields.all { f ->
        val v = answers[f.key]
        when {
            v == null -> !f.required
            f.multiple -> {
                val n = (v as? dev.opencode.client.data.FormValue.Multi)?.values?.size ?: 0
                n >= f.minItems && (f.maxItems <= 0 || n <= f.maxItems)
            }
            else -> true
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "❓ " + form.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (visibleFields.size > 1) {
                    Text(
                        text = "$answeredCount/${visibleFields.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 选项多时可滚动，避免把输入框挤出屏幕
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                visibleFields.forEachIndexed { index, field ->
                    if (visibleFields.size > 1) {
                        Text(
                            text = "问题 ${index + 1}/${visibleFields.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp)
                        )
                    }
                    Text(
                        text = field.title ?: field.key,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    field.description?.takeIf { it.isNotBlank() && it != field.title }?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (field.options.isNotEmpty()) {
                        Text(
                            text = if (field.multiple) "可多选" else "单选",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    if (field.type == "boolean") {
                        Surface(
                            onClick = { flags = flags + (field.key to (flags[field.key] != true)) },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (flags[field.key] == true) "是" else "否",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = flags[field.key] == true,
                                    onCheckedChange = { flags = flags + (field.key to it) }
                                )
                            }
                        }
                    } else {
                        field.options.forEach { opt ->
                            val selected = picked[field.key].orEmpty().contains(opt.value)
                            Surface(
                                onClick = { toggle(field, opt.value) },
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (field.multiple) {
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = { toggle(field, opt.value) },
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else {
                                        RadioButton(
                                            selected = selected,
                                            onClick = { toggle(field, opt.value) },
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = opt.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        opt.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 自由输入：custom=true、无选项、或文本/数字类型
                        val wantsCustom = field.custom || field.options.isEmpty()
                        if (wantsCustom) {
                            val text = texts[field.key].orEmpty()
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (text.isNotBlank()) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                BasicTextField(
                                    value = text,
                                    onValueChange = { texts = texts + (field.key to it) },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 4,
                                    keyboardOptions = if (field.type == "number" || field.type == "integer") {
                                        KeyboardOptions(keyboardType = KeyboardType.Number)
                                    } else {
                                        KeyboardOptions.Default
                                    },
                                    decorationBox = { inner ->
                                        Box {
                                            if (text.isEmpty()) {
                                                Text(
                                                    text = field.placeholder ?: "或者输入自定义回答…",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            inner()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "忽略",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onIgnore() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Button(
                    onClick = {
                        val values = visibleFields
                            .mapNotNull { f -> answerOf(f)?.let { f.key to it } }
                            .toMap()
                        onAnswer(values)
                    },
                    enabled = canSubmit,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("提交", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * MCP 服务器面板：展示连接状态，并提供手动重连。
 *
 * 为什么需要手动重连：MCP 服务若在 opencode 启动之后才起来，服务器会把这个目录的连接
 * 标记为 failed 且不再自动重试，code mode 里就取不到该服务器的工具。
 */
@Composable
private fun McpPanel(
    servers: List<dev.opencode.client.data.McpServerInfo>,
    reconnecting: String?,
    onReconnect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Text(
        text = "MCP 服务器",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    )
    if (servers.isEmpty()) {
        Text(
            text = "未配置或尚未加载",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
    servers.forEach { sv ->
        val connected = sv.status == "connected"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (connected) "✓" else "✕",
                style = MaterialTheme.typography.labelMedium,
                color = if (connected) Color(0xFF4ADE80) else Color(0xFFFF5C7A)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sv.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = sv.status ?: "unknown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (reconnecting == sv.name) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (connected) "重连" else "连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onReconnect(sv.name) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
    Text(
        text = "刷新状态",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { onRefresh() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** 紧凑选择 chip：撑满父容器宽度、文字居中（weight 由外层 Box 承担）。 */
@Composable
private fun PickerChip(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (expanded) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

private fun displayModelId(id: String?): String = id?.substringAfterLast('/') ?: "model"

private fun contextLabel(state: ChatUiState, contextTokens: Long): String {
    val limit = state.models.firstOrNull {
        it.matches(state.currentProvider, state.currentModel)
    }?.contextLimit
    val base = formatTokens(contextTokens)
    return if (limit != null && limit > 0) {
        "$base (${(contextTokens * 100.0 / limit).toInt().coerceIn(0, 100)}%)"
    } else base
}

@Composable
private fun UsageDetails(state: ChatUiState) {
    // 明细固定展示全部条目：数据缺失时显示 "—"，不再因 usage/contextTokens 为空而整块消失
    val usage = state.usage
    val fmt = { v: Long? -> v?.let { formatTokens(it) } ?: "—" }

    DropdownMenuItem(
        text = { Text("输入 ${fmt(usage?.input)}") },
        onClick = {}
    )
    DropdownMenuItem(
        text = { Text("输出 ${fmt(usage?.output)}") },
        onClick = {}
    )
    DropdownMenuItem(
        text = { Text("思考 ${fmt(usage?.reasoning)}") },
        onClick = {}
    )
    DropdownMenuItem(
        text = { Text("缓存 ${fmt(usage?.cacheRead)}") },
        onClick = {}
    )

    val limit = state.models.firstOrNull {
        it.matches(state.currentProvider, state.currentModel)
    }?.contextLimit
    val ctx = state.contextTokens
    val pct = if (ctx != null && limit != null && limit > 0) {
        (ctx * 100.0 / limit).toInt()
    } else null
    DropdownMenuItem(
        text = {
            Text(
                buildString {
                    append(fmt(ctx))
                    limit?.let { append(" / ").append(formatTokens(it)) }
                    pct?.let { append("  (").append(it).append("%)") }
                }
            )
        },
        onClick = {}
    )
    DropdownMenuItem(
        text = { Text("api ${state.apiCalls}") },
        onClick = {}
    )
    state.usageCost?.takeIf { it > 0 }?.let { cost ->
        DropdownMenuItem(
            text = { Text("费用 ${String.format(java.util.Locale.ROOT, "%.4f", cost)}") },
            onClick = {}
        )
    }
}


@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun CommandHint(commands: List<CommandInfo>, onPick: (String) -> Unit) {
    if (commands.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Column {
            commands.take(4).forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command.name) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = command.description ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 压缩记录卡片：默认收起，点开展示服务器生成的完整摘要。
 *
 * 摘要由服务器自动注入后续对话（<conversation-checkpoint>），此处纯展示，
 * 让用户知道压缩发生了什么、保留了哪些内容。
 */
@Composable
private fun CompactionCard(info: dev.opencode.client.data.CompactionInfo) {
    var expanded by remember(info.summary.length) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⚡ 上下文已压缩",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                info.keptMessages?.let { kept ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "保留 $kept 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "查看摘要",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (expanded) {
                Text(
                    text = info.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onLongPress: (ChatMessage) -> Unit,
    streaming: Boolean = false,
    pendingDelivery: String? = null,
) {
    // 压缩记录：可折叠卡片（默认收起，不干扰阅读）
    message.compaction?.let { info ->
        CompactionCard(info)
        return
    }
    if (message.role == "user") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(horizontalAlignment = Alignment.End) {
                // 图片缩略图（Web 端同样在气泡里显示）
                message.images.forEach { image ->
                    ImagePreview(image)
                    Spacer(Modifier.height(4.dp))
                }
                // 路径引用的附件芯片（同 Web：📎 文件名）
                message.attachments.forEach { attachment ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (attachment.mime.startsWith("image/")) "🖼" else "📎",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (message.blocks.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .combinedClickable(onClick = {}, onLongClick = { onLongPress(message) })
                        ) {
                            Text(
                                text = message.textContent(),
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // 待投递标记：让用户知道这条输入是"将插入"还是"已排队"
                        if (pendingDelivery != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = if (pendingDelivery == "queue") "已排队" else "将插入",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { onLongPress(message) }),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            message.blocks.forEach { block ->
                when (block) {
                    is Block.Text -> when (block.type) {
                        "reasoning" -> ReasoningBlock(message.id, block.ordinal, block.text, block.durationMs)
                        else -> MarkdownText(
                            markdown = block.text,
                            modifier = Modifier.fillMaxWidth(),
                            streaming = streaming,
                        )
                    }
                    is Block.Tool -> ToolCallCard(block.call)
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(messageId: String, ordinal: Int, text: String, durationMs: Long?) {
    var expanded by remember("reasoning-$messageId-$ordinal") { mutableStateOf(false) }
    val duration = formatDuration(durationMs)

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) "思考过程" else "思考过程（点开查看）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (duration.isNotEmpty()) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 消息内图片缩略图（base64 解码，限制尺寸）。 */
@Composable
private fun ImagePreview(image: MessageImage) {
    // 解码放到后台线程：之前用 remember { decode } 在组合阶段同步解码，
    // 一张 720px 的图会卡住整帧（滚动时明显掉帧）。produceState 先出占位再替换。
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.base64) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = android.util.Base64.decode(image.base64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val maxSide = 720
                    val scale = minOf(maxSide.toFloat() / bmp.width, maxSide.toFloat() / bmp.height, 1f)
                    if (scale < 1f) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                        bmp.recycle()
                        scaled
                    } else bmp
                } else null
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = image.name ?: "图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(max = 260.dp)
                .clip(MaterialTheme.shapes.medium)
        )
    } else {
        Text(
            text = "🖼 ${image.name ?: "图片"}（无法预览）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 从 Markdown 中提取 ``` 围栏代码块内容。 */
private fun extractCodeBlocks(markdown: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inFence = false
    markdown.lineSequence().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inFence) {
                out += sb.toString().trimEnd()
                sb.clear()
            }
            inFence = !inFence
        } else if (inFence) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
    }
    return out.filter { it.isNotBlank() }
}

/** 时间戳格式化（当天显示时分，跨天显示月日时分）。 */
private fun formatTimestamp(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return java.text.SimpleDateFormat(
        if (sameDay) "HH:mm" else "MM-dd HH:mm",
        java.util.Locale.getDefault()
    ).format(java.util.Date(ms))
}

/** 贴底时多滚一点：LazyList 会把超出内容末尾的滚动量钳制掉，用它等价于"滚到底"。 */
private const val BOTTOM_SCROLL_OVERSHOOT = 100_000f
