package com.example.service.search

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Common contract for application-specific search detectors.
 */
interface SearchDetector {

    val target: SearchBlockingTarget

    /**
     * Checks if this detector handles the given package name.
     */
    fun canHandle(packageName: String?): Boolean

    /**
     * Attempts to extract an active search query from the current accessibility event and root window.
     */
    fun extractSearchQuery(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): ExtractedQuery?

    /**
     * Checks if the event indicates an intentional search submission (e.g. keyboard search, clicking suggestion, or clicking search submit button).
     */
    fun isSearchSubmissionAttempt(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean

    data class ExtractedQuery(
        val query: String,
        val isSubmissionAttempt: Boolean = false,
        val confidence: Confidence = Confidence.HIGH,
        val isTypingEvent: Boolean = false,
        val targetNode: AccessibilityNodeInfo? = null
    )

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW
    }
}
