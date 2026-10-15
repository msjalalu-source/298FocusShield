package com.example.service.systemui

import android.os.SystemClock
import android.util.Log

/**
 * Handles navigation restriction execution and throttling (Back/Home prevention).
 * Ensures immediate restriction without showing any custom overlays, dialogs, popups, or blocking screens.
 */
object NavigationRestrictionEngine {

    const val AUDIT_TAG = "NavigationRestrictionAudit"

    @Volatile
    private var lastRestrictionTime: Long = 0L

    @Volatile
    private var lastRestrictedTarget: String = ""

    @Volatile
    private var repeatCount: Int = 0

    /**
     * Executes immediate navigation restriction without showing any custom overlays, dialogs,
     * popups, or blocking screens:
     * 1. Performs Back action immediately.
     * 2. If back fails or if screen persists (repeated trigger within 1.5s), falls back to Home action.
     */
    fun executeNavigationPrevention(
        targetName: String = "Restricted Target",
        performBack: () -> Boolean,
        performHome: (() -> Boolean)? = null
    ): Boolean {
        val now = SystemClock.elapsedRealtime()

        val isSameTarget = (targetName.isNotEmpty() && targetName == lastRestrictedTarget)

        // Debounce rapid event bursts from accessibility tree churn on the same target (< 35ms)
        if (isSameTarget && now - lastRestrictionTime < 35L) {
            return true
        }

        val isQuickReentry = isSameTarget && (now - lastRestrictionTime in 35L..1500L)

        if (isQuickReentry) {
            repeatCount++
        } else {
            repeatCount = 0
        }
        lastRestrictionTime = now
        lastRestrictedTarget = targetName

        var backSuccess = false
        try {
            backSuccess = performBack()
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "🛡️ [AUDIT_ACTION] Error performing back navigation for '$targetName'", e)
        }

        var homeSuccess: Boolean? = null
        if ((!backSuccess || repeatCount >= 1) && performHome != null) {
            try {
                homeSuccess = performHome()
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "🛡️ [AUDIT_ACTION] Error executing performHomeAction fallback for '$targetName'", e)
            }
        }

        Log.i(
            AUDIT_TAG,
            "🛡️ [AUDIT_ACTION] Target '$targetName' restricted: back=$backSuccess, repeatCount=$repeatCount" +
                (if (homeSuccess != null) ", fallbackHome=$homeSuccess" else "")
        )
        return true
    }

    /**
     * Backward-compatible overload.
     */
    fun executeNavigationPrevention(
        performBack: () -> Boolean,
        performHome: (() -> Boolean)? = null
    ): Boolean {
        return executeNavigationPrevention("Restricted Target", performBack, performHome)
    }

    /**
     * Resets internal throttle and tracking state for unit and workflow testing.
     */
    fun resetForTesting() {
        lastRestrictionTime = 0L
        lastRestrictedTarget = ""
        repeatCount = 0
    }
}
