package com.example.service.search

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Centralized controller for clearing active search input fields in target apps.
 *
 * Responsibilities:
 * - Unifies duplicated search-field clearing logic across handlers and detectors.
 * - Safely attempts clearing via direct event source first.
 * - Falls back to fast, bounded root node-tree traversal when source is unavailable.
 * - Safely recycles all inspected and unused AccessibilityNodeInfo instances.
 * - Catches all unexpected exceptions so failure never crashes the Accessibility Service.
 */
object SearchFieldController {

    private val KNOWN_SEARCH_VIEW_IDS = listOf(
        "search_edit_text",
        "search_src_text",
        "search_input",
        "search_box",
        "search_bar",
        "toolbar_search_edit_text",
        "query",
        "search_query"
    )

    val CLEAR_TEXT_ARGUMENTS = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
    }

    /**
     * Directly clears text on an already-resolved AccessibilityNodeInfo without traversing the node tree.
     */
    fun clearNodeText(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, CLEAR_TEXT_ARGUMENTS)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Clears the active search field text completely using ACTION_SET_TEXT with an empty string.
     *
     * @param event The accessibility event that triggered the blocking.
     * @param rootSupplier A lambda that supplies the root AccessibilityNodeInfo if needed.
     * @return true if the clear action was dispatched successfully, false otherwise.
     */
    fun clearActiveSearchField(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        // 1. Try direct event source if it is a search field or editable
        val source = try {
            event.source
        } catch (_: Exception) {
            null
        }

        if (source != null) {
            try {
                if (SearchFieldDetector.isSearchField(source) || source.isEditable) {
                    val cleared = clearNodeText(source)
                    if (cleared) return true
                }
            } catch (_: Exception) {
            } finally {
                try { source.recycle() } catch (_: Exception) {}
            }
        }

        // 2. Search root node tree for active search field (root lifecycle is owned and recycled by caller)
        val root = try {
            rootSupplier()
        } catch (_: Exception) {
            null
        } ?: return false

        try {
            val searchNode = findSearchFieldNode(root)
            if (searchNode != null) {
                try {
                    return clearNodeText(searchNode)
                } catch (_: Exception) {
                    return false
                } finally {
                    try { searchNode.recycle() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            return false
        }

        return false
    }

    /**
     * Locates the active search input field node in the hierarchy.
     */
    fun findSearchFieldNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (root.isEditable && SearchFieldDetector.isSearchField(root)) {
                return root
            }

            // Fast path: search by known resource IDs
            for (id in KNOWN_SEARCH_VIEW_IDS) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId(id)
                } catch (_: Exception) {
                    null
                }
                if (!nodes.isNullOrEmpty()) {
                    val target = nodes.firstOrNull { it.isEditable || SearchFieldDetector.isSearchField(it) } ?: nodes[0]
                    for (node in nodes) {
                        if (node !== target) {
                            try { node.recycle() } catch (_: Exception) {}
                        }
                    }
                    return target
                }
            }

            // Bounded recursive fallback (max depth 4, max 12 nodes inspected)
            return scanForSearchEdit(root, depth = 0, count = intArrayOf(0))
        } catch (_: Exception) {
            return null
        }
    }

    private fun scanForSearchEdit(node: AccessibilityNodeInfo, depth: Int, count: IntArray): AccessibilityNodeInfo? {
        if (depth > 4 || count[0] >= 12) return null
        count[0]++

        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until childCount) {
            if (count[0] >= 12) break
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (child.isEditable && SearchFieldDetector.isSearchField(child)) {
                return child
            }
            val found = scanForSearchEdit(child, depth + 1, count)
            if (found != null) {
                try { child.recycle() } catch (_: Exception) {}
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }
}
