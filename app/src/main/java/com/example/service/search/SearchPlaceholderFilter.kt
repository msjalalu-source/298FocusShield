package com.example.service.search

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * High-performance, multilingual search placeholder, hint, and UI label filter.
 * Ensures search interface activation, field focusing, and placeholder hints
 * are NEVER treated as user-entered search queries.
 */
object SearchPlaceholderFilter {

    private val GENERIC_PLACEHOLDERS = setOf(
        "search",
        "search...",
        "search…",
        "search query",
        "search videos",
        "search video",
        "search youtube",
        "search here",
        "search or type url",
        "search or type web address",
        "search or enter url",
        "type to search",
        "search chats",
        "search chats and channels",
        "search messages",
        "search channels",
        "search dialogs",
        "global search",
        "quick search",
        "search music",
        // Bengali
        "সার্চ",
        "সার্চ করুন",
        "ইউটিউবে সার্চ করুন",
        "ভিডিও খুঁজুন",
        "অনুসন্ধান",
        "অনুসন্ধান করুন",
        "খুঁজুন",
        "খোজ করুন",
        "খোঁজ করুন",
        "খোঁজ",
        "বার্তা লিখুন",
        "বার্তা খুঁজুন",
        // Hindi
        "खोजें",
        "सर्च करें",
        "वीडियो खोजें",
        // Spanish
        "buscar",
        "buscar vídeos",
        "buscar videos",
        "buscar en youtube",
        // French
        "rechercher",
        "rechercher sur youtube",
        // German
        "suche",
        "suchen",
        // Arabic
        "بحث",
        "البحث"
    )

    /**
     * Determines whether the given text is a placeholder, hint, or UI label rather than user-entered text.
     */
    fun isPlaceholderOrHint(text: CharSequence?, node: AccessibilityNodeInfo? = null): Boolean {
        if (text.isNullOrBlank()) return true
        val trimmed = text.toString().trim()
        if (trimmed.isEmpty()) return true

        // 1. If an AccessibilityNodeInfo is available, check against hintText or contentDescription
        if (node != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hint = node.hintText?.toString()?.trim()
                if (!hint.isNullOrBlank() && trimmed.equals(hint, ignoreCase = true)) {
                    return true
                }
            }
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrBlank()) {
                if (trimmed.equals(desc, ignoreCase = true)) {
                    return true
                }
            }
        }

        return isGenericPlaceholder(trimmed)
    }

    private fun isGenericPlaceholder(trimmed: String): Boolean {
        val lower = trimmed.lowercase()

        if (GENERIC_PLACEHOLDERS.contains(lower)) {
            return true
        }

        // Common placeholder prefix patterns
        if (lower.startsWith("search in ") ||
            lower.startsWith("search on ") ||
            lower.startsWith("search for videos") ||
            lower.startsWith("type a message") ||
            lower.startsWith("type to search")
        ) {
            return true
        }

        return false
    }
}
