package dev.opencode.client

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** 崩溃日志：写入应用私有目录 + 公共 Downloads（Termux 可直接读取，便于远程诊断）。 */
class OpenCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { saveCrash(this, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("time: ").append(System.currentTimeMillis()).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append(sw.toString())
        }
        // 1) 应用私有外部目录（必定可写）
        runCatching {
            File(context.getExternalFilesDir(null), "crash-latest.txt").writeText(text)
        }
        // 2) 公共 Downloads（API 29+ 走 MediaStore；旧版本直接写，失败则忽略）
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                // 清理旧崩溃文件，保持“latest”语义
                runCatching {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'opencode-crash%'",
                        null
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "opencode-crash-latest.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "opencode-crash-latest.txt"
                ).writeText(text)
            }
        }
    }
}
