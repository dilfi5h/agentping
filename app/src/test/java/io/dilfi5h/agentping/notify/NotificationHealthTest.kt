package io.dilfi5h.agentping.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHealthTest {
    @Test
    fun `uncreated channels are not reported as user-blocked`() {
        val report = diagnoseNotificationHealth(snap(alertExists = false))
        assertEquals(HealthTone.WARN, report.tone)
        assertFalse(report.canAlert)
        assertEquals("Notification channels not yet created", report.summary)
        assertTrue(report.lines.any { it.text.contains("not created") })
        assertFalse(report.lines.any { it.text.contains("Failure & waiting: off") })
    }

    @Test
    fun `healthy high-alert channel can alert`() {
        val report = diagnoseNotificationHealth(
            snap(
                alertExists = true,
                alertImportance = IMPORTANCE_HIGH,
                statusExists = true,
                statusImportance = IMPORTANCE_DEFAULT,
                serviceExists = true,
                serviceImportance = IMPORTANCE_MIN,
            )
        )
        assertTrue(report.canAlert)
        assertEquals(HealthTone.OK, report.tone)
        assertTrue(report.lines.any { it.text.contains("Service running") && it.tone == HealthTone.OK })
    }

    @Test
    fun `app notifications disabled is a blocker`() {
        val report = diagnoseNotificationHealth(
            snap(appNotificationsEnabled = false, alertExists = true, alertImportance = IMPORTANCE_HIGH)
        )
        assertFalse(report.canAlert)
        assertEquals(HealthTone.ERROR, report.tone)
        assertEquals("App notifications disabled", report.summary)
    }

    @Test
    fun `API 33 permission denied is a blocker and API 29 omits the permission line`() {
        val denied = diagnoseNotificationHealth(
            snap(sdk = 33, postNotificationsGranted = false, alertExists = true, alertImportance = IMPORTANCE_HIGH)
        )
        assertFalse(denied.canAlert)
        assertTrue(denied.showPermissionRequest)
        assertTrue(denied.lines.any { it.text.contains("Notification permission: not granted") })

        val api29 = diagnoseNotificationHealth(
            snap(sdk = 29, postNotificationsGranted = null, alertExists = true, alertImportance = IMPORTANCE_HIGH)
        )
        assertTrue(api29.canAlert)
        assertFalse(api29.showPermissionRequest)
        assertFalse(api29.lines.any { it.text.contains("Notification permission") })
    }

    @Test
    fun `blocked alert channel cannot alert`() {
        val report = diagnoseNotificationHealth(
            snap(alertExists = true, alertImportance = IMPORTANCE_NONE)
        )
        assertFalse(report.canAlert)
        assertEquals(HealthTone.ERROR, report.tone)
        assertEquals("The \"Failure & waiting\" channel is off", report.summary)
    }

    @Test
    fun `service min importance is not a fault`() {
        val health = channelHealth(exists = true, importance = IMPORTANCE_MIN)
        assertEquals(ChannelHealth.MIN, health)
        val report = diagnoseNotificationHealth(
            snap(
                alertExists = true,
                alertImportance = IMPORTANCE_HIGH,
                serviceExists = true,
                serviceImportance = IMPORTANCE_MIN,
            )
        )
        assertEquals(HealthTone.OK, report.tone)
        assertTrue(report.lines.any { it.text.contains("persistent minimum") && it.tone == HealthTone.OK })
    }

    @Test
    fun `local test notify uses alert channel and avoids service ids`() {
        val spec = localTestNotifySpec()
        assertEquals(CH_ALERT, spec.channelId)
        assertEquals("test", spec.tag)
        assertEquals(2, spec.id)
        assertTrue(spec.title.contains("local test"))
        assertTrue(spec.text.contains("doesn't enter the timeline"))
    }

    private fun snap(
        sdk: Int = 35,
        postNotificationsGranted: Boolean? = true,
        appNotificationsEnabled: Boolean = true,
        notificationsPaused: Boolean = false,
        alertExists: Boolean = false,
        alertImportance: Int? = null,
        statusExists: Boolean = false,
        statusImportance: Int? = null,
        serviceExists: Boolean = false,
        serviceImportance: Int? = null,
    ) = NotificationHealthSnap(
        sdk = sdk,
        postNotificationsGranted = postNotificationsGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        notificationsPaused = notificationsPaused,
        status = ChannelSnap(CH_STATUS, statusExists, statusImportance),
        alert = ChannelSnap(CH_ALERT, alertExists, alertImportance),
        service = ChannelSnap(CH_SERVICE, serviceExists, serviceImportance),
    )
}
