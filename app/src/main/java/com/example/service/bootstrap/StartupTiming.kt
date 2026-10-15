package com.example.service.bootstrap

import android.os.SystemClock
import android.util.Log

/**
 * High-precision startup timing instrumentation using SystemClock.elapsedRealtime().
 * Measures and logs critical boot, lifecycle, and enforcement milestones with zero performance overhead.
 */
object StartupTiming {
    private const val TAG = "StartupTiming"
    private const val DIAGNOSTIC_TAG = "FocusDiagnostic"

    @Volatile var processStartTime: Long = SystemClock.elapsedRealtime()
    @Volatile var bootEventReceivedTime: Long = 0L
    @Volatile var unlockEventReceivedTime: Long = 0L
    @Volatile var appBootstrapStartTime: Long = 0L
    @Volatile var appBootstrapEndTime: Long = 0L
    @Volatile var lockedBootCompletedTime: Long = 0L
    @Volatile var criticalStateReadyTime: Long = 0L
    @Volatile var systemUiProtectionReadyTime: Long = 0L
    @Volatile var protectionEngineReadyTime: Long = 0L
    @Volatile var accessibilityOnCreateTime: Long = 0L
    @Volatile var handlersReadyTime: Long = 0L
    @Volatile var accessibilityConnectedTime: Long = 0L
    @Volatile var firstSystemUiEventTime: Long = 0L
    @Volatile var firstProtectionActionTime: Long = 0L
    @Volatile var editCustomizerDetectedTime: Long = 0L
    @Volatile var blockActionStartedTime: Long = 0L
    @Volatile var blockActionCompletedTime: Long = 0L
    @Volatile var fullSyncCompleteTime: Long = 0L

    fun recordProcessStart() {
        if (processStartTime == 0L) {
            processStartTime = SystemClock.elapsedRealtime()
        }
        Log.i(DIAGNOSTIC_TAG, "⏱️ [PROCESS_START] elapsedRealtime=$processStartTime ms")
    }

    fun recordBootEventReceived(action: String) {
        val now = SystemClock.elapsedRealtime()
        if (bootEventReceivedTime == 0L) {
            bootEventReceivedTime = now
        }
        val diff = if (processStartTime > 0L) now - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [BOOT_EVENT_RECEIVED] action=$action, elapsedRealtime=$now ms (+${diff}ms from process start)")
    }

    fun recordUnlockEventReceived(action: String) {
        val now = SystemClock.elapsedRealtime()
        unlockEventReceivedTime = now
        val diffProcess = if (processStartTime > 0L) now - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [UNLOCK_EVENT_RECEIVED] action=$action, elapsedRealtime=$now ms (+${diffProcess}ms from process start)")
    }

    fun recordLockedBootCompleted() {
        lockedBootCompletedTime = SystemClock.elapsedRealtime()
        val diff = lockedBootCompletedTime - processStartTime
        Log.i(DIAGNOSTIC_TAG, "⏱️ [LOCKED_BOOT_COMPLETED] elapsedRealtime=$lockedBootCompletedTime ms (+${diff}ms from process start)")
    }

    fun recordCriticalBootstrapStart() {
        appBootstrapStartTime = SystemClock.elapsedRealtime()
        Log.i(DIAGNOSTIC_TAG, "⏱️ [CRITICAL_BOOTSTRAP_START] elapsedRealtime=$appBootstrapStartTime ms")
    }

    fun recordCriticalStateReady() {
        val now = SystemClock.elapsedRealtime()
        if (criticalStateReadyTime == 0L) {
            criticalStateReadyTime = now
            protectionEngineReadyTime = now
            val diffProcess = if (processStartTime > 0L) criticalStateReadyTime - processStartTime else 0L
            val diffBootstrap = if (appBootstrapStartTime > 0L) criticalStateReadyTime - appBootstrapStartTime else 0L
            Log.i(
                DIAGNOSTIC_TAG,
                "⏱️ [CRITICAL_STATE_AVAILABLE] elapsedRealtime=$criticalStateReadyTime ms (+${diffProcess}ms from process start, bootstrap duration: ${diffBootstrap}ms)"
            )
        }
    }

    fun recordProtectionEngineReady() {
        val now = SystemClock.elapsedRealtime()
        protectionEngineReadyTime = now
        val diffProcess = if (processStartTime > 0L) now - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [PROTECTION_ENGINE_READY] elapsedRealtime=$now ms (+${diffProcess}ms from process start)")
    }

    fun recordFirstProtectionAction(actionName: String, targetPackage: String) {
        val now = SystemClock.elapsedRealtime()
        if (firstProtectionActionTime == 0L) {
            firstProtectionActionTime = now
            val latencyFromUnlock = if (unlockEventReceivedTime > 0L) now - unlockEventReceivedTime else -1L
            val latencyFromProcess = if (processStartTime > 0L) now - processStartTime else -1L
            Log.i(
                DIAGNOSTIC_TAG,
                "⚡ [FIRST_PROTECTION_ACTION] action=$actionName, target=$targetPackage, elapsedRealtime=$now ms, latencyFromUnlock=${latencyFromUnlock}ms, latencyFromProcess=${latencyFromProcess}ms"
            )
        }
    }

    fun recordSystemUiProtectionReady() {
        val now = SystemClock.elapsedRealtime()
        if (systemUiProtectionReadyTime == 0L) {
            systemUiProtectionReadyTime = now
            val diff = if (processStartTime > 0L) systemUiProtectionReadyTime - processStartTime else 0L
            Log.i(DIAGNOSTIC_TAG, "⏱️ [SYSTEMUI_PROTECTION_READY] elapsedRealtime=$systemUiProtectionReadyTime ms (+${diff}ms from process start)")
        }
    }

    fun recordAccessibilityOnCreate() {
        accessibilityOnCreateTime = SystemClock.elapsedRealtime()
        val diff = if (processStartTime > 0L) accessibilityOnCreateTime - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [ACCESSIBILITY_ONCREATE] elapsedRealtime=$accessibilityOnCreateTime ms (+${diff}ms from process start)")
    }

    fun recordHandlersReady() {
        if (handlersReadyTime == 0L) {
            handlersReadyTime = SystemClock.elapsedRealtime()
            val diffService = if (accessibilityOnCreateTime > 0L) handlersReadyTime - accessibilityOnCreateTime else 0L
            val diffProcess = if (processStartTime > 0L) handlersReadyTime - processStartTime else 0L
            Log.i(
                DIAGNOSTIC_TAG,
                "⏱️ [SYSTEMUI_HANDLER_READY] elapsedRealtime=$handlersReadyTime ms (service init duration: ${diffService}ms, +${diffProcess}ms from process start)"
            )
        }
    }

    fun recordAccessibilityConnected() {
        accessibilityConnectedTime = SystemClock.elapsedRealtime()
        val diff = if (processStartTime > 0L) accessibilityConnectedTime - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [ACCESSIBILITY_CONNECTED] elapsedRealtime=$accessibilityConnectedTime ms (+${diff}ms from process start)")
    }

    fun recordFirstSystemUiEvent() {
        if (firstSystemUiEventTime == 0L) {
            firstSystemUiEventTime = SystemClock.elapsedRealtime()
            val diffHandler = if (handlersReadyTime > 0L) firstSystemUiEventTime - handlersReadyTime else 0L
            val diffProcess = if (processStartTime > 0L) firstSystemUiEventTime - processStartTime else 0L
            Log.i(DIAGNOSTIC_TAG, "⏱️ [FIRST_SYSTEMUI_EVENT] elapsedRealtime=$firstSystemUiEventTime ms (handler ready -> first event: ${diffHandler}ms, +${diffProcess}ms from process start)")
        }
    }

    fun recordEditCustomizerDetected() {
        editCustomizerDetectedTime = SystemClock.elapsedRealtime()
        val diffFirstEvent = if (firstSystemUiEventTime > 0L) editCustomizerDetectedTime - firstSystemUiEventTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [EDIT_CUSTOMIZER_DETECTED] elapsedRealtime=$editCustomizerDetectedTime ms (event -> detection: ${diffFirstEvent}ms)")
    }

    fun recordBlockActionStarted() {
        blockActionStartedTime = SystemClock.elapsedRealtime()
        val diffDetect = if (editCustomizerDetectedTime > 0L) blockActionStartedTime - editCustomizerDetectedTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [BLOCK_ACTION_STARTED] elapsedRealtime=$blockActionStartedTime ms (detection -> block start: ${diffDetect}ms)")
    }

    fun recordBlockActionCompleted() {
        blockActionCompletedTime = SystemClock.elapsedRealtime()
        val duration = if (blockActionStartedTime > 0L) blockActionCompletedTime - blockActionStartedTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [BLOCK_ACTION_COMPLETED] elapsedRealtime=$blockActionCompletedTime ms (block execution duration: ${duration}ms)")
    }

    fun recordFullSyncComplete() {
        fullSyncCompleteTime = SystemClock.elapsedRealtime()
        val diff = if (processStartTime > 0L) fullSyncCompleteTime - processStartTime else 0L
        Log.i(DIAGNOSTIC_TAG, "⏱️ [FULL_BACKGROUND_SYNC_COMPLETE] elapsedRealtime=$fullSyncCompleteTime ms (+${diff}ms from process start)")
    }

    fun resetForTesting() {
        processStartTime = SystemClock.elapsedRealtime()
        bootEventReceivedTime = 0L
        appBootstrapStartTime = 0L
        appBootstrapEndTime = 0L
        lockedBootCompletedTime = 0L
        criticalStateReadyTime = 0L
        systemUiProtectionReadyTime = 0L
        accessibilityOnCreateTime = 0L
        handlersReadyTime = 0L
        accessibilityConnectedTime = 0L
        firstSystemUiEventTime = 0L
        editCustomizerDetectedTime = 0L
        blockActionStartedTime = 0L
        blockActionCompletedTime = 0L
        fullSyncCompleteTime = 0L
    }
}
