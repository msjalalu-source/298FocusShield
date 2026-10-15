package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppRepository
import com.example.service.bootstrap.CriticalProtectionBootstrap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification of the minimal "New Installed Apps" architecture.
 *
 * Verifies all 10 critical validation scenarios:
 * 1. PACKAGE_ADDED with feature ON -> package becomes blocked
 * 2. PACKAGE_ADDED with feature OFF -> package is not added
 * 3. Focus Guard's own package -> ignored
 * 4. Foreground blocked package -> existing block/Home action occurs
 * 5. Foreground unblocked package -> ignored
 * 6. Package removal -> blocked entry is cleaned
 * 7. Package update -> no duplicate new-app state
 * 8. State survives service/process restart
 * 9. Installation source does not matter
 * 10. Repeated Accessibility events do not create a Home-action loop
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewInstallBlockingPipelineTest {

    private lateinit var context: Context
    private lateinit var accessibilityService: FocusAccessibilityService
    private val receiver = PackageReceiver()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CriticalProtectionBootstrap.resetForTesting()
        NewAppBlockStore.clear(context)

        // Clear shared preferences
        AppRepository.getDeviceProtectedPrefs(context).edit().clear().commit()

        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
    }

    @After
    fun tearDown() {
        NewAppBlockStore.clear(context)
        CriticalProtectionBootstrap.resetForTesting()
    }

    // 1. PACKAGE_ADDED with feature ON -> package becomes blocked
    @Test
    fun test1_packageAdded_featureOn_packageBecomesBlocked() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        assertTrue("Feature should be enabled", NewAppBlockStore.isFeatureEnabled(context))

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:com.test.newapp")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertTrue("New app must be blocked when feature is ON", NewAppBlockStore.isBlocked("com.test.newapp"))
        assertTrue("Blocked app must be present in getBlockedApps", NewAppBlockStore.getBlockedApps().contains("com.test.newapp"))
    }

    // 2. Feature is permanently enabled -> setFeatureEnabled(false) has no effect and new apps are blocked
    @Test
    fun test2_packageAdded_featurePermanentlyEnabled_setFeatureEnabledIgnored() {
        NewAppBlockStore.setFeatureEnabled(context, false)
        assertTrue("Feature should remain permanently enabled", NewAppBlockStore.isFeatureEnabled(context))

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:com.test.alwaysblockedapp")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertTrue("New app must be blocked because feature is permanently enabled", NewAppBlockStore.isBlocked("com.test.alwaysblockedapp"))
        assertTrue("App must be in store", NewAppBlockStore.getBlockedApps().contains("com.test.alwaysblockedapp"))
    }

    // 3. Focus Guard's own package -> ignored
    @Test
    fun test3_packageAdded_ownPackage_ignored() {
        NewAppBlockStore.setFeatureEnabled(context, true)

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:${context.packageName}")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertFalse("Focus Guard's own package must NEVER be blocked", NewAppBlockStore.isBlocked(context.packageName))
    }

    // 4. Foreground blocked package -> existing block/Home action occurs
    @Test
    fun test4_foregroundBlockedPackage_homeActionTriggered() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.evil.distraction")

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.evil.distraction"
            className = "com.evil.distraction.MainActivity"
        }

        try {
            accessibilityService.onAccessibilityEvent(event)
            val lastHomeTime = accessibilityService.getLastHomeActionTime("com.evil.distraction")
            assertTrue("Home action must be recorded for blocked package", lastHomeTime > 0L)
        } finally {
            event.recycle()
        }
    }

    // 5. Foreground unblocked package -> ignored
    @Test
    fun test5_foregroundUnblockedPackage_ignored() {
        NewAppBlockStore.setFeatureEnabled(context, true)

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.innocent.app"
            className = "com.innocent.app.MainActivity"
        }

        try {
            accessibilityService.onAccessibilityEvent(event)
            val lastHomeTime = accessibilityService.getLastHomeActionTime("com.innocent.app")
            assertEquals("No Home action should be triggered for unblocked package", 0L, lastHomeTime)
        } finally {
            event.recycle()
        }
    }

    // 6. Package removal -> blocked entry is cleaned
    @Test
    fun test6_packageRemoved_blockedEntryCleaned() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.app.toremove")
        assertTrue("App should initially be blocked", NewAppBlockStore.isBlocked("com.app.toremove"))

        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.parse("package:com.app.toremove")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertFalse("Uninstalled app must be removed from blocked set", NewAppBlockStore.isBlocked("com.app.toremove"))
        assertFalse("Store must not contain uninstalled app", NewAppBlockStore.getBlockedApps().contains("com.app.toremove"))
    }

    // 7. Package update -> no duplicate new-app state
    @Test
    fun test7_packageUpdate_preservesStateWithoutDuplication() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.app.toupdate")

        val countBefore = NewAppBlockStore.getBlockedApps().size

        val updateIntent = Intent(Intent.ACTION_PACKAGE_REPLACED).apply {
            data = Uri.parse("package:com.app.toupdate")
            putExtra(Intent.EXTRA_REPLACING, true)
        }
        receiver.onReceive(context, updateIntent)

        assertTrue("Updated app should remain blocked if it was already blocked", NewAppBlockStore.isBlocked("com.app.toupdate"))
        assertEquals("Blocked apps set size must remain unchanged", countBefore, NewAppBlockStore.getBlockedApps().size)
    }

    // 8. State survives service/process restart
    @Test
    fun test8_statePersistence_survivesRestart() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.persistent.app")

        // Simulate new process by clearing in-memory state and re-initializing from persistence
        CriticalProtectionBootstrap.resetForTesting()
        NewAppBlockStore.setBlockedAppsForTesting(emptySet())
        assertFalse("In-memory cache cleared", NewAppBlockStore.isBlocked("com.persistent.app"))

        // Re-initialize from storage
        NewAppBlockStore.init(context)
        assertTrue("Blocked app state must survive restart via persistence", NewAppBlockStore.isBlocked("com.persistent.app"))
    }

    // 9. Installation source does not matter
    @Test
    fun test9_installationSourceDoesNotMatter() {
        NewAppBlockStore.setFeatureEnabled(context, true)

        // Broadcast received for app installed via any source (Play Store, sideload, ADB)
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:com.sideloaded.apk")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertTrue("Sideloaded package must be blocked identically", NewAppBlockStore.isBlocked("com.sideloaded.apk"))
    }

    // 10. Repeated Accessibility events do not create a Home-action loop
    @Test
    fun test10_repeatedAccessibilityEvents_noHomeActionLoop() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.spammy.app")

        val event1 = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.spammy.app"
        }
        val event2 = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.spammy.app"
        }

        try {
            accessibilityService.onAccessibilityEvent(event1)
            val firstHomeTime = accessibilityService.getLastHomeActionTime("com.spammy.app")
            assertTrue("First event triggers home action", firstHomeTime > 0L)

            // Immediate second event should be debounced
            accessibilityService.onAccessibilityEvent(event2)
            val secondHomeTime = accessibilityService.getLastHomeActionTime("com.spammy.app")
            assertEquals("Second immediate event must not re-trigger home action (debounced)", firstHomeTime, secondHomeTime)
        } finally {
            event1.recycle()
            event2.recycle()
        }
    }

    // 11. System package -> blocked হবে না
    @Test
    fun test11_systemPackage_notBlocked() {
        NewAppBlockStore.setFeatureEnabled(context, true)

        val pm = context.packageManager
        val shadowPm = org.robolectric.Shadows.shadowOf(pm)
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = "com.android.systemui"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.android.systemui"
                flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowPm.installPackage(packageInfo)

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:com.android.systemui")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertFalse("System packages must not be blocked", NewAppBlockStore.isBlocked("com.android.systemui"))
    }

    // 12. App startup and CriticalProtectionBootstrap initialization check
    @Test
    fun test12_criticalSnapshot_andAppInit() {
        AppRepository.getDeviceProtectedPrefs(context).edit()
            .putString("setting_${AppRepository.KEY_NEW_INSTALL_APP_BLOCKED}", "true")
            .commit()

        val state = AppRepository.loadMinimalCriticalSnapshot(context)
        assertEquals("true", state.settings[AppRepository.KEY_NEW_INSTALL_APP_BLOCKED])

        // Verify NewAppBlockStore.isFeatureEnabled reflects it immediately
        assertTrue(NewAppBlockStore.isFeatureEnabled(context))
    }

    // 13. Multiple apps installed sequentially -> all blocked independently
    @Test
    fun test13_multipleAppsInstalledSequentially_allBlocked() {
        NewAppBlockStore.setFeatureEnabled(context, true)

        val appA = "com.test.appa"
        val appB = "com.test.appb"
        val appC = "com.test.appc"

        for (pkg in listOf(appA, appB, appC)) {
            val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
                data = Uri.parse("package:$pkg")
                putExtra(Intent.EXTRA_REPLACING, false)
            }
            receiver.onReceive(context, intent)
        }

        assertTrue("App A must be blocked", NewAppBlockStore.isBlocked(appA))
        assertTrue("App B must be blocked", NewAppBlockStore.isBlocked(appB))
        assertTrue("App C must be blocked", NewAppBlockStore.isBlocked(appC))
        assertEquals(3, NewAppBlockStore.getBlockedApps().size)

        // Verify each app triggers HOME action on launch
        for (pkg in listOf(appA, appB, appC)) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = pkg
                className = "$pkg.MainActivity"
            }
            try {
                accessibilityService.onAccessibilityEvent(event)
                assertTrue("Home action must trigger for $pkg", accessibilityService.getLastHomeActionTime(pkg) > 0L)
            } finally {
                event.recycle()
            }
        }
    }

    // 14. Uninstall followed by reinstall -> properly unblocked then blocked again
    @Test
    fun test14_uninstallAndReinstall_blocksAgain() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val pkg = "com.test.lifecycleapp"

        // 1. Install
        val installIntent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$pkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, installIntent)
        assertTrue("App must be blocked after install", NewAppBlockStore.isBlocked(pkg))

        // 2. Uninstall
        val uninstallIntent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.parse("package:$pkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, uninstallIntent)
        assertFalse("App must be unblocked after uninstall", NewAppBlockStore.isBlocked(pkg))

        // 3. Reinstall with feature ON
        receiver.onReceive(context, installIntent)
        assertTrue("App must be blocked again after reinstall", NewAppBlockStore.isBlocked(pkg))
    }

    // 15. Dynamic registration works safely and idempotently
    @Test
    fun test15_dynamicRegistration_registersAndUnregistersCleanly() {
        PackageReceiver.register(context)
        assertTrue("PackageReceiver should be registered", PackageReceiver.isDynamicallyRegistered())

        // Calling register again must be idempotent
        PackageReceiver.register(context)
        assertTrue("PackageReceiver should remain registered", PackageReceiver.isDynamicallyRegistered())

        PackageReceiver.unregister(context)
        assertFalse("PackageReceiver should be unregistered", PackageReceiver.isDynamicallyRegistered())
    }

    // 16. Existing pre-installed app -> unchanged and not blocked
    @Test
    fun test16_existingApp_unchangedAndNotBlocked() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val existingPkg = "com.existing.calculator"
        assertFalse("Existing app must not be in NewAppBlockStore", NewAppBlockStore.isBlocked(existingPkg))
        assertFalse("Existing app must not be in getBlockedApps", NewAppBlockStore.getBlockedApps().contains(existingPkg))
    }

    // 17. Duplicate install events -> idempotent, no duplicate state
    @Test
    fun test17_duplicateInstallEvents_noDuplicateState() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val pkg = "com.duplicate.install.test"

        val firstAdd = NewAppBlockStore.addBlockedApp(context, pkg)
        assertTrue("First add must return true", firstAdd)

        val secondAdd = NewAppBlockStore.addBlockedApp(context, pkg)
        assertFalse("Second add must return false (idempotent, duplicate suppressed)", secondAdd)

        val thirdAdd = NewAppBlockStore.addBlockedApp(context, pkg)
        assertFalse("Third add must return false", thirdAdd)

        assertEquals("Set must contain exactly 1 entry", 1, NewAppBlockStore.getBlockedApps().filter { it == pkg }.size)
    }

    // 18. AccessibilityService restart -> state remains available
    @Test
    fun test18_accessibilityServiceRestart_stateAvailable() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.survives.service.restart")

        // Recreate service instance (simulating accessibility service crash/restart)
        val newService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
        newService.onServiceConnected()

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.survives.service.restart"
            className = "com.survives.service.restart.MainActivity"
        }
        try {
            newService.onAccessibilityEvent(event)
            assertTrue("Home action must trigger on re-created service instance", newService.getLastHomeActionTime("com.survives.service.restart") > 0L)
        } finally {
            event.recycle()
        }
    }

    // 19. Device reboot -> state restored via BootCompletedReceiver
    @Test
    fun test19_reboot_stateRestored() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, "com.reboot.testapp")

        // Clear in-memory state to simulate device power off
        NewAppBlockStore.setBlockedAppsForTesting(emptySet())
        assertFalse(NewAppBlockStore.isBlocked("com.reboot.testapp"))

        // Simulate BOOT_COMPLETED broadcast
        val bootReceiver = BootCompletedReceiver()
        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        bootReceiver.onReceive(context, bootIntent)

        assertTrue("Blocked app state must be restored after boot", NewAppBlockStore.isBlocked("com.reboot.testapp"))
    }

    // 20. Rapid open immediately after installation -> blocked without race condition
    @Test
    fun test20_rapidOpenImmediatelyAfterInstallation_blocked() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val pkg = "com.instant.open.app"

        // Broadcast received
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$pkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        // User immediately switches to app (within 0ms)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = "$pkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertTrue("Rapidly opened app must be blocked immediately", accessibilityService.getLastHomeActionTime(pkg) > 0L)
        } finally {
            event.recycle()
        }
    }

    // 21. Concurrent additions and removals -> thread safe
    @Test
    fun test21_concurrentAddRemove_threadSafe() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val threads = mutableListOf<Thread>()
        val errorCount = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 0 until 10) {
            val pkg = "com.concurrent.app.$i"
            threads.add(Thread {
                try {
                    NewAppBlockStore.addBlockedApp(context, pkg)
                    NewAppBlockStore.isBlocked(pkg)
                    NewAppBlockStore.removeApp(context, pkg)
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                }
            })
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("No concurrency errors allowed", 0, errorCount.get())
    }

    // 22. Duplicate receiver delivery -> idempotent execution
    @Test
    fun test22_duplicateReceiverDelivery_idempotent() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val pkg = "com.duplicate.broadcast.delivery"

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$pkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }

        // Deliver same intent twice (simulating multiple delivery or dual registration)
        receiver.onReceive(context, intent)
        receiver.onReceive(context, intent)

        assertTrue(NewAppBlockStore.isBlocked(pkg))
        assertEquals(1, NewAppBlockStore.getBlockedApps().filter { it == pkg }.size)
    }

    // 23. Raced launch: newly installed app opened before PACKAGE_ADDED broadcast arrives
    @Test
    fun test23_runtimeDiscovery_racedAppOpenedBeforeBroadcast_blockedImmediately() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val pkg = "com.raced.instant.open"

        // Known apps already loaded from pre-existing state
        accessibilityService.cachedKnownApps = setOf("com.preinstalled.calculator")

        // User opens the new app BEFORE the system delivers ACTION_PACKAGE_ADDED
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = "$pkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertTrue("Newly opened app must be blocked immediately via runtime discovery", accessibilityService.getLastHomeActionTime(pkg) > 0L)
            assertTrue("App must be marked in NewAppBlockStore immediately", NewAppBlockStore.isBlocked(pkg))
        } finally {
            event.recycle()
        }

        // System delivers ACTION_PACKAGE_ADDED later
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$pkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertTrue(NewAppBlockStore.isBlocked(pkg))
    }

    // 24. Muslim Browser Pro permanent exemption from New Install Protection
    @Test
    fun test24_muslimBrowserPro_permanentlyExemptFromNewInstallProtection() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val exemptPkg = "com.muslim.browser.pro"

        assertTrue("Package must be recognized as exempt", NewAppBlockStore.isExemptPackage(exemptPkg))

        // 1. ACTION_PACKAGE_ADDED broadcast
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$exemptPkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertFalse("Muslim Browser Pro must NOT be blocked", NewAppBlockStore.isBlocked(exemptPkg))
        assertFalse("Muslim Browser Pro must not be in blocked set", NewAppBlockStore.getBlockedApps().contains(exemptPkg))

        // 2. Direct addBlockedApp attempt must fail
        assertFalse("addBlockedApp must return false for exempt package", NewAppBlockStore.addBlockedApp(context, exemptPkg))
        assertFalse("Must remain unblocked after addBlockedApp attempt", NewAppBlockStore.isBlocked(exemptPkg))

        // 3. Direct addBlockedAppDirect attempt must not add it
        NewAppBlockStore.addBlockedAppDirect(exemptPkg)
        assertFalse("addBlockedAppDirect must not add exempt package", NewAppBlockStore.isBlocked(exemptPkg))

        // 4. Raced foreground launch before/during install must not trigger Home action or block
        accessibilityService.cachedKnownApps = setOf("com.preinstalled.calculator")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = exemptPkg
            className = "$exemptPkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertEquals("Muslim Browser Pro foreground launch must not trigger safe home action", 0L, accessibilityService.getLastHomeActionTime(exemptPkg))
            assertFalse("Muslim Browser Pro must not be marked blocked by accessibility service", NewAppBlockStore.isBlocked(exemptPkg))
        } finally {
            event.recycle()
        }

        // 5. Reinstall scenario: subsequent installation must also remain exempt
        val reinstallIntent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$exemptPkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, reinstallIntent)
        assertFalse("Muslim Browser Pro must remain unblocked on reinstall", NewAppBlockStore.isBlocked(exemptPkg))
    }

    // 25. Different new app follows the normal blocking path
    @Test
    fun test25_differentNewApp_followsNormalBlockingPath() {
        NewAppBlockStore.setFeatureEnabled(context, true)
        val regularPkg = "com.normal.otherapp"

        assertFalse("Regular package must not be recognized as exempt", NewAppBlockStore.isExemptPackage(regularPkg))

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:$regularPkg")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        receiver.onReceive(context, intent)

        assertTrue("Regular new app must follow normal blocking path and be blocked", NewAppBlockStore.isBlocked(regularPkg))
        assertTrue("Regular new app must be present in blocked apps set", NewAppBlockStore.getBlockedApps().contains(regularPkg))
    }
}
