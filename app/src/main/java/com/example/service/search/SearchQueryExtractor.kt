package com.example.service.search

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safely extracts search query text from AccessibilityEvents and AccessibilityNodeInfos.
 * Handles node lifecycle responsibly (deterministic recycling of child nodes).
 */
object SearchQueryExtractor {

    /**
     * Extracts text from an AccessibilityEvent if it matches search typing or clicking.
     */
    fun extractFromEvent(event: AccessibilityEvent): String? {
        val eventTexts = event.text
        if (!eventTexts.isNullOrEmpty()) {
            val combined = eventTexts.joinToString(" ") { it.toString() }.trim()
            if (combined.isNotEmpty() && !SearchPlaceholderFilter.isPlaceholderOrHint(combined)) {
                return combined
            }
        }

        val contentDesc = event.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty() && !SearchPlaceholderFilter.isPlaceholderOrHint(contentDesc)) {
            // Only extract from suggestion content descriptions (e.g. "Search suggestion for gaming")
            val cleaned = cleanSuggestionPrefix(contentDesc)
            if (cleaned.isNotEmpty() && !SearchPlaceholderFilter.isPlaceholderOrHint(cleaned)) {
                return cleaned
            }
        }

        return null
    }

    data class ExtractedSearchField(
        val query: String,
        val targetNode: AccessibilityNodeInfo
    )

    /**
     * Extracts active search query along with its containing AccessibilityNodeInfo.
     * Returns the target node un-recycled so the caller can directly clear it without a second tree traversal.
     */
    fun extractWithNodeFromTree(root: AccessibilityNodeInfo?): ExtractedSearchField? {
        if (root == null) return null
        val nodeCount = intArrayOf(0)
        return scanForSearchQueryWithNode(root, depth = 0, nodeCount = nodeCount)
    }

    private fun scanForSearchQueryWithNode(node: AccessibilityNodeInfo, depth: Int, nodeCount: IntArray): ExtractedSearchField? {
        if (depth > 3 || nodeCount[0] >= 8) return null
        nodeCount[0]++

        if (SearchFieldDetector.isSearchField(node)) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && !SearchPlaceholderFilter.isPlaceholderOrHint(text, node)) {
                return ExtractedSearchField(text, node)
            }
        }

        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until childCount) {
            if (nodeCount[0] >= 8) break
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = scanForSearchQueryWithNode(child, depth + 1, nodeCount)
            if (found != null) {
                if (found.targetNode !== child) {
                    try { child.recycle() } catch (_: Exception) {}
                }
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }

        return null
    }

    private fun cleanSuggestionPrefix(raw: String): String {
        return when {
            raw.startsWith("search suggestion for ", ignoreCase = true) ->
                raw.substring("search suggestion for ".length).trim()
            raw.startsWith("search suggestion ", ignoreCase = true) ->
                raw.substring("search suggestion ".length).trim()
            raw.startsWith("suggestion: ", ignoreCase = true) ->
                raw.substring("suggestion: ".length).trim()
            else -> ""
        }
    }
}
