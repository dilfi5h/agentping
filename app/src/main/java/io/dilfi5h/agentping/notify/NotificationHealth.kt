package io.dilfi5h.agentping.notify

/**
 * Pure-Kotlin layer of notification diagnostics: Android APIs are read into a snapshot,
 * then mapped to human-readable text.
 * System auto-silencing / OEM autostart cannot be queried reliably — never report them as green.
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
    title = "AgentPing local test",
    text = "This notification doesn't enter the timeline or move the cursor. If you can see it, the failure & waiting channel works.",
)

fun diagnoseNotificationHealth(snap: NotificationHealthSnap): NotificationHealthReport {
    val permissionDenied = snap.sdk >= 33 && snap.postNotificationsGranted == false
    val status = channelHealth(snap.status.exists, snap.status.importance)
    val alert = channelHealth(snap.alert.exists, snap.alert.importance)
    val service = channelHealth(snap.service.exists, snap.service.importance)

    val lines = buildList {
        if (snap.sdk >= 33) {
            add(
                if (permissionDenied) HealthLine("✗ Notification permission: not granted (the system silently drops)", HealthTone.ERROR)
                else HealthLine("✓ Notification permission: granted", HealthTone.OK),
            )
        }
        add(
            if (snap.appNotificationsEnabled) HealthLine("✓ App notifications: enabled", HealthTone.OK)
            else HealthLine("✗ App notifications: disabled", HealthTone.ERROR),
        )
        if (snap.notificationsPaused) {
            add(HealthLine("✗ Notifications temporarily paused", HealthTone.ERROR))
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
        permissionDenied -> "Notification permission not granted"
        !snap.appNotificationsEnabled -> "App notifications disabled"
        snap.notificationsPaused -> "Notifications temporarily paused"
        alert == ChannelHealth.BLOCKED -> "The \"Failure & waiting\" channel is off"
        alert == ChannelHealth.NOT_CREATED -> "Notification channels not yet created"
        alert != ChannelHealth.HIGH -> "\"Failure & waiting\" may have been downgraded by the system"
        else -> "Notification permission and channels look fine"
    }

    val hint = when {
        !canAlert -> "Grant the permission and turn on the \"Failure & waiting\" channel first, then tap test."
        tone == HealthTone.WARN && alert == ChannelHealth.NOT_CREATED ->
            "Tapping \"Send test notification\" creates the channels. The system's auto-silencing is invisible to the API — trust the test result."
        else -> "The system's auto-silencing is invisible to the API; tap test to confirm the banner appears."
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
        HealthLine("○ Failure & waiting: not created (service hasn't started yet)", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ Failure & waiting: off", HealthTone.ERROR)
    ChannelHealth.HIGH ->
        HealthLine("✓ Failure & waiting: high (failures/waiting show a banner)", HealthTone.OK)
    else ->
        HealthLine("! Failure & waiting: importance=${importance ?: -1} (may be downgraded by the system; banner/sound unreliable)", HealthTone.WARN)
}

private fun statusLine(health: ChannelHealth, importance: Int?): HealthLine = when (health) {
    ChannelHealth.NOT_CREATED ->
        HealthLine("○ Task status: not created (silent by default, not a fault)", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ Task status: off (completion notifications won't appear)", HealthTone.ERROR)
    else ->
        HealthLine("✓ Task status: ${importanceLabel(importance)} (silent by default)", HealthTone.OK)
}

private fun serviceLine(health: ChannelHealth, importance: Int?): HealthLine = when (health) {
    ChannelHealth.NOT_CREATED ->
        HealthLine("○ Service running: not created (min importance for the persistent notification, not a fault)", HealthTone.WARN)
    ChannelHealth.BLOCKED ->
        HealthLine("✗ Service running: off (doesn't affect task notifications)", HealthTone.ERROR)
    else ->
        HealthLine("✓ Service running: ${importanceLabel(importance)} (persistent minimum, can be off)", HealthTone.OK)
}

private fun importanceLabel(importance: Int?): String = when (importance) {
    IMPORTANCE_NONE -> "Off"
    IMPORTANCE_MIN -> "Min"
    IMPORTANCE_LOW -> "Low"
    IMPORTANCE_DEFAULT -> "Default"
    IMPORTANCE_HIGH -> "High"
    IMPORTANCE_MAX -> "Max"
    else -> "importance=${importance ?: -1}"
}
