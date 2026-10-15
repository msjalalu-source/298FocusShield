package com.example.service.protection

import android.os.SystemClock
import com.example.service.systemui.NavigationRestrictionEngine

/**
 * Common Blocking Action Layer.
 *
 * Centralizes genuine common blocking operations:
 * - Duplicate event suppression & debouncing (prevents infinite loops, UI lag, and repeated actions)
 * - Navigation prevention (performBackAction with fallback performHomeAction)
 * - Search field clearing (empties search query completely on match)
 * - Feedback toast notification
 */
class CommonBlockingAction(
    private val performBack: () -> Boolean,
    private val performHome: (targetPackage: String?) -> Boolean = { false },
    private val showToast: (message: String, durationMs: Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val SEARCH_DEBOUNCE_MS = 800L
    }

    @Volatile private var lastSearchQuery = ""
    @Volatile private var lastSearchTime = 0L

    fun resetForTesting() {
        lastSearchQuery = ""
        lastSearchTime = 0L
        NavigationRestrictionEngine.resetForTesting()
    }

    /**
     * Checks if this block request should be suppressed due to duplicate event bursts.
     */
    fun isSuppressed(request: ProtectionRequest): Boolean {
        val now = SystemClock.elapsedRealtime()
        return when (request) {
            is ProtectionRequest.SearchKeyword -> {
                request.query == lastSearchQuery && lastSearchTime != 0L && (now - lastSearchTime < SEARCH_DEBOUNCE_MS)
            }
        }
    }

    /**
     * Executes the appropriate blocking action based on the request and decision.
     */
    fun execute(request: ProtectionRequest, decision: ProtectionDecision.Block): Boolean {
        val now = SystemClock.elapsedRealtime()

        return when (request) {
            is ProtectionRequest.SearchKeyword -> {
                // Event loop protection: if already debounced/suppressed, consume without repeating actions
                if (request.query == lastSearchQuery && (now - lastSearchTime < SEARCH_DEBOUNCE_MS)) {
                    return true
                }
                lastSearchQuery = request.query
                lastSearchTime = now

                // 1. Clear the ENTIRE search box
                request.clearSearchBox?.invoke()

                // 2. Prevent navigation
                if (!performBack()) {
                    performHome(request.packageName)
                }

                // 3. User feedback
                val msg = decision.customMessage ?: "🚫 Search blocked: Restricted keyword '${request.matchedKeyword}'"
                showToast(msg, 2000L)
                true
            }
        }
    }
}
