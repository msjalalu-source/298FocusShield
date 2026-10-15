package com.example.service.search

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Common base detector for video and media streaming applications (YouTube).
 * Consolidates duplicated query extraction, placeholder filtering, submit button detection,
 * and media player content-change filtering.
 */
abstract class BaseMediaSearchDetector : SearchDetector {

    protected open val irrelevantResourceSubstrings: List<String> = listOf(
        "player",
        "thumbnail",
        "progress",
        "seekbar",
        "comment",
        "reply"
    )

    protected open fun isSearchSubmitButton(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        return viewId.contains("search_btn") ||
                viewId.contains("search_button") ||
                viewId.contains("action_search") ||
                viewId.contains("submit")
    }

    override fun extractSearchQuery(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): SearchDetector.ExtractedQuery? {
        val eventType = event.eventType

        // 1. Search Activation Guard: TYPE_VIEW_FOCUSED is focus/IME activation, NEVER a query submission
        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return null
        }

        // 2. Direct event text extraction (Typing / Clicking on suggestions or submit)
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val source = event.source
            if (source != null) {
                var retainedTargetNode = false
                try {
                    val isSuggestionOrSubmit = SearchFieldDetector.isSuggestionOrSubmitItem(source) || isSearchSubmitButton(source)
                    val isSearchField = SearchFieldDetector.isSearchField(source)

                    if (isSuggestionOrSubmit || isSearchField) {
                        // Tapping into a search EditText to type is SEARCH FIELD ACTIVATION, NOT submission
                        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isSearchField && !isSuggestionOrSubmit) {
                            return null
                        }

                        val text = source.text?.toString()?.trim() ?: SearchQueryExtractor.extractFromEvent(event)
                        if (!text.isNullOrBlank() && !SearchPlaceholderFilter.isPlaceholderOrHint(text, source)) {
                            val isTyping = (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                            val isSubmit = (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isSuggestionOrSubmit)
                            val targetNode = if (isSearchField) {
                                retainedTargetNode = true
                                source
                            } else null
                            return SearchDetector.ExtractedQuery(
                                query = text,
                                isSubmissionAttempt = isSubmit,
                                confidence = SearchDetector.Confidence.HIGH,
                                isTypingEvent = isTyping,
                                targetNode = targetNode
                            )
                        }
                    }
                } finally {
                    if (!retainedTargetNode) {
                        try { source.recycle() } catch (_: Exception) {}
                    }
                }
            } else {
                // If source is null, only extract on actual typing
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    val eventText = SearchQueryExtractor.extractFromEvent(event)
                    if (!eventText.isNullOrBlank() && !SearchPlaceholderFilter.isPlaceholderOrHint(eventText)) {
                        return SearchDetector.ExtractedQuery(
                            query = eventText,
                            isSubmissionAttempt = false,
                            confidence = SearchDetector.Confidence.MEDIUM,
                            isTypingEvent = true
                        )
                    }
                }
            }
        }

        // 3. Window state or window content changed - shallow inspect active search query
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            // Early gate: skip root traversal if content change event is for media player/comments
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isIrrelevantContentChange(event)) {
                return null
            }

            // Fast path: try extracting directly from event before calling rootSupplier
            val eventQuery = SearchQueryExtractor.extractFromEvent(event)
            if (!eventQuery.isNullOrBlank() && !SearchPlaceholderFilter.isPlaceholderOrHint(eventQuery)) {
                return SearchDetector.ExtractedQuery(
                    query = eventQuery,
                    isSubmissionAttempt = false,
                    confidence = SearchDetector.Confidence.HIGH,
                    isTypingEvent = false
                )
            }

            val root = try { rootSupplier() } catch (_: Exception) { null }
            val extractedField = if (root != null) {
                try {
                    SearchQueryExtractor.extractWithNodeFromTree(root)
                } catch (_: Exception) {
                    null
                }
            } else null

            if (extractedField != null) {
                val query = extractedField.query
                if (!query.isBlank() && !SearchPlaceholderFilter.isPlaceholderOrHint(query)) {
                    return SearchDetector.ExtractedQuery(
                        query = query,
                        isSubmissionAttempt = false,
                        confidence = SearchDetector.Confidence.HIGH,
                        isTypingEvent = false,
                        targetNode = extractedField.targetNode
                    )
                } else {
                    try { extractedField.targetNode.recycle() } catch (_: Exception) {}
                }
            }
        }

        return null
    }

    protected open fun isIrrelevantContentChange(event: AccessibilityEvent): Boolean {
        val source = try { event.source } catch (_: Exception) { null } ?: return false
        try {
            val viewId = source.viewIdResourceName?.lowercase() ?: ""
            if (viewId.isNotEmpty()) {
                for (sub in irrelevantResourceSubstrings) {
                    if (viewId.contains(sub)) return true
                }
            }
            return false
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    override fun isSearchSubmissionAttempt(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null) {
                try {
                    if (SearchFieldDetector.isSuggestionOrSubmitItem(source)) return true
                    if (isSearchSubmitButton(source)) return true
                } finally {
                    try { source.recycle() } catch (_: Exception) {}
                }
            }
        }
        return false
    }
}
