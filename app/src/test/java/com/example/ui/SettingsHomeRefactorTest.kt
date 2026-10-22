package com.example.ui

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
class SettingsHomeRefactorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testBlockNewlyInstalledApps_turnOnIsImmediate() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        // Initially false or null
        repository.setSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, "false")
        assertEquals("false", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))

        // Turning ON is immediate
        repository.setSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, "true")
        assertEquals("true", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))
    }

    @Test
    fun testBlockNewlyInstalledApps_turnOffRequires12HourDelayedUnlock() = runBlocking {
        repository.initializeDefaultsIfEmpty()
        repository.setSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, "true")

        // Initiating unlock request for NEW_INSTALL_APP_BLOCK
        val request = repository.createUnlockRequest("NEW_INSTALL_APP_BLOCK", AppRepository.KEY_NEW_INSTALL_APP_BLOCKED)

        // Verify request created with fixed 10 hours
        assertEquals("NEW_INSTALL_APP_BLOCK", request.targetType)
        assertEquals(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, request.targetKey)
        assertEquals(10, request.unlockDurationHours)
        assertEquals("PENDING", request.status)

        // During the 10-hour period, protection setting MUST remain "true"
        assertEquals("true", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))

        // Processing before 10 hours elapsed: setting remains "true"
        repository.checkAndProcessUnlockRequests()
        assertEquals("true", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))

        // Fast forward 10 hours: set unlockAtTime to in the past
        database.appDao().updateUnlockRequest(request.copy(unlockAtTime = System.currentTimeMillis() - 1000))

        // Process expired unlock requests
        repository.checkAndProcessUnlockRequests()

        // After 10 hours expire, setting becomes "false"
        assertEquals("false", repository.getSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED))
    }

    @Test
    fun testAllProtectionSwitches_useSame12HourMechanism() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        val protectedFeatures = listOf(
            Pair("NEW_INSTALL_APP_BLOCK", AppRepository.KEY_NEW_INSTALL_APP_BLOCKED),
            Pair("CONTROL_CENTER_EDIT_LOCK", AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED),
            Pair("SEARCH_BLOCKING_YOUTUBE", AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED)
        )

        for ((targetType, key) in protectedFeatures) {
            // 1. Enable feature immediately
            repository.setSetting(key, "true")
            assertEquals("true", repository.getSetting(key))

            // 2. Request 10-hour unlock
            val req = repository.createUnlockRequest(targetType, key)
            assertEquals("Unlock duration must be 10 hours for $targetType", 10, req.unlockDurationHours)
            assertEquals("PENDING", req.status)

            // 3. Verify feature is still enabled during countdown
            assertEquals("true", repository.getSetting(key))

            // 4. Simulate timer completion
            database.appDao().updateUnlockRequest(req.copy(unlockAtTime = System.currentTimeMillis() - 500))
            repository.checkAndProcessUnlockRequests()

            // 5. Verify feature is disabled after timer completion
            assertEquals("false", repository.getSetting(key))
        }
    }

    @Test
    fun testDeleteProtection_uses12HourMechanism() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        val targetId = "target_settings_accessibility"
        val req = repository.createUnlockRequest("REMEMBER_TARGET_DELETE", targetId)

        assertEquals("REMEMBER_TARGET_DELETE", req.targetType)
        assertEquals(targetId, req.targetKey)
        assertEquals(10, req.unlockDurationHours)
        assertEquals("PENDING", req.status)
    }

    @Test
    fun testSearchBlockingYouTubeSwitch_defaultsToOffAndUsesDelayedUnlock() = runBlocking {
        repository.initializeDefaultsIfEmpty()

        // Fresh install defaults YouTube search blocking to false
        assertEquals("false", repository.getSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED))

        // Turning YouTube ON works immediately
        repository.setSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED, "true")
        assertEquals("true", repository.getSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED))

        // Disabling YouTube requires 10-hour delayed unlock
        val ytUnlock = repository.createUnlockRequest("SEARCH_BLOCKING_YOUTUBE", AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED)
        assertEquals(10, ytUnlock.unlockDurationHours)
        assertEquals("true", repository.getSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED))

        // Timer completes for YouTube
        database.appDao().updateUnlockRequest(ytUnlock.copy(unlockAtTime = System.currentTimeMillis() - 100))
        repository.checkAndProcessUnlockRequests()
        assertEquals("false", repository.getSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED))
    }
}
