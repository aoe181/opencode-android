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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.client.ui.ConnectScreen
import dev.opencode.client.ui.SessionsScreen
import dev.opencode.client.ui.chat.ChatScreen
import dev.opencode.client.ui.theme.OpenCodeClientTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 夜间模式默认开启（品牌主形态），会话页可切换
            var darkMode by remember { mutableStateOf(true) }
            OpenCodeClientTheme(darkTheme = darkMode) {
                val vm: MainViewModel = viewModel()
                val state by vm.uiState.collectAsState()
                val selected by vm.selected.collectAsState()
                // 主题开关通过 MainUiState 传递到会话页：用 CompositionLocal 简化
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalThemeToggle provides { darkMode = !darkMode }
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