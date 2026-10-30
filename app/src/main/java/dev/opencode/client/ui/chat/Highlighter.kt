package dev.opencode.client.ui.chat

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * 轻量语法染色：
 * - [highlightCode]：给任意代码文本上色（工具输出框等纯文本场景）；
 * - [apply]：给 Markwon 渲染后的 TextView 上色（扫描 CodeBlockSpan 覆盖区）。
 * 通用 token 规则：关键字紫 / 字符串绿 / 注释灰 / 数字橙。无外部依赖。
 */
object Highlighter {

    val KEYWORD_COLOR = 0xFFC792EA.toInt()   // 紫
    val STRING_COLOR = 0xFFC3E88D.toInt()    // 绿
    val COMMENT_COLOR = 0xFF78909C.toInt()   // 蓝灰
    val NUMBER_COLOR = 0xFFF78C6C.toInt()    // 橙

    private val KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "for", "while", "when", "return", "class", "object",
        "interface", "import", "package", "data", "sealed", "suspend", "override", "private",
        "public", "protected", "static", "final", "void", "int", "long", "boolean", "bool",
        "string", "str", "float", "double", "char", "true", "false", "null", "nil", "none",
        "this", "self", "super", "new", "try", "catch", "finally", "throw", "throws", "def",
        "from", "lambda", "pass", "elif", "let", "const", "function", "async", "await",
        "export", "default", "extends", "implements", "echo", "local", "then", "fi", "do",
        "done", "in", "is", "not", "and", "or", "struct", "enum", "type", "use", "pub", "fn",
        "match", "case", "switch", "break", "continue", "yield", "with", "as",
    )

    // 字符串（单双引号、反引号）、行注释（# //）、块注释（/* */）、数字
    private val TOKEN_REGEX = Regex(
        "\"(?:\\\\.|[^\"\\\\])*\"" +
            "|'(?:\\\\.|[^'\\\\])*'" +
            "|`(?:\\\\.|[^`\\\\])*`" +
            "|#[^\\n]*" +
            "|//[^\\n]*" +
            "|/\\*[\\s\\S]*?\\*/" +
            "|\\b\\d+(?:\\.\\d+)?\\b",
        RegexOption.MULTILINE
    )

    private val WORD_REGEX = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")

    /** 给代码文本上色（纯文本场景）。 */
    fun highlightCode(code: String): CharSequence {
        val spannable = SpannableString(code)
        for (match in TOKEN_REGEX.findAll(code)) {
            val token = match.value
            val color = when {
                token.startsWith("#") || token.startsWith("//") || token.startsWith("/*") -> COMMENT_COLOR
                token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") -> STRING_COLOR
                token.first().isDigit() -> NUMBER_COLOR
                else -> continue
            }
            spannable.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        for (match in WORD_REGEX.findAll(code)) {
            val word = match.value
            if (word !in KEYWORDS) continue
            val ps = match.range.first
            val pe = match.range.last + 1
            val covered = (ps until pe).any { pos ->
                spannable.getSpans(pos, pos, ForegroundColorSpan::class.java).isNotEmpty()
            }
            if (!covered) {
                spannable.setSpan(
                    ForegroundColorSpan(KEYWORD_COLOR),
                    ps, pe,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    /** 给 Markwon 渲染后的 TextView 上色（扫描 CodeBlockSpan 覆盖区）。 */
    fun apply(textView: android.widget.TextView) {
        val spannable = textView.text as? SpannableString ?: return
        val codeSpans = spannable.getSpans(0, spannable.length, io.noties.markwon.core.spans.CodeBlockSpan::class.java)
        if (codeSpans.isEmpty()) return

        for (span in codeSpans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (end - start < 3) continue
            val s = if (start < end && spannable[start] == '\n') start + 1 else start
            val e = if (end > s && spannable[end - 1] == '\n') end - 1 else end
            if (e - s < 3) continue

            val code = spannable.subSequence(s, e).toString()
            for (match in TOKEN_REGEX.findAll(code)) {
                val token = match.value
                val color = when {
                    token.startsWith("#") || token.startsWith("//") || token.startsWith("/*") -> COMMENT_COLOR
                    token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") -> STRING_COLOR
                    token.first().isDigit() -> NUMBER_COLOR
                    else -> continue
                }
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    s + match.range.first,
                    s + match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            for (match in WORD_REGEX.findAll(code)) {
                val word = match.value
                if (word !in KEYWORDS) continue
                val ps = s + match.range.first
                val pe = s + match.range.last + 1
                val covered = (ps until pe).any { pos ->
                    spannable.getSpans(pos, pos, ForegroundColorSpan::class.java).isNotEmpty()
                }
                if (!covered) {
                    spannable.setSpan(
                        ForegroundColorSpan(KEYWORD_COLOR),
                        ps, pe,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
    }
}
