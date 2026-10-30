package dev.opencode.client.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App 是否为深色模式。
 * 注意：App 主题由设置里的开关控制（默认深色），与系统 isSystemInDarkTheme() 无关，
 * 各组件取代码块/diff 背景色时必须读这个值，否则系统浅色时会出现刺眼的白块。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { true }
