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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatVm.onPicked(uri)
    }

    // 普通布局 + 智能吸底：上翻阅读不打扰，回到底部恢复跟随
    val listState = rememberLazyListState()
    val autoScroll = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }.collect { atBottom -> autoScroll.value = atBottom }
    }
    LaunchedEffect(state.messages.size, state.streaming) {
        // 流式输出中始终跟随；非流式时尊重用户上翻（autoScroll 标记）
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (state.streaming || autoScroll.value) {
            listState.scrollToItem(state.messages.lastIndex)
        }
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
                // 上下文占用指示器：分子=最近一次请求完整上下文(input+cache.read)，分母=模型上限
                state.contextTokens?.let { ctx ->
                    Box {
                        Text(
                            text = contextLabel(state, ctx),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable { showUsage = !showUsage }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        DropdownMenu(
                            expanded = showUsage,
                            onDismissRequest = { showUsage = false }
                        ) {
                            UsageDetails(state)
                        }
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message = message, onLongPress = { actionMessage = message })
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

            androidx.compose.animation.AnimatedVisibility(
                visible = !autoScroll.value && state.messages.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Surface(
                    onClick = {
                        autoScroll.value = true
                        scope.launch {
                            if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .padding(end = 12.dp, bottom = 12.dp)
                        .size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("↓", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
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

            // 输入行：紧凑输入框 + 附件 + 发送（发送键正上方是停止小圆点）
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (state.streaming) {
                        // 停止小圆点：仅一个 22dp 红点，无底板；点击后变灰+转圈表示正在中断
                        var stopping by remember(state.streaming) { mutableStateOf(false) }
                        LaunchedEffect(state.streaming) { if (!state.streaming) stopping = false }
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(
                                    if (stopping) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                    CircleShape
                                )
                                .clickable(enabled = !stopping) {
                                    stopping = true
                                    chatVm.interrupt()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.surface
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onError,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            chatVm.send(input)
                            input = ""
                            mainVm.saveDraft(sessionId, "")
                        },
                        enabled = !state.sending && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
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
                                            Text(
                                                text = model.providerID,
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
                    text = if (message?.role == "user") "这条输入" else "这条回复",
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
                if (message != null && message.id in state.pendingInputs) {
                    SheetAction("撤回这条输入") {
                        chatVm.cancelInbox(message.id)
                        actionMessage = null
                    }
                    SheetAction(
                        if (state.pendingInputs[message.id] == "queue") {
                            "改为插入（下一步立即送达）"
                        } else {
                            "改为排队（当前执行完再送达）"
                        }
                    ) {
                        chatVm.toggleInboxDelivery(message.id)
                        actionMessage = null
                    }
                }
            }
        }
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, onLongPress: () -> Unit) {
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
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    ) {
                        Text(
                            text = message.textContent(),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            message.blocks.forEach { block ->
                when (block) {
                    is Block.Text -> when (block.type) {
                        "reasoning" -> ReasoningBlock(message.id, block.ordinal, block.text, block.durationMs)
                        else -> MarkdownText(
                            markdown = block.text,
                            modifier = Modifier.fillMaxWidth()
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
    val bitmap = remember(image.base64) {
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
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
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
