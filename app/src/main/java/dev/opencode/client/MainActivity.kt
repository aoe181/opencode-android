package dev.opencode.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.opencode.client.ui.ConnectScreen
import dev.opencode.client.ui.SessionsScreen
import dev.opencode.client.ui.chat.ChatScreen
import dev.opencode.client.ui.theme.OpenCodeClientTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeStore = remember { dev.opencode.client.data.SettingsStore(applicationContext) }
            val scope = rememberCoroutineScope()
            // 夜间模式：从 DataStore 读取（默认深色），切换后立即持久化
            var darkMode by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { darkMode = themeStore.loadDarkTheme() }
            OpenCodeClientTheme(darkTheme = darkMode) {
                val vm: MainViewModel = viewModel()
                val state by vm.uiState.collectAsState()
                val selected by vm.selected.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalThemeToggle provides {
                        darkMode = !darkMode
                        scope.launch { themeStore.saveDarkTheme(darkMode) }
                    }
                ) {
                    // imePadding：键盘弹出时整体上移，聊天输入栏不被遮挡
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                    ) {
                    when {
                        !state.connected -> {
                            if (state.restoring) {
                                // 启动自动重连：轻量过渡页，不闪表单
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(Modifier.size(32.dp))
                                        Text(
                                            "正在连接 ${state.server?.baseUrl ?: ""} …",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                ConnectScreen(vm)
                            }
                        }
                        selected != null -> ChatScreen(vm)
                        else -> SessionsScreen(vm)
                    }
                    }
                }
            }
        }
    }
}

/** 会话页夜间模式切换回调（MainActivity 提供）。 */
val LocalThemeToggle = androidx.compose.runtime.staticCompositionLocalOf { {} }