package io.github.mangi.eta.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行痕迹：一行 logcat + 一行落盘（files/traces/eta-trace-<day>.log）。
 *
 * 3.4.1 官方没有这个工具类，但 fork（补丁链）里大量使用；移植时保留同一套接口，
 * 让自动化/虚拟屏那些链路日志继续可用，排查时不用靠 logcat 抢时间窗口。
 */
object EtaTrace {
    private const val TAG = "EtaTrace"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var dir: File? = null

    fun init(fileDir: File?) {
        dir = fileDir?.let { File(it, "traces").apply { runCatching { mkdirs() } } }
    }

    fun log(tag: String, message: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) +
            " [" + Thread.currentThread().name + "] " + tag + " " + message
        runCatching { Log.i(TAG, line) }
        val target = dir ?: return
        runCatching {
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(target, "eta-trace-$day.log")
            if (file.length() > MAX_BYTES) {
                runCatching { File(target, "eta-trace-$day.log.1").delete() }
                runCatching { file.renameTo(File(target, "eta-trace-$day.log.1")) }
            }
            file.appendText(line + "\n")
        }
    }
}
