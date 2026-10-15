package com.example.service

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashSafeEnforcementStabilityTest {

    private lateinit var context: Context
    private lateinit var accessibilityService: FocusAccessibilityService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NewAppBlockStore.clear(context)
        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
    }

    @Test
    fun testTemporaryUnlockManagerSettingsExemption() {
        // Settings package exemption
        assertTrue("Android Settings is recognized", TemporaryUnlockManager.isSettingsPackage("com.android.settings"))
        assertTrue("MIUI Security Center is recognized", TemporaryUnlockManager.isSettingsPackage("com.miui.securitycenter"))
        assertFalse("Standard app is not settings", TemporaryUnlockManager.isSettingsPackage("com.example.normalapp"))
    }

    @Test
    fun testNewAppBlockStore_EnforcesInstantAutoBlockOnWindowStateChanged() {
        NewAppBlockStore.addBlockedApp(context, "com.test.blockedapp")

        @Suppress("DEPRECATION")
        val windowEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.test.blockedapp"
        }

        try {
            accessibilityService.onAccessibilityEvent(windowEvent)
            val firstHomeTime = accessibilityService.getLastHomeActionTime("com.test.blockedapp")
            assertTrue("performSafeHomeAction should be called immediately for instant auto-block", firstHomeTime > 0L)

            // Repeated event within cooldown: suppresses duplicate HOME action
            accessibilityService.onAccessibilityEvent(windowEvent)
            val secondHomeTime = accessibilityService.getLastHomeActionTime("com.test.blockedapp")
            assertEquals("performSafeHomeAction should NOT be re-called during initial cooldown", firstHomeTime, secondHomeTime)
        } finally {
            windowEvent.recycle()
        }
    }

    @Test
    fun testSafeHomeAction_EnforcesCooldown() {
        // First call should execute and record action time for package A
        val firstResult = accessibilityService.performSafeHomeAction("com.test.blockedapp", "First attempt")
        assertTrue("First safe home action should execute successfully", firstResult)
        val firstHomeTime = accessibilityService.getLastHomeActionTime("com.test.blockedapp")
        assertTrue("First safe home action should record execution timestamp", firstHomeTime > 0L)

        // Immediate second call for SAME package should be suppressed by 1000ms cooldown
        val secondResult = accessibilityService.performSafeHomeAction("com.test.blockedapp", "Immediate second attempt")
        assertFalse("Second safe home action for same package within 1000ms must be suppressed by cooldown", secondResult)
        assertEquals(
            "Timestamp for same package should remain unchanged because second action was suppressed",
            firstHomeTime,
            accessibilityService.getLastHomeActionTime("com.test.blockedapp")
        )

        // Immediate call for DIFFERENT package within the same 1000ms must NOT be suppressed by package A's cooldown
        val differentPackageResult = accessibilityService.performSafeHomeAction("com.test.otherapp", "Different package attempt")
        assertTrue("Safe home action for DIFFERENT package must NOT be suppressed by previous package cooldown", differentPackageResult)
        val otherPackageTime = accessibilityService.getLastHomeActionTime("com.test.otherapp")
        assertTrue("Different package should have its own recorded execution timestamp", otherPackageTime >= firstHomeTime)

        // Immediate second call for package B should now be suppressed by package B's own cooldown
        val secondOtherResult = accessibilityService.performSafeHomeAction("com.test.otherapp", "Immediate second attempt for other package")
        assertFalse("Second safe home action for package B must be suppressed by package B cooldown", secondOtherResult)
    }

    @Test
    fun testEventLoopExceptionIsolation() {
        // Build a faulty event that triggers an unusual edge case
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.nonexistent.test"
            className = "android.app.Activity"
        }

        try {
            // onAccessibilityEvent must not throw any unhandled exception out of the method
            accessibilityService.onAccessibilityEvent(event)
        } finally {
            event.recycle()
        }
    }
}
