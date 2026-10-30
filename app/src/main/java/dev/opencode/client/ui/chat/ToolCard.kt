package dev.opencode.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.client.data.ToolCall
import dev.opencode.client.data.ToolFileChange
import dev.opencode.client.data.formatDuration
import dev.opencode.client.ui.theme.LocalAppDarkTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * 工具调用卡片。
 *
 * 按工具类型区分的展示策略（当前服务器实际会出现的工具：shell / edit / write / read 等）：
 * - edit / write：有统一 diff -> 展示 diff（+n -n），不重复显示输入输出
 * - shell：展示命令 + 输出（后台运行中带徽章；输出被截断时提示）
 * - read / grep / glob 等：展示输入参数 + 结果
 * - 其余未知工具：通用回退（输入 + 输出）
 */
@Composable
fun ToolCallCard(call: ToolCall) {
    var expanded by remember(call.id) { mutableStateOf(false) }
    val duration = formatDuration(call.durationMs)
    val hasDiff = call.fileChanges.isNotEmpty()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(call.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (call.background) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "后台运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = call.title ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasDiff) {
                    val adds = call.fileChanges.sumOf { it.additions }
                    val dels = call.fileChanges.sumOf { it.deletions }
                    Text(
                        text = "+$adds -$dels",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4ADE80)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (duration.isNotEmpty()) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = durationColor(call.state, call.background)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (expanded) {
                when {
                    hasDiff -> call.fileChanges.forEach { DiffView(it) }
                    call.name == "shell" -> {
                        ToolSection("命令", call.input)
                        if (call.truncated) {
                            Text(
                                text = "输出被截断（完整内容见服务器日志）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        ToolSection("输出", call.output)
                    }
                    else -> {
                        // read / grep / glob / task 等：参数 + 结果
                        ToolSection("参数", call.input)
                        ToolSection("结果", call.output)
                    }
                }
            }
        }
    }
}

/** 耗时颜色：与状态一致（成功青、失败红、后台紫、运行中次要色）。 */
@Composable
private fun durationColor(state: String, background: Boolean): Color = when {
    background -> MaterialTheme.colorScheme.tertiary
    state == "failed" -> MaterialTheme.colorScheme.error
    state == "success" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StatusIcon(state: String) {
    when (state) {
        "success" -> Icon(
            Icons.Filled.Check,
            contentDescription = "成功",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        "failed" -> Icon(
            Icons.Filled.Close,
            contentDescription = "失败",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        else -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ToolSection(label: String, content: String?) {
    if (content.isNullOrBlank()) return
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "复制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    Text(
        text = highlightedCode(content),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 146.dp)
            .background(codeBackground(), MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    )
}

@Composable
private fun codeBackground(): Color =
    if (LocalAppDarkTheme.current) Color(0xFF17181D) else Color(0xFFF4F5F7)

/** 统一 diff 渲染：+ 绿 / - 红 / @@ 青 / 头部灰。 */
@Composable
private fun DiffView(change: ToolFileChange) {
    val green = Color(0xFF4ADE80)
    val red = Color(0xFFFF5C7A)

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = change.file.substringAfterLast('/'),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "+${change.additions} -${change.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = green
            )
        }
        Text(
            text = highlightedDiff(change.patch),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .background(codeBackground(), MaterialTheme.shapes.small)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        )
    }
}

private fun highlightedDiff(patch: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    patch.split("\n").forEachIndexed { index, line ->
        val color = when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("Index:") -> Color(0xFF9AA3B5)
            line.startsWith("@@") -> Color(0xFF22D3EE)
            line.startsWith("+") -> Color(0xFF4ADE80)
            line.startsWith("-") -> Color(0xFFFF5C7A)
            else -> Color(0xFF9AA3B5).copy(alpha = 0.7f)
        }
        if (index > 0) append("\n")
        pushStyle(SpanStyle(color = color))
        append(line)
        pop()
    }
}

private fun highlightedCode(content: String): androidx.compose.ui.text.AnnotatedString {
    val spans = Highlighter.highlightCode(content) as android.text.SpannableString
    return buildAnnotatedString {
        append(spans.toString())
        spans.getSpans(
            0, spans.length, android.text.style.ForegroundColorSpan::class.java
        ).forEach { span ->
            addStyle(
                SpanStyle(color = Color(span.foregroundColor)),
                spans.getSpanStart(span),
                spans.getSpanEnd(span)
            )
        }
    }
}
