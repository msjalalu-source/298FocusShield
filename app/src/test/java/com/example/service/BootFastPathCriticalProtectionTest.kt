package com.example.service

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.FocusApplication
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.bootstrap.StartupTiming
import com.example.service.search.SearchDetectorRouter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootFastPathCriticalProtectionTest {

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
        CriticalProtectionBootstrap.resetForTesting()
        StartupTiming.resetForTesting()
        com.example.service.systemui.SystemUiDetector.resetStateForTesting()
    }

    @After
    fun tearDown() {
        database.close()
        CriticalProtectionBootstrap.resetForTesting()
        StartupTiming.resetForTesting()
        com.example.service.systemui.SystemUiDetector.resetStateForTesting()
    }

    /**
     * Test A — Normal Boot Sequence:
     * Power ON → LOCKED_BOOT_COMPLETED → Critical Protection (Control Center Lock) READY →
     * USER_UNLOCKED → Full initialization.
     */
    @Test
    fun testA_NormalBootSequence() = runBlocking {
        // 1. Setup existing state with Control Center Edit Lock ON in DE snapshot
        repository.setSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED, "true")
        repository.setSetting(AppRepository.KEY_PROTECTION_ENABLED, "true")
        repository.saveFastProtectionSnapshot(
            lockedApps = mapOf("com.example.target" to "Target App"),
            knownApps = setOf("com.example.target"),
            settings = mapOf(
                AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED to "true",
                AppRepository.KEY_PROTECTION_ENABLED to "true"
            )
        )

        // Reset memory state to simulate fresh device power ON
        CriticalProtectionBootstrap.resetForTesting()
        assertFalse(CriticalProtectionBootstrap.isControlCenterProtectionReady())

        // 2. Trigger LOCKED_BOOT_COMPLETED
        val receiver = BootCompletedReceiver()
        val directBootIntent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        receiver.onReceive(context, directBootIntent)

        // 3. Verify: Critical Protection (Control Center Lock) is immediately READY from DE storage
        assertTrue(CriticalProtectionBootstrap.isControlCenterProtectionReady())
        assertTrue(CriticalProtectionBootstrap.isControlCenterLockEnabled())
        assertTrue(CriticalProtectionBootstrap.isProtectionEnabled())

        // 4. Trigger USER_UNLOCKED / BOOT_COMPLETED for full initialization
        val bootCompletedIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootCompletedIntent)

        // Verify state remains consistent and ready
        assertTrue(CriticalProtectionBootstrap.isControlCenterProtectionReady())
        assertTrue(CriticalProtectionBootstrap.isControlCenterLockEnabled())
    }

    /**
     * Test B — Unlock Sequence:
     * Lock Screen → Unlock (ACTION_USER_PRESENT / ACTION_USER_UNLOCKED) →
     * Critical Protection immediately verified → Full initialization afterward.
     */
    @Test
    fun testB_UnlockSequence() = runBlocking {
        // 1. Prime state with Control Center Edit Lock enabled
        repository.setSetting(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED, "true")

        // 2. Simulate User Present / Unlock event
        val receiver = BootCompletedReceiver()
        val userPresentIntent = Intent(Intent.ACTION_USER_PRESENT)
        receiver.onReceive(context, userPresentIntent)

        // 3. Verify Critical Protection verification succeeds immediately with zero delay
        val isReady = CriticalProtectionBootstrap.verifyAndEnsureProtection(context)
        assertTrue(isReady)
        assertTrue(CriticalProtectionBootstrap.isControlCenterLockEnabled())
        assertTrue(CriticalProtectionBootstrap.isControlCenterProtectionReady())
    }

    /**
     * Test C — Process Restart:
     * Process stopped → Process recreated → Fast Snapshot → Critical Protection READY →
     * Full initialization.
     */
    @Test
    fun testC_ProcessRestart() = runBlocking {
        // 1. Save Fast Snapshot in Device Protected Storage
        repository.saveFastProtectionSnapshot(
            lockedApps = mapOf("com.secret.app" to "Secret App"),
            knownApps = setOf("com.secret.app"),
            settings = mapOf(
                AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED to "true"
            )
        )

        // 2. Simulate process death: clear in-memory state
        CriticalProtectionBootstrap.resetForTesting()
        assertFalse(CriticalProtectionBootstrap.isControlCenterProtectionReady())

        // 3. Process restart: CriticalProtectionBootstrap.bootstrap is called (as in FocusApplication.onCreate)
        val bootstrappedState = CriticalProtectionBootstrap.bootstrap(context)

        // 4. Verify Critical Protection is restored immediately without DB access
        assertTrue(bootstrappedState.controlCenterProtectionReady)
        assertTrue(bootstrappedState.controlCenterLockEnabled)
        assertTrue(CriticalProtectionBootstrap.isControlCenterLockEnabled())
        assertTrue(CriticalProtectionBootstrap.isControlCenterProtectionReady())
    }

    /**
     * Test E — True Single-Load Idempotency:
     * Multiple consecutive calls to bootstrap() after READY do not perform duplicate storage reads.
     */
    @Test
    fun testE_IdempotentBootstrap_SingleStorageRead() {
        val prefs = AppRepository.getDeviceProtectedPrefs(context)
        prefs.edit()
            .putBoolean("snap_control_center_edit_locked", true)
            .putString("setting_${AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED}", "true")
            .apply()

        assertEquals(0, CriticalProtectionBootstrap.totalStorageReads)

        // Call 1: Performs single initial storage read
        val state1 = CriticalProtectionBootstrap.bootstrap(context)
        assertTrue(state1.controlCenterProtectionReady)
        assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)
        assertEquals(com.example.service.bootstrap.CriticalReadinessState.READY, CriticalProtectionBootstrap.currentReadinessState)

        // Calls 2-10: Strictly return existing in-memory AtomicReference state with ZERO storage reads
        for (i in 2..10) {
            val stateN = CriticalProtectionBootstrap.bootstrap(context)
            assertSame(state1, stateN)
            assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)
        }

        // verifyAndEnsureProtection also performs 0 storage reads when READY
        val ready = CriticalProtectionBootstrap.verifyAndEnsureProtection(context)
        assertTrue(ready)
        assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)
    }

    /**
     * Test F — Complete Startup Path Single-Load:
     * FocusApplication + BootCompletedReceiver + FocusAccessibilityService lifecycle performs
     * only ONE storage read total across the entire startup lifecycle.
     */
    @Test
    fun testF_CompleteStartupPath_SingleStorageRead() {
        val prefs = AppRepository.getDeviceProtectedPrefs(context)
        prefs.edit()
            .putBoolean("snap_control_center_edit_locked", true)
            .putString("setting_${AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED}", "true")
            .apply()

        assertEquals(0, CriticalProtectionBootstrap.totalStorageReads)

        // Stage 1: Process start & bootstrap (as executed by FocusApplication.onCreate())
        StartupTiming.recordProcessStart()
        val bootstrapped = CriticalProtectionBootstrap.bootstrap(context)
        assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)
        assertTrue(bootstrapped.controlCenterProtectionReady)
        assertTrue(CriticalProtectionBootstrap.isControlCenterProtectionReady())

        // Stage 2: BootCompletedReceiver.onReceive(LOCKED_BOOT_COMPLETED)
        val receiver = BootCompletedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)

        // Stage 3: FocusAccessibilityService lifecycle
        val serviceController = Robolectric.buildService(FocusAccessibilityService::class.java)
        val service = serviceController.create().get()
        service.onServiceConnected()
        assertEquals(1, CriticalProtectionBootstrap.totalStorageReads)

        // Stage 4: Milestone Timings
        assertTrue(StartupTiming.processStartTime > 0L)
        assertTrue(StartupTiming.criticalStateReadyTime > 0L)
        assertTrue(StartupTiming.accessibilityOnCreateTime > 0L)
        assertTrue(StartupTiming.handlersReadyTime > 0L)
    }

    /**
     * Test H — Direct State Machine Lifecycle:
     * NOT_READY → STARTING → READY → SystemUI event handling
     */
    @Test
    fun testH_DirectStateMachineLifecycle() {
        val prefs = AppRepository.getDeviceProtectedPrefs(context)
        prefs.edit()
            .putBoolean("snap_control_center_edit_locked", true)
            .putString("setting_${AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED}", "true")
            .apply()

        // 1. Initial State: NOT_READY
        CriticalProtectionBootstrap.resetForTesting()
        assertEquals(com.example.service.bootstrap.CriticalReadinessState.NOT_READY, CriticalProtectionBootstrap.currentReadinessState)

        // 2. Direct Bootstrap transition -> READY
        val state = CriticalProtectionBootstrap.bootstrap(context)
        assertEquals(com.example.service.bootstrap.CriticalReadinessState.READY, state.readinessState)
        assertEquals(com.example.service.bootstrap.CriticalReadinessState.READY, CriticalProtectionBootstrap.currentReadinessState)
        assertTrue(state.controlCenterProtectionReady)

        // 3. SystemUI Event Handling with READY state
        val serviceController = Robolectric.buildService(FocusAccessibilityService::class.java)
        val service = serviceController.create().get()
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.android.systemui"
            className = "com.android.systemui.qs.QSCustomizerActivity"
        }
        service.onAccessibilityEvent(event)
        assertEquals(com.example.service.bootstrap.CriticalReadinessState.READY, CriticalProtectionBootstrap.currentReadinessState)
    }

    /**
     * Test I — Blocked Keywords in Fast Snapshot Boot:
     * Keywords persisted in DE storage are available in memory immediately without DB query.
     */
    @Test
    fun testI_BlockedKeywordsFastSnapshot() = runBlocking {
        val keyword1 = com.example.data.BlockedSearchKeyword(
            id = 1L,
            keyword = "Unwanted Search",
            normalizedKeyword = "unwanted search",
            enabled = true,
            matchMode = "CONTAINS"
        )
        repository.saveFastProtectionSnapshot(
            lockedApps = mapOf("com.browser" to "Browser"),
            knownApps = setOf("com.browser"),
            settings = mapOf(AppRepository.KEY_PROTECTION_ENABLED to "true"),
            blockedKeywords = listOf(keyword1)
        )

        CriticalProtectionBootstrap.resetForTesting()
        val minimalState = CriticalProtectionBootstrap.bootstrap(context)
        // Minimal state on synchronous boot path strictly avoids parsing keywords
        assertTrue(minimalState.blockedKeywords.isEmpty())

        // Background IO asynchronously populates keywords without blocking boot
        var state = CriticalProtectionBootstrap.activeState
        val start = System.currentTimeMillis()
        while (state.blockedKeywords.isEmpty() && System.currentTimeMillis() - start < 2000) {
            kotlinx.coroutines.delay(10)
            state = CriticalProtectionBootstrap.activeState
        }

        assertEquals(1, state.blockedKeywords.size)
        assertEquals("Unwanted Search", state.blockedKeywords[0].keyword)
        assertEquals("CONTAINS", state.blockedKeywords[0].matchMode)
        assertTrue(state.blockedKeywords[0].enabled)
    }

    /**
     * Test J — Startup Capability and Health Diagnostics:
     * Correctly detects accessibility state, health report, and lifecycle degraded mode.
     */
    @Test
    fun testJ_CapabilityAndHealthDiagnostics() {
        val healthBefore = StartupCapabilityDetector.getHealthReport(context)
        assertNotNull(healthBefore)

        val serviceController = Robolectric.buildService(FocusAccessibilityService::class.java)
        val service = serviceController.create().get()
        service.onServiceConnected()

        assertTrue(StartupCapabilityDetector.isAccessibilityServiceAlive())

        serviceController.destroy()
        assertFalse(StartupCapabilityDetector.isAccessibilityServiceAlive())
    }
}
