package io.dilfi5h.agentping.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内环形日志（内存，最多 600 条），供设置页"复制日志"导出排障。
 * 注意：任何调用点都不得写入 token 等敏感值。
 */
object AppLog {
    private const val CAP = 600
    private val lines = ArrayDeque<String>(CAP)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        Log.i("AgentPing", line)
        synchronized(lines) {
            if (lines.size >= CAP) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun size(): Int = synchronized(lines) { lines.size }
}
