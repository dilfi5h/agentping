package io.dilfi5h.agentping.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dilfi5h.agentping.data.SettingsStore

/** Start on boot: launch the foreground service only if a read token is configured (DESIGN.md §4). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SettingsStore.get(context).flow.value.configured) {
            context.startForegroundService(Intent(context, PingService::class.java))
        }
    }
}
