package io.dilfi5h.agentping.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PingSettings(
    val serverUrl: String = "https://ntfy.871116.xyz",
    val token: String = "",
    val topic: String = "agentping-all",
) {
    val configured: Boolean get() = token.isNotBlank() && serverUrl.isNotBlank()
    val wsUrl: String
        get() {
            val base = serverUrl.trim().trimEnd('/')
            val wsBase = if (base.startsWith("https://")) "wss://" + base.removePrefix("https://")
                else if (base.startsWith("http://")) "ws://" + base.removePrefix("http://")
                else "wss://$base"
            return "$wsBase/${topic.trim()}/ws"
        }
}

/**
 * 进程内单例：Activity / Service / BootReceiver 必须读同一份 flow。
 * lastNtfyId 是 ntfy 续传游标（与时间线 time 无关），存在同一 prefs 里以免升 schema。
 */
class SettingsStore private constructor(app: Context) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<PingSettings> = _flow

    private fun load() = PingSettings(
        serverUrl = prefs.getString("serverUrl", null) ?: PingSettings().serverUrl,
        token = prefs.getString("token", "").orEmpty(),
        topic = prefs.getString("topic", null) ?: PingSettings().topic,
    )

    fun save(s: PingSettings) {
        val stored = s.copy(
            serverUrl = s.serverUrl.trim(),
            token = s.token.trim(),
            topic = s.topic.trim(),
        )
        // commit：第一次保存立刻拉起 Service 时，另一条路径读 disk 也能看到 token
        prefs.edit()
            .putString("serverUrl", stored.serverUrl)
            .putString("token", stored.token)
            .putString("topic", stored.topic)
            .commit()
        _flow.value = stored
    }

    fun reloadFromDisk() {
        _flow.value = load()
    }

    fun lastNtfyId(): String? =
        prefs.getString(KEY_LAST_NTFY_ID, null)?.takeIf { it.isNotBlank() }

    fun rememberNtfyId(id: String) {
        if (id.isBlank()) return
        prefs.edit().putString(KEY_LAST_NTFY_ID, id).apply()
    }

    companion object {
        private const val KEY_LAST_NTFY_ID = "lastNtfyId"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(ctx: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(ctx.applicationContext).also { instance = it }
            }
    }
}
