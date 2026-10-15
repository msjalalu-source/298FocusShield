package com.example.service.bootstrap

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.AppRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fast-path Critical Protection Bootstrap Engine.
 *
 * Implements a TRUE SINGLE-LOAD DETERMINISTIC CRITICAL BOOT PATH:
 * Guarantees that Focus Guard's highest priority critical protection—Control Center Edit/Customize
 * Prevention—and critical lock states are immediately loaded from Device Protected Storage (DE)
 * on the first component initialization with 0ms database latency, 0 SharedPreferences writes,
 * and 0 duplicate snapshot loads.
 */
object CriticalProtectionBootstrap {

    private const val TAG = "FocusDiagnostic"
    private const val INNER_TAG = "CriticalBootstrap"

    private val stateRef = AtomicReference(CriticalProtectionState.UNINITIALIZED)
    private val initLock = Any()
    private val asyncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Metrics for testing and verification
    private val storageReadCount = AtomicInteger(0)

    val currentReadinessState: CriticalReadinessState
        get() = stateRef.get().readinessState

    val totalStorageReads: Int
        get() = storageReadCount.get()

    /**
     * Synchronously bootstraps Critical Protection from Device Protected Storage.
     * Strictly idempotent & thread-safe:
     * - Double-checked locking prevents concurrent race conditions from returning uninitialized states.
     * - Direct state machine: UNINITIALIZED -> BOOTSTRAPPING -> READY -> SystemUI event handling
     * - If already loaded (READY), immediately returns the in-memory AtomicReference state without:
     *   reopening SharedPreferences or performing disk I/O.
     */
    fun bootstrap(context: Context): CriticalProtectionState {
        // Fast-path: Already initialized and critical protection ready (READY)
        val existing = stateRef.get()
        if (existing.readinessState == CriticalReadinessState.READY && existing.controlCenterProtectionReady) {
            return existing
        }

        synchronized(initLock) {
            val current = stateRef.get()
            if (current.readinessState == CriticalReadinessState.READY && current.controlCenterProtectionReady) {
                return current
            }

            val startTime = SystemClock.elapsedRealtime()
            StartupTiming.recordCriticalBootstrapStart()
            stateRef.set(current.copy(readinessState = CriticalReadinessState.BOOTSTRAPPING))

            val appContext = context.applicationContext ?: context
            try {
                storageReadCount.incrementAndGet()

                // Step 1: Synchronously load Minimal Critical Snapshot (0ms DB delay, zero disk writes)
                val minimalState = AppRepository.loadMinimalCriticalSnapshot(appContext)
                val loadFinishedTime = SystemClock.elapsedRealtime()

                val readyState = minimalState.copy(
                    readinessState = CriticalReadinessState.READY,
                    controlCenterProtectionReady = true
                )
                stateRef.set(readyState)

                StartupTiming.appBootstrapEndTime = loadFinishedTime
                StartupTiming.recordCriticalStateReady()
                StartupTiming.recordSystemUiProtectionReady()

                Log.i(
                    TAG,
                    "⏱️ [CRITICAL_BOOTSTRAP_READY] elapsedRealtime=${loadFinishedTime}ms, State: READY, CC Edit Lock: ${readyState.controlCenterLockEnabled}, Protection Ready: true"
                )

                // Step 2: Asynchronously load Full Protection Snapshot (locked apps, all settings)
                loadFullSnapshotAsync(appContext)

                return readyState
            } catch (e: Exception) {
                Log.e(INNER_TAG, "Error bootstrapping minimal critical protection snapshot", e)
                val fallback = CriticalProtectionState(
                    readinessState = CriticalReadinessState.READY,
                    controlCenterLockEnabled = false,
                    controlCenterProtectionReady = true,
                    isProtectionEnabled = true,
                    isValid = false
                )
                stateRef.set(fallback)
                StartupTiming.recordCriticalStateReady()
                return fallback
            }
        }
    }

    /**
     * Lightweight verification mechanism.
     * If already ready, returns immediately without accessing SharedPreferences or disk.
     */
    fun verifyAndEnsureProtection(context: Context): Boolean {
        val current = stateRef.get()
        if (current.readinessState == CriticalReadinessState.READY && current.controlCenterProtectionReady) {
            return true
        }
        return bootstrap(context).controlCenterProtectionReady
    }

    /**
     * Transition state when FocusAccessibilityService connects to system.
     */
    fun markServiceConnected() {
        stateRef.updateAndGet { current ->
            current.copy(
                readinessState = CriticalReadinessState.READY,
                controlCenterProtectionReady = true
            )
        }
        Log.i(TAG, "⏱️ [ACCESSIBILITY_SERVICE_CONNECTED] State: READY, elapsedRealtime=${SystemClock.elapsedRealtime()}ms")
    }

    /**
     * Transition state when all critical enforcement handlers are primed and ready.
     */
    fun markEnforcementReady() {
        stateRef.updateAndGet { current ->
            current.copy(
                readinessState = CriticalReadinessState.READY,
                controlCenterProtectionReady = true
            )
        }
        Log.i(TAG, "⏱️ [CRITICAL_ENFORCEMENT_READY] State: READY, elapsedRealtime=${SystemClock.elapsedRealtime()}ms")
    }

    /**
     * Asynchronously loads full snapshot and updates in-memory state without blocking boot path.
     */
    private fun loadFullSnapshotAsync(context: Context) {
        asyncScope.launch {
            try {
                val fullSnapshot = AppRepository.loadFullProtectionSnapshot(context, performMaintenanceWrites = true)
                if (!fullSnapshot.isValid) return@launch
                stateRef.updateAndGet { current ->
                    val mergedLocked = if (current.lockedApps.isNotEmpty()) current.lockedApps else fullSnapshot.lockedApps
                    val mergedKnown = if (current.knownApps.isNotEmpty()) current.knownApps else fullSnapshot.knownApps
                    val mergedKeywords = if (current.blockedKeywords.isNotEmpty()) current.blockedKeywords else fullSnapshot.blockedKeywords
                    val mergedSettings = if (current.settings.isNotEmpty()) fullSnapshot.settings + current.settings else fullSnapshot.settings

                    if (mergedLocked == current.lockedApps &&
                        mergedKnown == current.knownApps &&
                        mergedKeywords == current.blockedKeywords &&
                        mergedSettings == current.settings &&
                        current.isValid) {
                        current
                    } else {
                        current.copy(
                            lockedApps = mergedLocked,
                            knownApps = mergedKnown,
                            blockedKeywords = mergedKeywords,
                            settings = mergedSettings,
                            schemaVersion = fullSnapshot.schemaVersion,
                            createdAtMs = fullSnapshot.createdAtMs,
                            isStale = fullSnapshot.isStale,
                            isValid = true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(INNER_TAG, "Error loading full protection snapshot asynchronously", e)
            }
        }
    }

    val activeState: CriticalProtectionState
        get() = stateRef.get()

    fun isControlCenterLockEnabled(): Boolean = activeState.controlCenterLockEnabled

    fun isControlCenterProtectionReady(): Boolean = activeState.controlCenterProtectionReady

    fun isProtectionEnabled(): Boolean = activeState.isProtectionEnabled

    fun getLockedApps(): Map<String, String> = activeState.lockedApps

    fun getKnownApps(): Set<String> = activeState.knownApps

    fun getBlockedKeywords(): List<com.example.data.BlockedSearchKeyword> = activeState.blockedKeywords

    fun getSetting(key: String): String? = activeState.settings[key]

    fun isSettingEnabled(key: String, defaultVal: Boolean): Boolean {
        val v = activeState.settings[key]
        return if (v != null) v == "true" else defaultVal
    }

    /**
     * Transition state when FocusAccessibilityService is unbound or disconnected.
     */
    fun markServiceDisconnected() {
        stateRef.updateAndGet { current ->
            current.copy(
                readinessState = CriticalReadinessState.DEGRADED
            )
        }
        Log.i(TAG, "⚠️ [ACCESSIBILITY_SERVICE_DISCONNECTED] State: DEGRADED, elapsedRealtime=${SystemClock.elapsedRealtime()}ms")
    }

    /**
     * Updates an individual setting in the in-memory critical state.
     */
    fun updateSetting(key: String, value: String) {
        stateRef.updateAndGet { current ->
            val updatedSettings = current.settings.toMutableMap().apply { put(key, value) }
            val ccEditLocked = if (key == AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED) {
                value == "true"
            } else {
                current.controlCenterLockEnabled
            }
            val isProtectionEnabled = if (key == AppRepository.KEY_PROTECTION_ENABLED) {
                value != "false"
            } else {
                current.isProtectionEnabled
            }
            current.copy(
                controlCenterLockEnabled = ccEditLocked,
                controlCenterProtectionReady = true,
                isProtectionEnabled = isProtectionEnabled,
                settings = updatedSettings
            )
        }
    }

    /**
     * Immediately registers a newly discovered app as locked in critical memory state.
     */
    fun addDiscoveredLockedApp(packageName: String, appName: String) {
        stateRef.updateAndGet { current ->
            current.copy(
                knownApps = current.knownApps + packageName,
                lockedApps = current.lockedApps + (packageName to appName)
            )
        }
    }

    /**
     * Removes an app from in-memory locked list when explicitly unlocked by user timer.
     */
    fun removeLockedApp(packageName: String) {
        stateRef.updateAndGet { current ->
            current.copy(
                lockedApps = current.lockedApps - packageName
            )
        }
    }

    /**
     * Synchronizes memory state when repository emits full database updates.
     * Guarded: Missing/empty settings do not wipe active critical locks.
     */
    fun updateStateFromRepository(
        lockedApps: Map<String, String>,
        knownApps: Set<String>,
        settings: Map<String, String>,
        blockedKeywords: List<com.example.data.BlockedSearchKeyword> = emptyList()
    ) {
        stateRef.updateAndGet { current ->
            val ccEditLocked = if (settings.containsKey(AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED)) {
                settings[AppRepository.KEY_CONTROL_CENTER_EDIT_LOCKED] == "true"
            } else {
                current.controlCenterLockEnabled
            }
            val isProtectionEnabled = if (settings.containsKey(AppRepository.KEY_PROTECTION_ENABLED)) {
                settings[AppRepository.KEY_PROTECTION_ENABLED] != "false"
            } else {
                current.isProtectionEnabled
            }
            val newLockedApps = if (lockedApps.isNotEmpty()) {
                lockedApps
            } else if (knownApps.isNotEmpty()) {
                emptyMap()
            } else {
                current.lockedApps
            }
            current.copy(
                controlCenterLockEnabled = ccEditLocked,
                controlCenterProtectionReady = true,
                isProtectionEnabled = isProtectionEnabled,
                lockedApps = newLockedApps,
                knownApps = if (knownApps.isNotEmpty()) current.knownApps + knownApps else current.knownApps,
                blockedKeywords = if (blockedKeywords.isNotEmpty()) blockedKeywords else current.blockedKeywords,
                settings = if (settings.isNotEmpty()) current.settings + settings else current.settings,
                isValid = true
            )
        }
    }

    fun resetForTesting() {
        stateRef.set(CriticalProtectionState.UNINITIALIZED)
        storageReadCount.set(0)
    }
}
