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
) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current
    val markwon = remember(context, dark) {
        val borderColor = if (dark) 0xFF2C3340.toInt() else 0xFFCBD3E1.toInt()
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(
                TablePlugin.create { builder ->
                    builder
                        .tableBorderColor(borderColor)
                        .tableBorderWidth(2)
                        .tableCellPadding(8)
                        .tableEvenRowBackgroundColor(if (dark) 0xFF14171F.toInt() else 0xFFF7F8FB.toInt())
                        .tableOddRowBackgroundColor(if (dark) 0xFF1A1E28.toInt() else 0xFFEAEDF4.toInt())
                        .tableHeaderRowBackgroundColor(if (dark) 0xFF11141B.toInt() else 0xFFFFFFFF.toInt())
                }
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(0xFFBD93F9.toInt())
                        .codeBackgroundColor(if (dark) 0xFF2A2A32.toInt() else 0xFFECEEF2.toInt())
                        .codeBlockBackgroundColor(if (dark) 0xFF17181D.toInt() else 0xFFF4F5F7.toInt())
                        .linkColor(0xFF42A5F5.toInt())
                        .isLinkUnderlined(true)
                }
            })
            .build()
    }
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
            markwon.setMarkdown(tv, markdown)
            // 语法染色是纯 CPU 遍历，流式时文本增长很快；长文本降频以免抢主线程帧时间
            if (markdown.length <= HIGHLIGHT_SYNC_LIMIT || markdown.endsWith("```")) {
                Highlighter.apply(tv)
            }
        }
    )
}

/** 超过该长度时，只在代码块闭合（``` 结尾）才重新染色，避免流式期间每帧全量扫描。 */
private const val HIGHLIGHT_SYNC_LIMIT = 1500
