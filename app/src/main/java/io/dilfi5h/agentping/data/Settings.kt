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

/** 只存 read token；App 全程没有任何发布能力。 */
class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<PingSettings> = _flow

    private fun load() = PingSettings(
        serverUrl = prefs.getString("serverUrl", null) ?: PingSettings().serverUrl,
        token = prefs.getString("token", "").orEmpty(),
        topic = prefs.getString("topic", null) ?: PingSettings().topic,
    )

    fun save(s: PingSettings) {
        prefs.edit()
            .putString("serverUrl", s.serverUrl.trim())
            .putString("token", s.token.trim())
            .putString("topic", s.topic.trim())
            .apply()
        _flow.value = load()
    }
}
