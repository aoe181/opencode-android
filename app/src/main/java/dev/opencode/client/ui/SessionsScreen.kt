package dev.opencode.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.client.LocalThemeToggle
import dev.opencode.client.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()
    // 长按会话 -> 弹出管理菜单（删除）
    var pendingDelete by remember { mutableStateOf<dev.opencode.client.data.SessionSummary?>(null) }
    // 长按弹出操作菜单（重命名 / 删除）
    var actionSession by remember { mutableStateOf<dev.opencode.client.data.SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<dev.opencode.client.data.SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 会话搜索
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val visibleSessions = remember(state.sessions, searchQuery) {
        if (searchQuery.isBlank()) state.sessions
        else state.sessions.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "OpenCode 会话",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                actions = {
                    // 夜间模式切换
                    val themeToggle = LocalThemeToggle.current
                    IconButton(onClick = themeToggle) {
                        Icon(
                            Icons.Filled.Home,
                            contentDescription = "切换夜间/日间模式",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = if (searchOpen) "关闭搜索" else "搜索会话",
                            tint = if (searchOpen) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    TextButton(onClick = { vm.disconnect() }) {
                        Text("断开", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createSession() }) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索会话标题或 ID", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            state.serverInfo?.let { info ->
                val label = buildString {
                    append("已连接：")
                    append(state.server?.baseUrl)
                    info.version?.let { append("  ·  v").append(it) }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (!state.loading && visibleSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无会话\n点右下角 + 新建",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // 滚动到接近底部时自动加载下一页
                LaunchedEffect(listState, state.sessionsCursor) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= info.totalItemsCount - 3
                    }.collect { nearBottom ->
                        if (nearBottom && state.sessionsCursor != null && !state.loadingMore &&
                            searchQuery.isBlank()
                        ) {
                            vm.loadMoreSessions()
                        }
                    }
                }
                LazyColumn(state = listState) {
                    items(visibleSessions, key = { it.id }) { session ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    session.id,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { vm.openSession(session.id, session.title) },
                                onLongClick = { actionSession = session },
                            )
                        )
                        HorizontalDivider()
                    }
                    if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinearProgressIndicator(Modifier.fillMaxWidth(0.4f))
                            }
                        }
                    } else if (state.sessionsCursor == null && visibleSessions.isNotEmpty()) {
                        item(key = "no-more") {
                            Text(
                                text = "— 已加载全部 —",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 长按会话：操作菜单
    actionSession?.let { target ->
        AlertDialog(
            onDismissRequest = { actionSession = null },
            title = { Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        "重命名",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                renameTarget = target
                                renameText = target.title
                                actionSession = null
                            }
                            .padding(vertical = 10.dp)
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pendingDelete = target
                                actionSession = null
                            }
                            .padding(vertical = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { actionSession = null }) { Text("取消") }
            }
        )
    }

    // 重命名输入框
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标题") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameSession(target.id, renameText)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // 长按会话：删除确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除对话") },
            text = {
                Text(
                    "确定删除「${target.title}」？\n该会话及其子会话的消息将被永久删除，无法恢复。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(target.id)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
