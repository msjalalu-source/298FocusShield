package com.example.service.bootstrap

import android.os.SystemClock

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
    }

    fun recordBootEventReceived(action: String) {
        val now = SystemClock.elapsedRealtime()
        if (bootEventReceivedTime == 0L) {
            bootEventReceivedTime = now
        }
    }

    fun recordUnlockEventReceived(action: String) {
        val now = SystemClock.elapsedRealtime()
        unlockEventReceivedTime = now
    }

    fun recordLockedBootCompleted() {
        lockedBootCompletedTime = SystemClock.elapsedRealtime()
    }

    fun recordCriticalBootstrapStart() {
        appBootstrapStartTime = SystemClock.elapsedRealtime()
    }

    fun recordCriticalStateReady() {
        val now = SystemClock.elapsedRealtime()
        if (criticalStateReadyTime == 0L) {
            criticalStateReadyTime = now
            protectionEngineReadyTime = now
        }
    }

    fun recordProtectionEngineReady() {
        val now = SystemClock.elapsedRealtime()
        protectionEngineReadyTime = now
    }

    fun recordFirstProtectionAction(actionName: String, targetPackage: String) {
        val now = SystemClock.elapsedRealtime()
        if (firstProtectionActionTime == 0L) {
            firstProtectionActionTime = now
        }
    }

    fun recordSystemUiProtectionReady() {
        val now = SystemClock.elapsedRealtime()
        if (systemUiProtectionReadyTime == 0L) {
            systemUiProtectionReadyTime = now
        }
    }

    fun recordAccessibilityOnCreate() {
        accessibilityOnCreateTime = SystemClock.elapsedRealtime()
    }

    fun recordHandlersReady() {
        if (handlersReadyTime == 0L) {
            handlersReadyTime = SystemClock.elapsedRealtime()
        }
    }

    fun recordAccessibilityConnected() {
        accessibilityConnectedTime = SystemClock.elapsedRealtime()
    }

    fun recordFirstSystemUiEvent() {
        if (firstSystemUiEventTime == 0L) {
            firstSystemUiEventTime = SystemClock.elapsedRealtime()
        }
    }

    fun recordEditCustomizerDetected() {
        editCustomizerDetectedTime = SystemClock.elapsedRealtime()
    }

    fun recordBlockActionStarted() {
        blockActionStartedTime = SystemClock.elapsedRealtime()
    }

    fun recordBlockActionCompleted() {
        blockActionCompletedTime = SystemClock.elapsedRealtime()
    }

    fun recordFullSyncComplete() {
        fullSyncCompleteTime = SystemClock.elapsedRealtime()
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
