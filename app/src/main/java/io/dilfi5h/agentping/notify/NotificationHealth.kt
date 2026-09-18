package io.dilfi5h.agentping.notify

/**
 * 通知诊断的纯 Kotlin 层：Android API 读成 snapshot 后再映射成文案。
 * 系统自动静默 / OEM 自启动无法可靠查询，禁止做成绿灯。
 */
data class ChannelSnap(
    val id: String,
    val exists: Boolean,
    val importance: Int?,
)

data class NotificationHealthSnap(
    val sdk: Int,
    val postNotificationsGranted: Boolean?,
    val appNotificationsEnabled: Boolean,
    val notificationsPaused: Boolean = false,
    val status: ChannelSnap,
    val alert: ChannelSnap,
    val service: ChannelSnap,
)

enum class ChannelHealth { NOT_CREATED, BLOCKED, MIN, DEFAULT, HIGH, OTHER }

enum class HealthTone { OK, WARN, ERROR }

data class HealthLine(
    val text: String,
    val tone: HealthTone,
)

data class NotificationHealthReport(
    val tone: HealthTone,
    val summary: String,
    val lines: List<HealthLine>,
    val canAlert: Boolean,
    val showPermissionRequest: Boolean,
    val hint: String,
)

data class LocalTestNotifySpec(
    val channelId: String,
    val tag: String,
    val id: Int,
    val title: String,
    val text: String,
)

const val IMPORTANCE_NONE = 0
const val IMPORTANCE_MIN = 1
const val IMPORTANCE_LOW = 2
const val IMPORTANCE_DEFAULT = 3
const val IMPORTANCE_HIGH = 4
const val IMPORTANCE_MAX = 5

const val CH_STATUS = "status-v3"
const val CH_ALERT = "alert-v3"
const val CH_SERVICE = "service-v3"

const val NOTIF_TAG_TEST = "test"
const val NOTIF_TEST = 2

fun channelHealth(exists: Boolean, importance: Int?): ChannelHealth = when {
    !exists || importance == null -> ChannelHealth.NOT_CREATED
    importance == IMPORTANCE_NONE -> ChannelHealth.BLOCKED
    importance == IMPORTANCE_MIN -> ChannelHealth.MIN
    importance == IMPORTANCE_DEFAULT -> ChannelHealth.DEFAULT
    importance >= IMPORTANCE_HIGH -> ChannelHealth.HIGH
    else -> ChannelHealth.OTHER
}

fun localTestNotifySpec(): LocalTestNotifySpec = LocalTestNotifySpec(
    channelId = CH_ALERT,
    tag = NOTIF_TAG_TEST,
    id = NOTIF_TEST,
    title = "AgentPing 本地测试",
    text = "这条通知不进时间线、不改游标。能看到它，说明失败与等待渠道可用。",
)

fun diagnoseNotificationHealth(snap: NotificationHealthSnap): NotificationHealthReport {
    val permissionDenied = snap.sdk >= 33 && snap.postNotificationsGranted == false
    val status = channelHealth(snap.status.exists, snap.status.importance)
    val alert = channelHealth(snap.alert.exists, snap.alert.importance)
    val service = channelHealth(snap.service.exists, snap.service.importance)

    val lines = buildList {
        if (snap.sdk >= 33) {
            add(
                if (permissionDenied) HealthLine("✗ 通知权限：未授予（系统会静默丢弃）", HealthTone.ERROR)
                else HealthLine("✓ 通知权限：已授予", HealthTone.OK),
            )
        }
        add(
            if (snap.appNotificationsEnabled) HealthLine("✓ 应用通知：已开启", HealthTone.OK)
            else HealthLine("✗ 应用通知：已关闭", HealthTone.ERROR),
        )
        if (snap.notificationsPaused) {
            add(HealthLine("✗ 通知已暂时暂停", HealthTone.ERROR))
        }
        add(alertLine(alert, snap.alert.importance))
        add(statusLine(status, snap.status.importance))
        add(serviceLine(service, snap.service.importance))
    }

    val canAlert = snap.appNotificationsEnabled &&
        !permissionDenied &&
        !snap.notificationsPaused &&
        alert != ChannelHealth.BLOCKED &&
        alert != ChannelHealth.NOT_CREATED

    val tone = when {
        permissionDenied || !snap.appNotificationsEnabled || snap.notificationsPaused ||
            alert == ChannelHealth.BLOCKED -> HealthTone.ERROR
        alert == ChannelHealth.NOT_CREATED ||
            (alert != ChannelHealth.HIGH && alert != ChannelHealth.BLOCKED) -> HealthTone.WARN
        else -> HealthTone.OK
    }

    val summary = when {
        permissionDenied -> "通知权限未授予"
        !snap.appNotificationsEnabled -> "应用通知已关闭"
        snap.notificationsPaused -> "通知已暂时暂停"
        alert == ChannelHealth.BLOCKED -> "「失败与等待」渠道已关闭"
        alert == ChannelHealth.NOT_CREATED -> "通知渠道尚未创建"
        alert != ChannelHealth.HIGH -> "「失败与等待」可能被系统降级"
        else -> "通知权限与渠道看起来正常"
    }

    val hint = when {
        !canAlert -> "先打开权限和「失败与等待」渠道，再点测试。"
        tone == HealthTone.WARN && alert == ChannelHealth.NOT_CREATED ->
            "点「发送测试通知」会创建渠道。系统自动静默 API 看不出来，请以测试结果为准。"
        else -> "系统自动静默 API 看不出来，请点测试确认横幅是否出现。"
    }

    return NotificationHealthReport(
        tone = tone,
        summary = summary,
        lines = lines,
        canAlert = canAlert,
        showPermissionRequest = permissionDenied,
        hint = hint,
    )
}

private fun alertLine(health: ChannelHealth, importance: Int?): HealthLine = when (health) {
    ChannelHealth.NOT_CREATED ->
        HealthLine("○ 失败与等待：未创建（服务尚未启动）", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ 失败与等待：已关闭", HealthTone.ERROR)
    ChannelHealth.HIGH ->
        HealthLine("✓ 失败与等待：高（失败/等待会横幅）", HealthTone.OK)
    else ->
        HealthLine("! 失败与等待：importance=${importance ?: -1}（可能被系统降级，横幅/声音不可信）", HealthTone.WARN)
}

private fun statusLine(health: ChannelHealth, importance: Int?): HealthLine = when (health) {
    ChannelHealth.NOT_CREATED ->
        HealthLine("○ 任务状态：未创建（默认无声，不是故障）", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ 任务状态：已关闭（完成通知不会出现）", HealthTone.ERROR)
    else ->
        HealthLine("✓ 任务状态：${importanceLabel(importance)}（默认无声）", HealthTone.OK)
}

private fun serviceLine(health: ChannelHealth, importance: Int?): HealthLine = when (health) {
    ChannelHealth.NOT_CREATED ->
        HealthLine("○ 服务运行：未创建（常驻通知最低重要性，不是故障）", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ 服务运行：已关闭（不影响任务通知）", HealthTone.ERROR)
    else ->
        HealthLine("✓ 服务运行：${importanceLabel(importance)}（常驻最低，可关）", HealthTone.OK)
}

private fun importanceLabel(importance: Int?): String = when (importance) {
    IMPORTANCE_NONE -> "已关闭"
    IMPORTANCE_MIN -> "最低"
    IMPORTANCE_LOW -> "低"
    IMPORTANCE_DEFAULT -> "默认"
    IMPORTANCE_HIGH -> "高"
    IMPORTANCE_MAX -> "最高"
    else -> "importance=${importance ?: -1}"
}
