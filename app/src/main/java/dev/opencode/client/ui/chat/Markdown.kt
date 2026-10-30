package dev.opencode.client.ui.chat

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.client.ui.theme.LocalAppDarkTheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin

/** Markwon + TextView 包装的 Markdown 渲染（主题化排版 + 官方表格扩展 + 代码染色）。 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    /** 该块是否仍在流式增长。为 true 时表格按纯文本渲染，避免列宽反复重算导致的闪屏。 */
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current
    val markwon = remember(context, dark) { buildMarkwon(context, dark, withTables = true) }
    // 流式期间禁用表格插件：TablePlugin 每次都要重算列宽/单元格背景，
    // 文本每增长一行就整表重排，视觉上就是"闪"。降级为纯文本后列宽不再变化。
    val markwonNoTables = remember(context, dark) { buildMarkwon(context, dark, withTables = false) }
    val hasTable = remember(markdown) { looksLikeTable(markdown) }
    val usePlain = streaming && hasTable
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                // 表格/代码块频繁变化时避免布局抖动（闪烁）
                setLineSpacing(0f, 1.15f)
            }
        },
        update = { tv ->
            tv.setTextColor(color.toArgb())
            val renderer = if (usePlain) markwonNoTables else markwon
            renderer.setMarkdown(tv, markdown)
            // 语法染色是纯 CPU 遍历，流式时文本增长很快；长文本降频以免抢主线程帧时间
            if (markdown.length <= HIGHLIGHT_SYNC_LIMIT || markdown.endsWith("```")) {
                Highlighter.apply(tv)
            }
        }
    )
}

/** 构造 Markwon；withTables=false 时不装表格插件（流式降级用）。 */
private fun buildMarkwon(
    context: android.content.Context,
    dark: Boolean,
    withTables: Boolean,
): Markwon {
    val borderColor = if (dark) 0xFF2C3340.toInt() else 0xFFCBD3E1.toInt()
    val builder = Markwon.builder(context)
        .usePlugin(CorePlugin.create())
    if (withTables) {
        builder.usePlugin(
            TablePlugin.create { b ->
                b
                    .tableBorderColor(borderColor)
                    .tableBorderWidth(2)
                    .tableCellPadding(8)
                    .tableEvenRowBackgroundColor(if (dark) 0xFF14171F.toInt() else 0xFFF7F8FB.toInt())
                    .tableOddRowBackgroundColor(if (dark) 0xFF1A1E28.toInt() else 0xFFEAEDF4.toInt())
                    .tableHeaderRowBackgroundColor(if (dark) 0xFF11141B.toInt() else 0xFFFFFFFF.toInt())
            }
        )
    }
    builder.usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(b: MarkwonTheme.Builder) {
            b
                .codeTextColor(0xFFBD93F9.toInt())
                .codeBackgroundColor(if (dark) 0xFF2A2A32.toInt() else 0xFFECEEF2.toInt())
                .codeBlockBackgroundColor(if (dark) 0xFF17181D.toInt() else 0xFFF4F5F7.toInt())
                .linkColor(0xFF42A5F5.toInt())
                .isLinkUnderlined(true)
        }
    })
    return builder.build()
}

/**
 * 粗判是否含 Markdown 表格：存在一行含至少两个 `|`，
 * 且下一行（或上一行）是 `|---|` 这类分隔行。
 */
private fun looksLikeTable(markdown: String): Boolean {
    if (markdown.indexOf('|') < 0) return false
    val lines = markdown.lines()
    for (i in lines.indices) {
        if (lines[i].count { it == '|' } < 2) continue
        val next = lines.getOrNull(i + 1) ?: continue
        if (next.contains('-') && next.count { it == '|' } >= 2) return true
    }
    return false
}

/** 超过该长度时，只在代码块闭合（``` 结尾）才重新染色，避免流式期间每帧全量扫描。 */
private const val HIGHLIGHT_SYNC_LIMIT = 1500
