package com.example.service.search

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Resilient multi-tier search field detector.
 *
 * Evaluates accessibility nodes using a confidence-oriented detection order:
 * 1. Resource ID / known search-field identifiers
 * 2. Editable input characteristics (reject non-input nodes early)
 * 3. Accessibility input metadata (SearchView, inputType variations)
 * 4. Search-related hints/content descriptions (specific search labels)
 * 5. Generic textual fallback (validated search placeholders only)
 */
object SearchFieldDetector {

    private val KNOWN_SEARCH_RESOURCE_IDS = setOf(
        "search_edit_text",
        "search_src_text",
        "search_input",
        "search_box",
        "search_bar",
        "search_field",
        "query",
        "search_query",
        "search_text",
        "search_view"
    )

    private val KNOWN_SEARCH_DESCRIPTIONS = listOf(
        "search",
        "search query",
        "search youtube",
        "সার্চ",
        "ইউটিউব সার্চ",
        "সার্চ ক্যোয়ারী",
        "search videos"
    )

    private val KNOWN_NON_SEARCH_RESOURCE_IDS = setOf(
        "comment_edit_text",
        "reply_edit_text",
        "add_comment",
        "chat_text_edit",
        "message_box",
        "messagebox",
        "description",
        "feedback_text",
        "post_input"
    )

    /**
     * Checks whether an accessibility node is a search input field.
     * Uses strict confidence tiers to prevent false positives from chat, comment,
     * or arbitrary user text containing the word "search".
     */
    fun isSearchField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase() ?: ""
        } else ""
        val cls = node.className?.toString()?.lowercase() ?: ""

        // Exclusions: Comment, chat, reply, or post text fields are never search fields
        for (excluded in KNOWN_NON_SEARCH_RESOURCE_IDS) {
            if (viewId.contains(excluded)) return false
        }
        if (desc.contains("comment") || desc.contains("মন্তব্য") || desc.contains("reply") ||
            hint.contains("comment") || hint.contains("মন্তব্য") || hint.contains("reply")) {
            return false
        }

        // Tier 1: Resource ID matching (Highest confidence)
        if (viewId.isNotEmpty()) {
            for (knownId in KNOWN_SEARCH_RESOURCE_IDS) {
                if (viewId.endsWith(":id/$knownId") || viewId.contains("/$knownId")) {
                    return true
                }
            }
        }

        // Tier 2: Editable input characteristics
        // Non-editable, non-input nodes (e.g. static labels, icons, normal buttons) are rejected
        val isInputNode = node.isEditable ||
            cls.contains("edittext") ||
            cls.contains("searchview") ||
            cls.contains("autocompletetextview")

        if (!isInputNode) {
            return false
        }

        // Tier 3: Accessibility input metadata
        if (cls.contains("searchview")) {
            return true
        }
        val inputType = node.inputType
        if (inputType != 0) {
            val variation = inputType and 0x00000ff0
            // TYPE_TEXT_VARIATION_WEB_SEARCH (0x00a0) or TYPE_TEXT_VARIATION_FILTER (0x00b0)
            if (variation == 0x000000a0 || variation == 0x000000b0) {
                return true
            }
        }

        // Tier 4: Search-related hints / content descriptions
        if (desc.isNotEmpty()) {
            for (knownDesc in KNOWN_SEARCH_DESCRIPTIONS) {
                if (desc == knownDesc || desc.startsWith("$knownDesc ") || desc.startsWith("$knownDesc...")) {
                    return true
                }
            }
        }
        if (hint.isNotEmpty()) {
            for (knownDesc in KNOWN_SEARCH_DESCRIPTIONS) {
                if (hint == knownDesc || hint.startsWith("$knownDesc ") || hint.startsWith("$knownDesc...")) {
                    return true
                }
            }
        }

        // Tier 5: Generic textual fallback
        // Only valid if the field text itself is a recognized search placeholder/hint,
        // NEVER when the user has typed text containing the word "search".
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        if (text.isNotEmpty() && SearchPlaceholderFilter.isPlaceholderOrHint(text, node)) {
            for (knownDesc in KNOWN_SEARCH_DESCRIPTIONS) {
                if (text == knownDesc || text.startsWith("$knownDesc ")) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Checks whether an item is a search suggestion or search result entry item.
     */
    fun isSuggestionOrSubmitItem(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (viewId.contains("suggestion") || viewId.contains("item_search") || viewId.contains("search_item")) {
            return true
        }

        if (desc.contains("suggestion") || desc.contains("search for") || desc.contains("সার্চ করুন")) {
            return true
        }

        return false
    }
}
