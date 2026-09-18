package io.dilfi5h.agentping.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process ring buffer log (in memory, capped at 600 entries), exported via the settings
 * page's "copy logs" button for troubleshooting.
 * Note: no call site may write sensitive values such as tokens.
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
