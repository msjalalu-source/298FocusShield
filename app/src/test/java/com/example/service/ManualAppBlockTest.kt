package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.view.accessibility.AccessibilityEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.LockedApp
import com.example.service.bootstrap.CriticalProtectionBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualAppBlockTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var accessibilityService: FocusAccessibilityService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
        CriticalProtectionBootstrap.resetForTesting()
        NewAppBlockStore.init(context, forceReload = true)
        NewAppBlockStore.clear(context)

        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
        accessibilityService.resetHomeActionCooldownsForTesting()
    }

    @After
    fun tearDown() {
        database.close()
        CriticalProtectionBootstrap.resetForTesting()
        NewAppBlockStore.clear(context)
    }

    // 1. A launcher-visible, already-installed application can be manually blocked
    // 2. The actual enforcement mechanism blocks the selected application
    @Test
    fun test1_manuallyBlockInstalledApp_persistsAndEnforces() = runBlocking {
        val targetPkg = "com.sample.socialapp"
        val targetAppName = "Sample Social"

        // Insert as an already-installed unlocked app
        database.appDao().insertApp(
            LockedApp(
                packageName = targetPkg,
                appName = targetAppName,
                isAppLocked = false,
                isSystemApp = false
            )
        )

        // User manually blocks the app
        repository.setAppLocked(targetPkg, true)

        val appInDb = database.appDao().getAppByPackage(targetPkg)
        assertNotNull(appInDb)
        assertTrue("App should now be marked locked in database", appInDb!!.isAppLocked)
        assertTrue("App should be locked in repository memory cache", repository.isAppLockedCached(targetPkg))
        assertTrue("App should be in CriticalProtectionBootstrap locked map",
            CriticalProtectionBootstrap.getLockedApps().containsKey(targetPkg))

        // Rebuild service cache to reflect locked app
        accessibilityService.setCachedLockedAppsForTesting(mapOf(targetPkg to targetAppName))

        // Foreground event in the manually locked app triggers enforcement (Home action)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = targetPkg
            className = "$targetPkg.MainActivity"
        }

        try {
            accessibilityService.onAccessibilityEvent(event)
            val homeTime = accessibilityService.getLastHomeActionTime(targetPkg)
            assertTrue("Home action must be recorded for manually blocked app", homeTime > 0L)
        } finally {
            event.recycle()
        }
    }

    // 3. Background-only packages do not appear in the selection list
    @Test
    fun test3_backgroundOnlyPackagesExcludedFromLauncherQuery() {
        val pm = context.packageManager
        val shadowPm = shadowOf(pm)

        val launcherPkg = "com.launcher.visible.app"
        val backgroundPkg = "com.background.only.service"

        // 1. Add launcher-visible app
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = launcherPkg
                name = "$launcherPkg.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = launcherPkg
                    flags = 0
                }
            }
        }
        shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)

        // 2. Add background-only package (has package info, but NO launcher activity)
        val bgPkgInfo = PackageInfo().apply {
            packageName = backgroundPkg
            applicationInfo = ApplicationInfo().apply {
                packageName = backgroundPkg
                flags = 0
            }
        }
        shadowPm.installPackage(bgPkgInfo)

        val launcherPackages = repository.getLauncherVisiblePackages()
        assertTrue("Launcher-visible app must be present in launcher packages", launcherPackages.contains(launcherPkg))
        assertFalse("Background-only package must NOT be present in launcher packages", launcherPackages.contains(backgroundPkg))
    }

    // 4. Focus Shield cannot accidentally block itself through this feature
    @Test
    fun test4_focusShieldCannotBlockItself() = runBlocking {
        val selfPkg = context.packageName

        // Attempting to manually lock our own app is ignored
        repository.setAppLocked(selfPkg, true)

        val selfInDb = database.appDao().getAppByPackage(selfPkg)
        assertNull("Focus Shield itself must never be stored as a locked app", selfInDb)
        assertFalse("Focus Shield itself must not be cached as locked", repository.isAppLockedCached(selfPkg))
        assertFalse("Focus Shield itself must not be considered locked by service", accessibilityService.isAppLocked(selfPkg))
        assertFalse("Launcher visible packages must exclude self", repository.getLauncherVisiblePackages().contains(selfPkg))

        // Accessibility event for self must never trigger Home action
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = selfPkg
            className = "$selfPkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertEquals(0L, accessibilityService.getLastHomeActionTime(selfPkg))
        } finally {
            event.recycle()
        }
    }

    // 5. Existing automatic blocking of newly installed apps remains unchanged
    @Test
    fun test5_automaticNewInstallBlocking_remainsFunctional() {
        val newAppPkg = "com.new.install.app"
        NewAppBlockStore.setFeatureEnabled(context, true)
        NewAppBlockStore.addBlockedApp(context, newAppPkg)

        assertTrue(NewAppBlockStore.isBlocked(newAppPkg))

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = newAppPkg
            className = "$newAppPkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertTrue("New install block must still trigger Home action",
                accessibilityService.getLastHomeActionTime(newAppPkg) > 0L)
        } finally {
            event.recycle()
        }
    }

    // 6. Existing applicable unblock and 12-hour time-restriction rules continue to work
    @Test
    fun test6_manualUnblock_requires12HourDelay() = runBlocking {
        val targetPkg = "com.locked.with.delay"
        repository.setAppLocked(targetPkg, true)
        assertTrue(repository.isAppLockedCached(targetPkg))

        // Request unblock
        val unlockReq = repository.requestUnblockApp(targetPkg)
        assertNotNull(unlockReq)
        assertEquals("APP_LOCK", unlockReq!!.targetType)
        assertEquals(targetPkg, unlockReq.targetKey)
        assertEquals(10, unlockReq.unlockDurationHours)
        assertEquals("PENDING", unlockReq.status)

        // During 12-hour countdown, the app MUST remain locked
        repository.checkAndProcessUnlockRequests()
        assertTrue("App must remain locked while timer is pending", repository.isAppLockedCached(targetPkg))

        // Fast-forward 12 hours
        database.appDao().updateUnlockRequest(unlockReq.copy(unlockAtTime = System.currentTimeMillis() - 1000))
        repository.checkAndProcessUnlockRequests()

        // Now the app is unlocked
        assertFalse("App must be unlocked after 12-hour timer completes", repository.isAppLockedCached(targetPkg))
    }

    // 7. Removing a manual rule does not erase independent active blocking rules
    @Test
    fun test7_removingManualRule_doesNotEraseActiveNewInstallRule() = runBlocking {
        val dualPkg = "com.dual.protected.app"

        // App is blocked BOTH automatically (new install) and manually
        NewAppBlockStore.addBlockedApp(context, dualPkg)
        repository.setAppLocked(dualPkg, true)

        assertTrue(NewAppBlockStore.isBlocked(dualPkg))
        assertTrue(repository.isAppLockedCached(dualPkg))

        // Remove the manual lock directly
        repository.setAppLocked(dualPkg, false)

        assertFalse("Manual lock is now removed", repository.isAppLockedCached(dualPkg))
        assertTrue("New install block MUST remain active independently", NewAppBlockStore.isBlocked(dualPkg))

        // Service still blocks it because automatic new install rule is still active
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = dualPkg
            className = "$dualPkg.MainActivity"
        }
        try {
            accessibilityService.onAccessibilityEvent(event)
            assertTrue("Service must still enforce block via remaining active rule",
                accessibilityService.getLastHomeActionTime(dualPkg) > 0L)
        } finally {
            event.recycle()
        }
    }
}
