package io.dilfi5h.agentping.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.dilfi5h.agentping.MainActivity
import io.dilfi5h.agentping.R
import io.dilfi5h.agentping.data.AppDatabase
import io.dilfi5h.agentping.data.NtfyFrame
import io.dilfi5h.agentping.data.SettingsStore
import io.dilfi5h.agentping.data.parsePayload
import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.net.NtfyStream
import io.dilfi5h.agentping.notify.localTestNotifySpec
import io.dilfi5h.agentping.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsStore
    private lateinit var db: AppDatabase
    private lateinit var stream: NtfyStream

    private val kick = MutableStateFlow(0)
    private var loopJob: kotlinx.coroutines.Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Number of messages already notified one-by-one within the current connection (resume/real-time mode); the rest are merged into a summary. */
    private val resumeCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        AppLog.log("SVC", "onCreate")
        settings = SettingsStore.get(this)
        db = AppDatabase.get(this)
        stream = NtfyStream(scope, ::onFrame, ::onConnState)
        ensureChannels(this)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.log("SVC", "onStartCommand action=${intent?.action ?: "null"} startId=$startId")
        startAsForeground()
        // Idempotent: repeated starts don't stack loops; reload/refresh (config change or pull-to-refresh) cancels the old loop and restarts
        if (intent?.action == ACTION_REFRESH) sinceOverride.value = SINCE_BACKFILL
        val reload = intent?.action == ACTION_RELOAD || intent?.action == ACTION_REFRESH
        if (reload) {
            settings.reloadFromDisk()
            loopJob?.cancel()
        }
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When the user swipes the task away, some ROMs kill the foreground service too; restart after 1s
        AppLog.log("SVC", "onTaskRemoved -> scheduling restart")
        val restart = Intent(applicationContext, PingService::class.java)
        val pi = PendingIntent.getForegroundService(
            applicationContext, 1, restart,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.log("SVC", "onDestroy")
        unregisterNetworkCallback()
        scope.cancel()
        stream.shutdown()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing running")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()
        startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Main subscription loop: reconnect with exponential backoff; reset after a successful open; retry immediately when the network comes back (kick). */
    private suspend fun runLoop() {
        var backoff = 1000L
        var seenKick = kick.value
        while (scope.isActive) {
            val cfg = settings.flow.first { it.configured }
            // Priority: forced replay from pull-to-refresh > processed ntfy cursor (resume after disconnect) > replay 12h cache on first connect
            val forced = sinceOverride.value
            if (forced != null) sinceOverride.value = null
            val since = forced ?: settings.lastNtfyId() ?: db.dao().lastId() ?: SINCE_BACKFILL
            // Backfill mode = 12h history replay on first connect after reinstall / pull-to-refresh, timeline only without notifying;
            // resume after a disconnect (lastId) recovers real messages from the outage and must notify as usual
            val backfill = since == SINCE_BACKFILL
            resumeCount.set(0)
            onConnState("Connecting")
            AppLog.log("LOOP", "connecting since=$since backfill=$backfill backoff=${backoff}ms")
            val opened = stream.connectOnce(cfg, since, backfill).let {
                it is io.dilfi5h.agentping.net.StreamEnd.Closed && it.opened
            }
            AppLog.log("LOOP", "ended opened=$opened net=${netState()}")
            if (opened) { backoff = 1000L; continue }
            updateServiceNotification()
            // Backoff wait; if the network comes back during it (kick counter changes), retry immediately without growing the backoff
            val kicked = withTimeoutOrNull(backoff) { kick.first { it != seenKick } } != null
            if (kicked) { AppLog.log("LOOP", "kick: retry now"); seenKick = kick.value }
            else { AppLog.log("LOOP", "backoff -> ${backoff * 2}ms"); backoff = (backoff * 2).coerceAtMost(60_000L) }
        }
    }

    private suspend fun onFrame(frame: NtfyFrame) {
        val payload = parsePayload(frame.message)
        val entity = MessageEntity(
            id = frame.id,
            time = frame.time * 1000,
            topic = frame.topic,
            title = frame.title,
            raw = frame.message,
            agent = payload?.agent,
            host = payload?.host,
            state = payload?.state,
            task = payload?.task,
            detail = payload?.detail,
            session = payload?.session,
            ts = payload?.ts,
            dur = payload?.dur,
        )
        if (entity.id.isBlank()) { AppLog.log("DB", "skip blank id"); return }
        if (db.deletedDao().exists(entity.id)) {
            AppLog.log("DB", "tombstoned id=${entity.id}, skip (user deleted)")
            settings.rememberNtfyId(entity.id)
            return
        }
        val rowId = db.dao().insert(entity) // ntfy id is idempotent; a duplicate returns -1 via IGNORE
        settings.rememberNtfyId(entity.id)
        if (rowId == -1L) {
            AppLog.log("DB", "dup id=${entity.id}")
            return
        }
        // Backfill (first connect after reinstall / pull-to-refresh) only fills the timeline; resume still notifies, and anything over the cap is merged into a summary to prevent flooding;
        // real-time messages (published after the connection opened, aligned by the server's Date header) are not subject to the cap
        if (stream.backfillMode) {
            AppLog.log("NOTIF", "backfill id=${entity.id}, timeline only (no notify)")
        } else if (entity.stateKind == io.dilfi5h.agentping.data.StateKind.STARTED) {
            notifyMessage(entity) // started events are frequent and carry no action value; handled internally as timeline-only
        } else if (entity.time >= stream.openServerTimeMillis) {
            notifyMessage(entity)
        } else {
            val n = resumeCount.incrementAndGet()
            if (n <= RESUME_NOTIFY_CAP) {
                notifyMessage(entity)
            } else {
                notifySummary(entity, n - RESUME_NOTIFY_CAP)
            }
        }
        pruneOld()
    }

    private suspend fun pruneOld() {
        // ntfy cache-duration is 12h; keep local history for 7 days so search still finds recent sessions
        db.dao().prune(System.currentTimeMillis() - 7 * 24 * 3600_000L)
        db.deletedDao().prune(System.currentTimeMillis() - 8 * 24 * 3600_000L)
    }

    // ---- Notifications ----

    private fun notifyMessage(m: MessageEntity) {
        // "Started" only goes to the timeline, no notification (frequent and no action value)
        if (m.stateKind == io.dilfi5h.agentping.data.StateKind.STARTED) {
            AppLog.log("NOTIF", "started -> timeline only, no notification")
            return
        }
        val alert = m.stateKind == io.dilfi5h.agentping.data.StateKind.WAITING ||
            m.stateKind == io.dilfi5h.agentping.data.StateKind.FAILED
        val title = m.title ?: "[${m.host ?: "?"}] ${m.agent ?: "shell"} ${m.stateKind.label}"
        val text = listOfNotNull(m.task, m.detail).joinToString("\n")
            .ifBlank { m.raw ?: "" }
        val n = NotificationCompat.Builder(this, if (alert) CH_ALERT else CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent())
            .setCategory(
                if (alert) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_STATUS
            )
            .build()
        try {
            // When POST_NOTIFICATIONS is denied, notify() doesn't throw but silently drops it; log it explicitly here
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.log("NOTIF", "!! POST_NOTIFICATIONS not granted, system will drop this")
            }
            AppLog.log("NOTIF", "post ${if (alert) "alert" else "status"}: $title")
            val nm = getSystemService(NotificationManager::class.java)
            // importance read at creation time is unreliable (after the system auto-silences, it may still return the original value); re-read when posting
            val imp = nm.getNotificationChannel(if (alert) CH_ALERT else CH_STATUS)?.importance ?: -1
            AppLog.log("NOTIF", "channel ${if (alert) CH_ALERT else CH_STATUS} importance=$imp")
            nm.notify(NOTIF_TAG_MSG, m.id.hashCode(), n)
        } catch (e: SecurityException) {
            AppLog.log("NOTIF", "no permission: ${e.message}")
            // POST_NOTIFICATIONS not granted: the message is still in the timeline
        }
    }

    private fun notifySummary(m: MessageEntity, extra: Int) {
        // After a long outage, hundreds of messages may be recovered; notifying one by one would re-trigger the system's "auto-silence" downgrade of the channel;
        // everything over the cap is merged into one summary whose count updates as more older messages arrive
        val title = m.title ?: "[${m.host ?: "?"}] ${m.agent ?: "shell"} ${m.stateKind.label}"
        val text = listOfNotNull(m.task, m.detail).joinToString("\n").ifBlank { m.raw ?: "" }
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing missed messages")
            .setContentText("Latest: $title (plus $extra earlier messages, tap to open)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        AppLog.log("NOTIF", "post summary: extra=$extra latest=$title")
        val nm = getSystemService(NotificationManager::class.java)
        AppLog.log("NOTIF", "channel $CH_ALERT importance=${nm.getNotificationChannel(CH_ALERT)?.importance ?: -1}")
        nm.notify(NOTIF_TAG_SUMMARY, NOTIF_SUMMARY, n)
    }

    private fun updateServiceNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_SERVICE, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing running")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()

    // ---- Connection state (shared by UI and foreground notification) ----

    private fun onConnState(s: String) {
        AppLog.log("STATE", s)
        connectionState.value = s
        scope.launch { updateServiceNotification() }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kick.value += 1 // network is back, retry immediately
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb,
        )
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        }
    }

    /** Active network profile (vpn/wifi/cell), used to correlate with VPN reconnect timestamps when connections fail. */
    private fun netState(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        val t = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
        }
        return t.joinToString("+").ifBlank { "other" }
    }

    private fun mainIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CH_STATUS = io.dilfi5h.agentping.notify.CH_STATUS
        const val CH_ALERT = io.dilfi5h.agentping.notify.CH_ALERT
        const val CH_SERVICE = io.dilfi5h.agentping.notify.CH_SERVICE
        const val NOTIF_SERVICE = 1
        const val NOTIF_SUMMARY = 3
        /** Kept separate from the FGS/summary notification ids to avoid hashCode collisions overwriting the persistent notification at 1/3. */
        const val NOTIF_TAG_MSG = "msg"
        const val NOTIF_TAG_SUMMARY = "summary"

        /** Per-connection cap on one-by-one notifications during resume; the rest are merged into a summary. */
        const val RESUME_NOTIFY_CAP = 10

        /** Connection state text read directly by the UI. */
        val connectionState = MutableStateFlow("Disconnected")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PingService::class.java))
        }

        /** Call after a config change: cancel the old connection loop and reconnect immediately with the new config. */
        fun reload(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_RELOAD)
            ctx.startForegroundService(i)
        }

        /** Pull-to-refresh: reconnect and replay the recent cache (since=12h) to fill in messages from before install / during the outage. */
        fun refresh(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_REFRESH)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PingService::class.java))
            connectionState.value = "Disconnected"
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Old channel ids are retired: the system's "auto-silence" downgrade can't be reverted from code, so use new ids to force a reset.
            // -v2 was also re-triggered by the 09-12 test flooding; bumping to -v3; resume cap of 10 + summary to prevent another trigger
            for (old in listOf("status", "alert", "service", "status-v2", "alert-v2", "service-v2")) {
                nm.deleteNotificationChannel(old)
            }
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Task status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Failure & waiting", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Service running", NotificationManager.IMPORTANCE_MIN).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            for (id in listOf(CH_STATUS, CH_ALERT, CH_SERVICE)) {
                nm.getNotificationChannel(id)?.let {
                    AppLog.log("NOTIF", "channel $id importance=${it.importance}")
                }
            }
        }

        /** Bypasses ntfy/Room/cursor, only verifying that the failure & waiting channel can really pop. */
        fun postLocalTest(ctx: Context) {
            ensureChannels(ctx)
            val spec = localTestNotifySpec()
            val n = NotificationCompat.Builder(ctx, spec.channelId)
                .setSmallIcon(R.drawable.ic_stat_ping)
                .setContentTitle(spec.title)
                .setContentText(spec.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.log("NOTIF", "!! POST_NOTIFICATIONS not granted, system will drop this")
            }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val imp = nm.getNotificationChannel(spec.channelId)?.importance ?: -1
            AppLog.log("NOTIF", "post test channel=${spec.channelId} importance=$imp")
            nm.notify(spec.tag, spec.id, n)
        }

        const val ACTION_RELOAD = "io.dilfi5h.agentping.RELOAD"
        const val ACTION_REFRESH = "io.dilfi5h.agentping.REFRESH"
        const val SINCE_BACKFILL = "12h"

        private val sinceOverride = MutableStateFlow<String?>(null)
    }
}
