package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.os.UserManagerCompat
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.bootstrap.StartupTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Direct-Boot Aware Lifecycle BroadcastReceiver.
 *
 * Implements the single-load fast-path boot sequence:
 * 1. LOCKED_BOOT_COMPLETED trigger
 * 2. Device Protected Storage fast critical protection check (idempotent 0ms DB latency)
 * 3. BOOT_COMPLETED / USER_UNLOCKED trigger
 * 4. Deferred background Room DB & full repository synchronization
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val DIAGNOSTIC_TAG = "FocusDiagnostic"
        private val hasPerformedUnlockedBackgroundSync = java.util.concurrent.atomic.AtomicBoolean(false)
        private var lastBackgroundSyncTimeMs = 0L

        fun resetForTesting() {
            hasPerformedUnlockedBackgroundSync.set(false)
            lastBackgroundSyncTimeMs = 0L
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val now = SystemClock.elapsedRealtime()

        StartupTiming.recordBootEventReceived(action)

        val isDirectBootEvent = action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val isBootCompleted = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isUserUnlocked = action == Intent.ACTION_USER_UNLOCKED
        val isUserPresent = action == Intent.ACTION_USER_PRESENT
        val isScreenOn = action == Intent.ACTION_SCREEN_ON
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (isDirectBootEvent) {
            StartupTiming.recordLockedBootCompleted()
        }

        if (!isDirectBootEvent && !isBootCompleted && !isUserUnlocked && !isUserPresent && !isScreenOn && !isPackageReplaced) {
            return
        }

        val appContext = context.applicationContext ?: context

        // STEP 1: FAST PATH - Verify existing in-memory Critical Protection (0ms SharedPreferences reload if ready)
        CriticalProtectionBootstrap.verifyAndEnsureProtection(appContext)
        NewAppBlockStore.init(appContext)

        val isUnlocked = UserManagerCompat.isUserUnlocked(appContext)
        if (!isUnlocked) {
            return
        }

        // STEP 2: Debounced/Gated background sync - Prevent redundant Room DB queries on duplicate unlock/screen events
        val shouldSync = !hasPerformedUnlockedBackgroundSync.getAndSet(true) || (now - lastBackgroundSyncTimeMs > 60_000L)
        if (!shouldSync) {
            return
        }
        lastBackgroundSyncTimeMs = now

        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }

        val repository = AppRepository.getInstance(appContext)
        repository.repositoryScope.launch(Dispatchers.IO) {
            try {
                repository.refreshMemoryCaches()
                repository.checkAndProcessUnlockRequests()
                StartupTiming.recordFullSyncComplete()
            } catch (e: Exception) {
                // ignored intentionally
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {}
            }
        }
    }
}
