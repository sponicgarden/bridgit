package net.finleg.bridget.power

import net.finleg.bridget.power.PowerPlan.Kind
import net.finleg.bridget.power.PowerPlan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPlanTest {
    private val dangerousSet = setOf(
        "android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CONTACTS",
    )
    private val sideloadManifest = dangerousSet + listOf(
        "android.permission.INTERNET", "android.permission.WRITE_SETTINGS", "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.ACCESS_NOTIFICATION_POLICY", "android.permission.health.READ_STEPS",
    )
    private val a11y = listOf("net.finleg.bridget/.capability.ScreenshotService")
    private val listener = listOf("net.finleg.bridget/.planned.NotificationReaderService")

    private fun full() = PowerPlan.steps(sideloadManifest, dangerousSet::contains, a11y, listener, sideloaded = true)

    @Test fun ordersRuntimeThenSpecialThenRestricted() {
        val ids = full().map { it.id }
        assertEquals(listOf("notifications", "camera", "mic", "photos", "location", "activity", "perm:READ_CONTACTS", "health",
            "battery", "usage", "write_settings", "dnd", "installs",
            PowerPlan.LISTENER + listener[0], PowerPlan.A11Y + a11y[0], PowerPlan.RESTRICTED), ids)
    }

    @Test fun normalPermissionsAreNotSteps() {
        assertFalse(full().any { "android.permission.INTERNET" in it.permissions })
    }

    @Test fun newDangerousPermissionJoinsByItself() {
        val s = full().single { it.id == "perm:READ_CONTACTS" }
        assertEquals(Kind.RUNTIME, s.kind)
        assertEquals(listOf("android.permission.READ_CONTACTS"), s.permissions)
    }

    @Test fun newAccessibilityServiceJoinsByItself() {
        val more = a11y + "net.finleg.bridget/.drive.DriveService"
        val steps = PowerPlan.steps(sideloadManifest, dangerousSet::contains, more, listener, sideloaded = true)
        assertEquals(2, steps.count { it.id.startsWith(PowerPlan.A11Y) })
        assertEquals(PowerPlan.RESTRICTED, steps.last().id)
    }

    /** GATE-03: a store build that declares less offers less, and needs no restricted-settings step. */
    @Test fun storeBuildOffersOnlyWhatItDeclares() {
        val play = setOf("android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        val ids = PowerPlan.steps(play, dangerousSet::contains, emptyList(), emptyList(), sideloaded = false).map { it.id }
        assertEquals(listOf("notifications", "camera", "mic", "battery"), ids)
    }

    @Test fun selectPhotosRidesWithImages() {
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), full().single { it.id == "photos" }.permissions)
    }

    @Test fun storeInstallHasNoRestrictedStep() {
        val steps = PowerPlan.steps(sideloadManifest, dangerousSet::contains, a11y, listener, sideloaded = false)
        assertFalse(steps.any { it.kind == Kind.RESTRICTED })
    }

    @Test fun nextSkipsGrantedAndDeclined() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.id == "notifications" }, declined = setOf("camera"))
        assertEquals(Status.ON, st["notifications"])
        assertEquals(Status.DECLINED, st["camera"])
        assertEquals("mic", PowerPlan.next(steps, st)?.id)
    }

    @Test fun restrictedHiddenUntilARestrictableStepIsDeclined() {
        val steps = full()
        assertNull(PowerPlan.status(steps, { false }, emptySet())[PowerPlan.RESTRICTED])
        val st = PowerPlan.status(steps, { false }, setOf(PowerPlan.A11Y + a11y[0]))
        assertEquals(Status.OFF, st[PowerPlan.RESTRICTED])
    }

    @Test fun restrictedOnWhenEveryRestrictableIsOn() {
        val steps = full()
        val st = PowerPlan.status(steps, PowerPlan::isRestrictable, emptySet())
        assertEquals(Status.ON, st[PowerPlan.RESTRICTED])
    }

    @Test fun doneWhenEverythingOnOrDeclined() {
        val steps = full()
        val all = steps.map { it.id }.toSet()
        val st = PowerPlan.status(steps, { it.kind == Kind.RUNTIME }, all)
        assertNull(PowerPlan.next(steps, st))
        assertTrue(st.values.none { it == Status.OFF })
    }

    @Test fun pendingRuntimeBatchesEverythingButHealth() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.id == "camera" }, setOf("mic"))
        val perms = PowerPlan.pendingRuntime(steps, st) { it == "android.permission.ACCESS_COARSE_LOCATION" }
        assertTrue("android.permission.POST_NOTIFICATIONS" in perms)
        assertFalse("android.permission.CAMERA" in perms) // on
        assertFalse("android.permission.RECORD_AUDIO" in perms) // declined
        assertFalse("android.permission.ACCESS_COARSE_LOCATION" in perms) // already granted
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in perms)
        assertFalse(perms.any { it.startsWith(PowerPlan.HEALTH_PREFIX) })
    }
}
