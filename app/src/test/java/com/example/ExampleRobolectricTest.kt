package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        com.example.service.safety.RememberProtectionEngine.resetForTesting(context)
        AppRepository.getDeviceProtectedPrefs(context).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
    }

    @After
    fun tearDown() {
        com.example.service.safety.RememberProtectionEngine.resetForTesting(context)
        com.example.service.bootstrap.CriticalProtectionBootstrap.resetForTesting()
        AppRepository.resetForTesting()
        AppRepository.getDeviceProtectedPrefs(context).edit().clear().commit()
        database.close()
    }

    @Test
    fun testDefaultsInitialization() = runBlocking {
        repository.initializeDefaultsIfEmpty()
        assertEquals("12", repository.getSetting(AppRepository.KEY_UNLOCK_DELAY_HOURS))
        assertEquals("false", repository.getSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED))
    }

    @Test
    fun testControlCenterAndSecuritySettingsPersistence() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        // 1. Set Security & System Settings
        repository.setSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED, "true")
        repository.setSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, "true")
        repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "true")

        // 2. Verify all are persisted correctly
        assertEquals("true", repository.getSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED))
        assertEquals("true", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))
        assertEquals("true", repository.getSetting(AppRepository.KEY_UNINSTALL_PROTECTION))

        // 3. Verify turning off works
        repository.setSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED, "false")
        assertEquals("false", repository.getSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED))
    }

    @Test
    fun testNewInstalledAppsProtectionEnforcementAndUnblock() = runBlocking {
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.service.NewAppBlockStore.enableFeature(appContext)

        val testPkg = "com.example.newlyinstalled"
        com.example.service.NewAppBlockStore.addBlockedApp(appContext, testPkg)

        // Centralized shouldBlock must block due to NewAppBlockStore
        assertTrue(repository.shouldBlockFast(testPkg))
        assertTrue(repository.shouldBlock(testPkg))

        // Direct unblock is strictly prohibited: calling unblockNewInstallApp initiates a 12h delayed unlock request
        repository.unblockNewInstallApp(testPkg)

        // App MUST remain blocked during the 12-hour delayed unlock period
        assertTrue("App must remain blocked while delayed unlock is pending", com.example.service.NewAppBlockStore.isBlocked(testPkg))
        assertTrue(repository.shouldBlockFast(testPkg))
        assertTrue(repository.shouldBlock(testPkg))

        // Verify pending unlock request exists in database
        val pendingList = database.appDao().getPendingUnlockRequests()
        val unlockReq = pendingList.find { it.targetType == "NEW_INSTALL_APP" && it.targetKey == testPkg }
        assertNotNull("Delayed unlock request must be created for NEW_INSTALL_APP", unlockReq)
        assertEquals(12, unlockReq!!.unlockDurationHours)

        // Simulate 12 hours passing
        database.appDao().updateUnlockRequest(unlockReq.copy(unlockAtTime = System.currentTimeMillis() - 1000))
        repository.checkAndProcessUnlockRequests()

        // After 12-hour delayed unlock completes, app is unblocked
        assertFalse(com.example.service.NewAppBlockStore.isBlocked(testPkg))
        assertFalse(repository.shouldBlockFast(testPkg))
        assertFalse(repository.shouldBlock(testPkg))
    }

    @Test
    fun testNewInstalledAppsProtection_permanentlyEnabled() = runBlocking {
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.service.NewAppBlockStore.setFeatureEnabled(appContext, false)
        assertTrue(com.example.service.NewAppBlockStore.isFeatureEnabled(appContext))

        val testPkg = "com.example.regularnewapp"
        assertFalse(com.example.service.NewAppBlockStore.isBlocked(testPkg))
        assertFalse(repository.shouldBlock(testPkg))

        com.example.service.NewAppBlockStore.addBlockedApp(appContext, testPkg)
        assertTrue(com.example.service.NewAppBlockStore.isBlocked(testPkg))
    }

    @Test
    fun testNewlyInstalledAppRemainsBlockedAcrossMultipleLaunches() = runBlocking {
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.service.NewAppBlockStore.enableFeature(appContext)

        val newAppPkg = "com.playstore.coolapp"
        com.example.service.NewAppBlockStore.addBlockedApp(appContext, newAppPkg)

        // 1. Initial verification
        assertTrue(com.example.service.NewAppBlockStore.isBlocked(newAppPkg))
        assertTrue(repository.shouldBlockFast(newAppPkg))
        assertTrue(repository.shouldBlock(newAppPkg))

        // 2. Subsequent check (verify persistence and in-memory consistency)
        assertTrue(com.example.service.NewAppBlockStore.isBlocked(newAppPkg))
        assertTrue(repository.shouldBlockFast(newAppPkg))
        assertTrue(repository.shouldBlock(newAppPkg))
    }

    @Test
    fun testRememberProtectionDefaultConfigurationsInitializedOnFreshInstall() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        val raw = repository.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET)
        assertNotNull(raw)
        val targets = com.example.service.safety.RememberProtectionEngine.decodeTargets(raw)

        val expectedTargets = AppRepository.DEFAULT_REMEMBER_TARGETS
        assertEquals(expectedTargets.size, targets.size)

        val paths = targets.map { it.navigationPath }
        val expectedPaths = expectedTargets.map { it.navigationPath }
        assertEquals(expectedPaths, paths)

        for (target in targets) {
            assertTrue(target.packageName.isNotBlank())
            assertTrue(target.eventType.isNotBlank())
            assertTrue(target.id.isNotBlank())
        }
    }

    @Test
    fun testRememberProtectionDefaultConfigurationsIdempotentAndNoDuplicates() = runBlocking {
        repository.initializeDefaultsIfEmpty()
        val rawFirst = repository.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET)
        val targetsFirst = com.example.service.safety.RememberProtectionEngine.decodeTargets(rawFirst)
        assertEquals(AppRepository.DEFAULT_REMEMBER_TARGETS.size, targetsFirst.size)

        // Call again - must remain idempotent without duplicates
        repository.ensureDefaultRememberTargets()
        val rawSecond = repository.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET)
        val targetsSecond = com.example.service.safety.RememberProtectionEngine.decodeTargets(rawSecond)
        assertEquals(AppRepository.DEFAULT_REMEMBER_TARGETS.size, targetsSecond.size)
    }

    @Test
    fun testRememberProtectionPreservesExistingUserAddedConfigurations() = runBlocking {
        val customTarget = com.example.service.safety.SavedProtectionTarget(
            navigationPath = "Custom Path",
            packageName = "com.custom.app",
            eventType = "Blank",
            id = "user_custom_1"
        )
        val initialList = listOf(customTarget)
        repository.setSetting(
            AppRepository.KEY_SAVED_PROTECTION_TARGET,
            com.example.service.safety.RememberProtectionEngine.encodeTargets(initialList)
        )

        repository.ensureDefaultRememberTargets()

        val raw = repository.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET)
        val targets = com.example.service.safety.RememberProtectionEngine.decodeTargets(raw)

        // Must contain original custom target + default targets
        assertEquals(AppRepository.DEFAULT_REMEMBER_TARGETS.size + 1, targets.size)
        assertTrue(targets.any { it.id == "user_custom_1" && it.navigationPath == "Custom Path" })
        assertTrue(targets.any { it.navigationPath == "Set up portable hotspot" })
        assertTrue(targets.any { it.navigationPath == "style" })
        assertTrue(targets.any { it.navigationPath == "apps" })
    }
}
