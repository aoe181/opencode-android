package dev.opencode.client.ui.chat

/**
 * 终端输出清洗：shell/bash 工具输出常含 ANSI 转义序列（颜色/光标控制），
 * TUI/Web 渲染成颜色，TextView 原样显示成乱码（如 [0;32m）。这里剥掉转义序列。
 */
object AnsiStripper {

    // CSI 序列: ESC [ ... 终于一个字母；OSC: ESC ] ... BEL/ST；单字符 ESC 后跟一个字母
    private val ANSI_REGEX = Regex(
        "\u001B\\[[0-9;?]*[A-Za-z]" +   // CSI（颜色/光标/清屏）
            "|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)" + // OSC（标题等）
            "|\u001B[@-Z\\\\-_]"        // 其余单字符转义
    )

    fun strip(text: String): String = ANSI_REGEX.replace(text, "")
}
