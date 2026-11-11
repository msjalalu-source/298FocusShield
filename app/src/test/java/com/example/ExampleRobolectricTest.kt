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
        assertEquals("10", repository.getSetting(AppRepository.KEY_UNLOCK_DELAY_HOURS))
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

        // Direct unblock is strictly prohibited: calling unblockNewInstallApp initiates a 10h delayed unlock request
        repository.unblockNewInstallApp(testPkg)

        // App MUST remain blocked during the 10-hour delayed unlock period
        assertTrue("App must remain blocked while delayed unlock is pending", com.example.service.NewAppBlockStore.isBlocked(testPkg))
        assertTrue(repository.shouldBlockFast(testPkg))
        assertTrue(repository.shouldBlock(testPkg))

        // Verify pending unlock request exists in database
        val pendingList = database.appDao().getPendingUnlockRequests()
        val unlockReq = pendingList.find { it.targetType == "NEW_INSTALL_APP" && it.targetKey == testPkg }
        assertNotNull("Delayed unlock request must be created for NEW_INSTALL_APP", unlockReq)
        assertEquals(10, unlockReq!!.unlockDurationHours)

        // Simulate 10 hours passing
        database.appDao().updateUnlockRequest(unlockReq.copy(unlockAtTime = System.currentTimeMillis() - 1000))
        repository.checkAndProcessUnlockRequests()

        // After 10-hour delayed unlock completes, app is unblocked
        assertFalse(com.example.service.NewAppBlockStore.isBlocked(testPkg))
        assertFalse(repository.shouldBlockFast(testPkg))
        assertFalse(repository.shouldBlock(testPkg))
    }

    @Test
    fun testNewInstalledAppsProtection_canBeDisabledViaStore() = runBlocking {
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.service.NewAppBlockStore.setFeatureEnabled(appContext, false)
        assertFalse(com.example.service.NewAppBlockStore.isFeatureEnabled(appContext))

        com.example.service.NewAppBlockStore.setFeatureEnabled(appContext, true)
        assertTrue(com.example.service.NewAppBlockStore.isFeatureEnabled(appContext))
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

    @Test
    fun testCompleteRemovalOfRememberProtectionTarget15And16() = runBlocking {
        // 1. Verify Target IDs 15, 16, and 17 are completely absent from DEFAULT_REMEMBER_TARGETS
        val defaultIds = AppRepository.DEFAULT_REMEMBER_TARGETS.map { it.id }
        assertFalse("Target 15 ID must not be in default targets", defaultIds.contains("default_remember_target_15"))
        assertFalse("Target 16 ID must not be in default targets", defaultIds.contains("default_remember_target_16"))
        assertFalse("Target 17 ID (Digital Wellbeing) must not be in default targets", defaultIds.contains("default_remember_target_17"))

        // 2. Verify neither exact target identity is present in DEFAULT_REMEMBER_TARGETS
        assertFalse(
            "Target A ('Parental controls' in com.miui.home) must not be in default targets",
            AppRepository.DEFAULT_REMEMBER_TARGETS.any {
                it.packageName.equals("com.miui.home", ignoreCase = true) &&
                it.navigationPath.equals("Parental controls", ignoreCase = true)
            }
        )
        assertFalse(
            "Target B ('Digital Wellbeing & parental controls' in com.android.settings) must not be in default targets",
            AppRepository.DEFAULT_REMEMBER_TARGETS.any {
                it.packageName.equals("com.android.settings", ignoreCase = true) &&
                it.navigationPath.equals("Digital Wellbeing & parental controls", ignoreCase = true)
            }
        )

        // 3. Seed pending unlock requests in Room DB for both targets
        val dao = database.appDao()
        dao.insertUnlockRequest(
            com.example.data.UnlockRequest(
                targetType = "REMEMBER_TARGET_DELETE",
                targetKey = "default_remember_target_15",
                unlockAtTime = System.currentTimeMillis() + 36000000L,
                status = "PENDING"
            )
        )
        dao.insertUnlockRequest(
            com.example.data.UnlockRequest(
                targetType = "REMEMBER_TARGET_DELETE",
                targetKey = "default_remember_target_17",
                unlockAtTime = System.currentTimeMillis() + 36000000L,
                status = "PENDING"
            )
        )
        dao.insertUnlockRequest(
            com.example.data.UnlockRequest(
                targetType = "REMEMBER_TARGET_DELETE",
                targetKey = "custom_user_target",
                unlockAtTime = System.currentTimeMillis() + 36000000L,
                status = "PENDING"
            )
        )

        // 4. Seed legacy storage containing Target A (Parental controls) and Target B (Digital Wellbeing)
        // alongside a valid default target and a user custom target
        val legacyRaw = "com.miui.home|focus shield|Type View Long Click|default_remember_target_2;" +
                "com.miui.home|Parental controls|Type View Click|default_remember_target_15;" +
                "com.android.settings|Digital Wellbeing & parental controls|Type View Click|default_remember_target_17;" +
                "com.custom.app|My Custom Target|Blank|custom_user_target;" +
                "com.android.settings|DNS|TYPE_VIEW_CLICKED|default_remember_target_14"

        repository.setSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET, legacyRaw)
        AppRepository.getDeviceProtectedPrefs(context).edit()
            .putString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", legacyRaw)
            .apply()
        context.getSharedPreferences(context.packageName + "_preferences", android.content.Context.MODE_PRIVATE).edit()
            .putString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", legacyRaw)
            .apply()

        // 5. Run ensureDefaultRememberTargets migration and cleanup
        repository.ensureDefaultRememberTargets()

        // 6. Verify Room DB, Device-Protected prefs, and standard prefs are purged of both targets
        val rawCleanedDb = repository.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET) ?: ""
        assertFalse("Persisted DB data must not contain default_remember_target_15", rawCleanedDb.contains("default_remember_target_15"))
        assertFalse("Persisted DB data must not contain default_remember_target_17", rawCleanedDb.contains("default_remember_target_17"))
        assertFalse("Persisted DB data must not contain Parental controls", rawCleanedDb.contains("Parental controls"))
        assertFalse("Persisted DB data must not contain Digital Wellbeing & parental controls", rawCleanedDb.contains("Digital Wellbeing & parental controls"))

        val rawCleanedDePrefs = AppRepository.getDeviceProtectedPrefs(context).getString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", "") ?: ""
        assertFalse("DE Prefs must not contain default_remember_target_15", rawCleanedDePrefs.contains("default_remember_target_15"))
        assertFalse("DE Prefs must not contain default_remember_target_17", rawCleanedDePrefs.contains("default_remember_target_17"))
        assertFalse("DE Prefs must not contain Parental controls", rawCleanedDePrefs.contains("Parental controls"))
        assertFalse("DE Prefs must not contain Digital Wellbeing", rawCleanedDePrefs.contains("Digital Wellbeing & parental controls"))

        val rawCleanedStdPrefs = context.getSharedPreferences(context.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
            .getString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", "") ?: ""
        assertFalse("Std Prefs must not contain default_remember_target_15", rawCleanedStdPrefs.contains("default_remember_target_15"))
        assertFalse("Std Prefs must not contain default_remember_target_17", rawCleanedStdPrefs.contains("default_remember_target_17"))

        // 7. Verify decodeTargets rejects and filters both targets
        val decoded = com.example.service.safety.RememberProtectionEngine.decodeTargets(rawCleanedDb)
        assertFalse("Decoded list must not contain target 15", decoded.any { it.id == "default_remember_target_15" })
        assertFalse("Decoded list must not contain target 17", decoded.any { it.id == "default_remember_target_17" })
        assertFalse("Decoded list must not contain Parental controls", decoded.any {
            it.packageName.equals("com.miui.home", ignoreCase = true) && it.navigationPath.equals("Parental controls", ignoreCase = true)
        })
        assertFalse("Decoded list must not contain Digital Wellbeing", decoded.any {
            it.packageName.equals("com.android.settings", ignoreCase = true) && it.navigationPath.equals("Digital Wellbeing & parental controls", ignoreCase = true)
        })

        // 8. Verify pending unlock requests for targets 15 and 17 were deleted, while unrelated requests remain
        val pendingRequests = dao.getPendingUnlockRequests()
        assertFalse("Pending unlock request for target 15 must be deleted", pendingRequests.any { it.targetKey == "default_remember_target_15" })
        assertFalse("Pending unlock request for target 17 must be deleted", pendingRequests.any { it.targetKey == "default_remember_target_17" })
        assertTrue("Pending unlock request for custom target must be preserved", pendingRequests.any { it.targetKey == "custom_user_target" })

        // 9. Verify remaining default targets and custom targets are preserved
        assertTrue("Custom target must be preserved", decoded.any { it.id == "custom_user_target" })
        assertTrue("Target 2 must be preserved", decoded.any { it.id == "default_remember_target_2" })
        assertTrue("Target 14 must be preserved", decoded.any { it.id == "default_remember_target_14" })
        assertEquals("Target count must match defaults (14) + custom (1)", AppRepository.DEFAULT_REMEMBER_TARGETS.size + 1, decoded.size)
        assertEquals("Default targets count must be exactly 14", 14, AppRepository.DEFAULT_REMEMBER_TARGETS.size)

        // 10. Verify validation prevents recreation of both targets
        val forbiddenTargetA = com.example.service.safety.SavedProtectionTarget(
            navigationPath = "Parental controls",
            packageName = "com.miui.home",
            eventType = "Type View Click",
            id = "default_remember_target_15"
        )
        val forbiddenTargetB = com.example.service.safety.SavedProtectionTarget(
            navigationPath = "Digital Wellbeing & parental controls",
            packageName = "com.android.settings",
            eventType = "Type View Click",
            id = "default_remember_target_17"
        )
        assertNotNull("Validation must reject Target A", com.example.service.safety.RememberProtectionEngine.validateTarget(forbiddenTargetA))
        assertNotNull("Validation must reject Target B", com.example.service.safety.RememberProtectionEngine.validateTarget(forbiddenTargetB))
    }
}


