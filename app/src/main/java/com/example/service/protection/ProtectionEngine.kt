package com.example.service.protection

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lightweight, centralized Protection Engine.
 *
 * Responsibilities:
 * 1. Receiving protection requests (ProtectionRequest)
 * 2. Checking relevant cached protection state
 * 3. Returning ALLOW / BLOCK decision (ProtectionDecision)
 * 4. Coordinating common blocking action (CommonBlockingAction)
 * 5. Lightweight duplicate-event suppression
 *
 * Does NOT contain UI-specific detection logic.
 * Feature detectors remain modular and independent.
 */
class ProtectionEngine(
    private val stateProvider: ProtectionStateProvider,
    val blockingAction: CommonBlockingAction
) {
    /**
     * Core decision logic for any ProtectionRequest.
     * Evaluates in-memory cached state and returns ALLOW or BLOCK.
     */
    fun evaluate(request: ProtectionRequest): ProtectionDecision {
        return when (request) {
            is ProtectionRequest.SearchKeyword -> {
                if (stateProvider.isSearchBlockingEnabled(request.target)) {
                    ProtectionDecision.Block(
                        type = ProtectionType.SEARCH_KEYWORD,
                        reason = "Restricted search keyword matched: ${request.matchedKeyword}",
                        targetName = request.query
                    )
                } else {
                    ProtectionDecision.Allow
                }
            }
        }
    }

    /**
     * Evaluates a request, and if blocked, coordinates the common blocking action.
     * Handles duplicate suppression safely.
     * @return true if blocked and action was executed/suppressed, false if allowed.
     */
    fun handleRequest(request: ProtectionRequest): Boolean {
        val decision = evaluate(request)
        return when (decision) {
            is ProtectionDecision.Block -> {
                blockingAction.execute(request, decision)
                true
            }
            is ProtectionDecision.Allow -> false
        }
    }

    fun resetForTesting() {
        blockingAction.resetForTesting()
    }
}
