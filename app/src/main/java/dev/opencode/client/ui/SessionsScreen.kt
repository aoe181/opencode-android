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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.client.LocalThemeToggle
import dev.opencode.client.LocalThemeToggle
import dev.opencode.client.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()
    // 长按会话 -> 弹出管理菜单（删除）
    var pendingDelete by remember { mutableStateOf<dev.opencode.client.data.SessionSummary?>(null) }

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

            if (!state.loading && state.sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无会话\n点右下角 + 新建",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(state.sessions, key = { it.id }) { session ->
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
                                onLongClick = { pendingDelete = session },
                            )
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
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
