package com.example.service.accessibility.handlers

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.search.SearchFieldController
import com.example.service.search.SearchPlaceholderFilter
import com.example.service.search.SearchPolicyResult

/**
 * Minimal, lightweight, deterministic search keyword blocking handler.
 *
 * Execution Flow:
 * AccessibilityEvent
 * → Target App Check
 * → Search Query Extraction
 * → Normalization & Debounce Check
 * → SearchKeywordEngine Evaluation
 * → Match Found
 * → Clear Entire Search Field via SearchFieldController
 * → Prevent Navigation (performBackAction)
 */
class SearchKeywordHandler(
    private val contextProvider: () -> AccessibilityHandlerContext
) : AccessibilityHandler {

    private var lastBlockedNormalizedQuery: String = ""
    private var lastBlockedRawQuery: String = ""
    private var lastBlockedPackage: String = ""
    private var lastBlockedTime: Long = 0L

    private var lastAllowedNormalizedQuery: String = ""
    private var lastAllowedPackage: String = ""

    private val recentlyAllowedQueries = object : java.util.LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 16
    }

    companion object {
        private const val DEBOUNCE_WINDOW_MS = 800L
    }

    override fun canHandle(packageName: String, event: AccessibilityEvent): Boolean {
        val ctx = contextProvider()
        if (!ctx.searchKeywordEngine.hasActiveKeywords()) return false
        val detector = ctx.searchDetectorRouter.findDetector(packageName) ?: return false
        return ctx.searchKeywordEngine.isTargetEnabled(detector.target)
    }

    override fun handleEvent(
        packageName: String,
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        // Guard 1: Focus events are interface activations, never search query submissions
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return false
        }

        val ctx = contextProvider()
        if (!ctx.searchKeywordEngine.hasActiveKeywords()) {
            return false
        }
        val detector = ctx.searchDetectorRouter.findDetector(packageName) ?: return false

        // Extract search query
        val extracted = try {
            detector.extractSearchQuery(event, rootSupplier)
        } catch (e: Exception) {
            ctx.recordException("SearchKeywordHandler", "Error extracting search query for $packageName", e)
            null
        }

        if (extracted == null || extracted.query.isBlank()) {
            return false
        }

        val targetNode = extracted.targetNode

        val query = extracted.query.trim()

        // Guard 2: Never evaluate placeholders, hints, or UI labels
        if (SearchPlaceholderFilter.isPlaceholderOrHint(query)) {
            recycleNode(targetNode)
            return false
        }

        // Guard 3: Clicking into search field to focus/type is interface activation, not submission
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !extracted.isSubmissionAttempt) {
            recycleNode(targetNode)
            return false
        }

        val normalizedQuery = ctx.searchKeywordEngine.normalize(query)
        if (normalizedQuery.isEmpty()) {
            recycleNode(targetNode)
            return false
        }

        val now = SystemClock.elapsedRealtime()

        // Event Loop Protection / Suppression:
        // Clearing the search field generates another AccessibilityEvent.
        // Suppress repeated processing for the same normalized query within debounce window.
        // e.g. "Football", "football", "FOOTBALL" are normalized identically and debounced safely.
        if (packageName == lastBlockedPackage &&
            normalizedQuery == lastBlockedNormalizedQuery &&
            (now - lastBlockedTime < DEBOUNCE_WINDOW_MS)
        ) {
            recycleNode(targetNode)
            return true
        }

        // Query Cache Gate: If this query was already evaluated and allowed, skip re-evaluation
        val allowedCacheKey = "$packageName:$normalizedQuery"
        val isAllowedCached = synchronized(recentlyAllowedQueries) {
            (packageName == lastAllowedPackage && normalizedQuery == lastAllowedNormalizedQuery) ||
                    recentlyAllowedQueries.containsKey(allowedCacheKey)
        }
        if (isAllowedCached) {
            recycleNode(targetNode)
            return false
        }

        // Evaluate query with SearchKeywordEngine
        val policyResult = try {
            ctx.searchKeywordEngine.evaluateNormalized(normalizedQuery, detector.target, query)
        } catch (e: Exception) {
            ctx.recordException("SearchKeywordHandler", "Error evaluating keyword engine for query", e)
            SearchPolicyResult.Allowed
        }

        if (policyResult !is SearchPolicyResult.Blocked) {
            recycleNode(targetNode)
            lastAllowedNormalizedQuery = normalizedQuery
            lastAllowedPackage = packageName
            synchronized(recentlyAllowedQueries) {
                recentlyAllowedQueries[allowedCacheKey] = true
            }
            return false
        }

        lastBlockedNormalizedQuery = normalizedQuery
        lastBlockedRawQuery = query
        lastBlockedPackage = packageName
        lastBlockedTime = now

        val request = com.example.service.protection.ProtectionRequest.SearchKeyword(
            packageName = packageName,
            target = detector.target,
            query = query,
            matchedKeyword = policyResult.matchedKeyword,
            clearSearchBox = {
                val cleared = if (targetNode != null) {
                    try {
                        SearchFieldController.clearNodeText(targetNode)
                    } catch (_: Exception) {
                        false
                    } finally {
                        recycleNode(targetNode)
                    }
                } else false

                if (!cleared) {
                    clearSearchField(event, rootSupplier)
                }
                true
            }
        )

        return ctx.effectiveProtectionEngine.handleRequest(request)
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (_: Exception) {}
    }

    /**
     * Clears the entire search field using ACTION_SET_TEXT with an empty string.
     * Delegates to centralized SearchFieldController.
     */
    private fun clearSearchField(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        return SearchFieldController.clearActiveSearchField(event, rootSupplier)
    }

    fun getLastBlockedQuery(): String = lastBlockedRawQuery.ifEmpty { lastBlockedNormalizedQuery }

    fun resetState() {
        lastBlockedNormalizedQuery = ""
        lastBlockedRawQuery = ""
        lastBlockedPackage = ""
        lastBlockedTime = 0L
        lastAllowedNormalizedQuery = ""
        lastAllowedPackage = ""
        synchronized(recentlyAllowedQueries) {
            recentlyAllowedQueries.clear()
        }
    }
}
