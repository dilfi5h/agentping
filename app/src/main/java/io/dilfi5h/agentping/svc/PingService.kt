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
    /** 当前连接（续传/实时模式）内已逐条通知的消息数，超出部分进摘要。 */
    private val resumeCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        AppLog.log("SVC", "onCreate")
        settings = SettingsStore.get(this)
        db = AppDatabase.get(this)
        stream = NtfyStream(scope, ::onFrame, ::onConnState)
        createChannels()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.log("SVC", "onStartCommand action=${intent?.action ?: "null"} startId=$startId")
        startAsForeground()
        // 幂等：重复 start 不叠加循环；reload/refresh（配置变更或下拉刷新）取消旧循环重开
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
        // 用户划掉任务卡时，部分 ROM 会连前台服务一起杀；1 秒后拉起重建
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
            .setContentTitle("AgentPing 运行中")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()
        startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** 订阅主循环：指数退避重连；open 成功后重置；网络恢复 kick 立即重试。 */
    private suspend fun runLoop() {
        var backoff = 1000L
        var seenKick = kick.value
        while (scope.isActive) {
            val cfg = settings.flow.first { it.configured }
            // 优先级：下拉刷新强制回放 > 已处理的 ntfy 游标（断线续传） > 首次连接回放 12h 缓存
            val forced = sinceOverride.value
            if (forced != null) sinceOverride.value = null
            val since = forced ?: settings.lastNtfyId() ?: db.dao().lastId() ?: SINCE_BACKFILL
            // 回放模式 = 重装首连/下拉刷新的 12h 历史回放，只进时间线不通知；
            // 断线续传（lastId）补回的是断连期间的真消息，必须照常通知
            val backfill = since == SINCE_BACKFILL
            resumeCount.set(0)
            onConnState("连接中")
            AppLog.log("LOOP", "connecting since=$since backfill=$backfill backoff=${backoff}ms")
            val opened = stream.connectOnce(cfg, since, backfill).let {
                it is io.dilfi5h.agentping.net.StreamEnd.Closed && it.opened
            }
            AppLog.log("LOOP", "ended opened=$opened net=${netState()}")
            if (opened) { backoff = 1000L; continue }
            updateServiceNotification()
            // 退避等待；期间网络恢复（kick 计数变化）则立即重试且不累加退避
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
        val rowId = db.dao().insert(entity) // ntfy id 幂等，重复消息 IGNORE 返回 -1
        settings.rememberNtfyId(entity.id)
        if (rowId == -1L) {
            AppLog.log("DB", "dup id=${entity.id}")
            return
        }
        // 回放（重装首连/下拉刷新）只进时间线；断线续传照常通知，超出上限的合并为摘要防洪水；
        // 实时消息（连接打开后发布，按服务器 Date 头对齐）不受上限约束
        if (stream.backfillMode) {
            AppLog.log("NOTIF", "backfill id=${entity.id}, timeline only (no notify)")
        } else if (entity.stateKind == io.dilfi5h.agentping.data.StateKind.STARTED) {
            notifyMessage(entity) // started 高频无行动价值，内部直接转时间线
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
        // cache-duration 12h，本地历史略长：留 3 天
        db.dao().prune(System.currentTimeMillis() - 3 * 24 * 3600_000L)
        db.deletedDao().prune(System.currentTimeMillis() - 2 * 24 * 3600_000L)
    }

    // ---- 通知 ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        // 旧渠道 ID 作废：系统"自动静默"降级无法用代码改回，换新 ID 强制重置。
        // -v2 也被 09-12 的测试灌水重新触发了降级，升级到 -v3；续传 10 条上限+摘要防再次触发
        for (old in listOf("status", "alert", "service", "status-v2", "alert-v2", "service-v2")) {
            nm.deleteNotificationChannel(old)
        }
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "任务状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "失败与等待", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "服务运行", NotificationManager.IMPORTANCE_MIN).apply {
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

    private fun notifyMessage(m: MessageEntity) {
        // 开始运行只进时间线，不发通知（高频且无行动价值）
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
            // POST_NOTIFICATIONS 被拒时 notify() 不抛异常而是静默丢弃，这里显式记录
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.log("NOTIF", "!! POST_NOTIFICATIONS not granted, system will drop this")
            }
            AppLog.log("NOTIF", "post ${if (alert) "alert" else "status"}: $title")
            val nm = getSystemService(NotificationManager::class.java)
            // 创建时读的 importance 不可信（系统自动静默后可能仍返回原值），发通知时再读一次
            val imp = nm.getNotificationChannel(if (alert) CH_ALERT else CH_STATUS)?.importance ?: -1
            AppLog.log("NOTIF", "channel ${if (alert) CH_ALERT else CH_STATUS} importance=$imp")
            nm.notify(NOTIF_TAG_MSG, m.id.hashCode(), n)
        } catch (e: SecurityException) {
            AppLog.log("NOTIF", "no permission: ${e.message}")
            // POST_NOTIFICATIONS 未授予：消息仍在时间线里
        }
    }

    private fun notifySummary(m: MessageEntity, extra: Int) {
        // 断线时间长时补回消息可能上百条，逐条通知会再次触发系统对渠道的"自动静默"降级；
        // 超出上限的合并到一条摘要里，随每条更早消息滚动更新计数
        val title = m.title ?: "[${m.host ?: "?"}] ${m.agent ?: "shell"} ${m.stateKind.label}"
        val text = listOfNotNull(m.task, m.detail).joinToString("\n").ifBlank { m.raw ?: "" }
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing 漏掉的消息")
            .setContentText("最新：$title（等 $extra 条更早消息，点击打开查看）")
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
            .setContentTitle("AgentPing 运行中")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()

    // ---- 连接状态（UI 与前台通知共用）----

    private fun onConnState(s: String) {
        AppLog.log("STATE", s)
        connectionState.value = s
        scope.launch { updateServiceNotification() }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kick.value += 1 // 网络恢复，立即尝试重连
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

    /** 活跃网络画像（vpn/wifi/cell），连接失败时与 VPN 重连时间点对照用。 */
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
        const val CH_STATUS = "status-v3"
        const val CH_ALERT = "alert-v3"
        const val CH_SERVICE = "service-v3"
        const val NOTIF_SERVICE = 1
        const val NOTIF_SUMMARY = 3
        /** 与 FGS/摘要通知分开放，避免 hashCode 撞上 1/3 覆盖常驻通知。 */
        const val NOTIF_TAG_MSG = "msg"
        const val NOTIF_TAG_SUMMARY = "summary"

        /** 断线续传单次连接内逐条通知的上限，超出部分合并摘要。 */
        const val RESUME_NOTIFY_CAP = 10

        /** UI 直读的连接状态文本。 */
        val connectionState = MutableStateFlow("未连接")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PingService::class.java))
        }

        /** 配置变更后调用：取消旧连接循环，立即按新配置重连。 */
        fun reload(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_RELOAD)
            ctx.startForegroundService(i)
        }

        /** 下拉刷新：重连并回放最近缓存（since=12h），补齐装包前/断线期间的消息。 */
        fun refresh(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_REFRESH)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PingService::class.java))
            connectionState.value = "未连接"
        }

        const val ACTION_RELOAD = "io.dilfi5h.agentping.RELOAD"
        const val ACTION_REFRESH = "io.dilfi5h.agentping.REFRESH"
        const val SINCE_BACKFILL = "12h"

        private val sinceOverride = MutableStateFlow<String?>(null)
    }
}
