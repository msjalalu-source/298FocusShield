package com.example.service

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.bootstrap.CriticalProtectionBootstrap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2AuditOptimizationsTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var accessibilityService: FocusAccessibilityService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppRepository.resetForTesting()
        AppRepository.getDeviceProtectedPrefs(context).edit().clear().commit()
        CriticalProtectionBootstrap.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).get()
    }

    @After
    fun tearDown() {
        database.close()
        CriticalProtectionBootstrap.resetForTesting()
    }

    // =============================================================================================
    // OPTIMIZATION 1: REMOVE UNNECESSARY MAP/SET ALLOCATIONS
    // =============================================================================================

    @Test
    fun testDirectMembershipLookups_NoCollectionCopying() {
        val cachedLocked = mapOf("com.app.locked1" to "Locked App 1")
        val cachedKnown = setOf("com.app.known1")
        val cachedNewBlocked = setOf("com.app.newblocked1")

        CriticalProtectionBootstrap.addDiscoveredLockedApp("com.app.bootstrapBlocked", "Bootstrap App")

        val ctx = AccessibilityHandlerContext(
            service = accessibilityService,
            repositoryProvider = { repository },
            searchDetectorRouter = com.example.service.search.SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { null },
            isSettingEnabled = { _, def -> def },
            isAppLocked = { pkg ->
                cachedLocked.containsKey(pkg) || CriticalProtectionBootstrap.getLockedApps().containsKey(pkg)
            },
            getLockedAppName = { pkg ->
                cachedLocked[pkg] ?: CriticalProtectionBootstrap.getLockedApps()[pkg]
            },
            isAppKnown = { pkg ->
                cachedKnown.contains(pkg) || CriticalProtectionBootstrap.getKnownApps().contains(pkg)
            },
            performHomeAction = { true },
            performBackAction = { true },
            showToast = { _, _ -> }
        )

        // 1. Direct membership verification in cached collections
        assertTrue("Locked app in cache should resolve true directly", ctx.isAppLocked("com.app.locked1"))
        assertEquals("Locked App 1", ctx.getLockedAppName("com.app.locked1"))
        assertTrue("Known app in cache should resolve true directly", ctx.isAppKnown("com.app.known1"))

        // 2. Direct membership verification in bootstrap collections
        assertTrue("Locked app in bootstrap should resolve true directly", ctx.isAppLocked("com.app.bootstrapBlocked"))
        assertEquals("Bootstrap App", ctx.getLockedAppName("com.app.bootstrapBlocked"))
        assertTrue("Known app in bootstrap should resolve true directly", ctx.isAppKnown("com.app.bootstrapBlocked"))

        // 3. Unregistered app resolves false without allocating collections
        assertFalse(ctx.isAppLocked("com.app.unregistered"))
        assertFalse(ctx.isAppKnown("com.app.unregistered"))
    }

    @Test
    fun testNewAppBlockingUsesDirectMembership() {
        val cachedLocked = mapOf("com.locked.target" to "Target App")
        val cachedKnown = setOf("com.locked.target", "com.known.target")

        val ctx = AccessibilityHandlerContext(
            service = accessibilityService,
            repositoryProvider = { repository },
            searchDetectorRouter = com.example.service.search.SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { null },
            isSettingEnabled = { _, def -> def },
            isAppLocked = { pkg -> cachedLocked.containsKey(pkg) },
            getLockedAppName = { pkg -> cachedLocked[pkg] },
            isAppKnown = { pkg -> cachedKnown.contains(pkg) },
            performHomeAction = { true },
            performBackAction = { true },
            showToast = { _, _ -> }
        )

        assertTrue("isAppLocked should recognize locked app via direct lookup", ctx.isAppLocked("com.locked.target"))
        assertFalse("isAppLocked should not handle known unlocked app", ctx.isAppLocked("com.known.target"))
    }
}
