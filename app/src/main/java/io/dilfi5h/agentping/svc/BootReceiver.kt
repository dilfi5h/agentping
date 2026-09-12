package io.dilfi5h.agentping.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dilfi5h.agentping.data.SettingsStore

/** 开机自启：已配置 read token 才拉起前台服务（DESIGN.md §4）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SettingsStore(context).flow.value.configured) {
            context.startForegroundService(Intent(context, PingService::class.java))
        }
    }
}
