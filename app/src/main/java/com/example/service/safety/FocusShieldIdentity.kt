package com.example.service.safety

import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Dynamically resolves and matches Focus Shield identity across UI nodes,
 * using authoritative package metadata first, and normalized visible label matching second.
 */
class FocusShieldIdentity(
    val packageName: String,
    val appLabel: String
) {
    private val normalizedAppLabel: String = normalize(appLabel)
    private val normalizedPackage: String = packageName.lowercase().trim()

    companion object {
        // Canonical and multilingual identifiers for Focus Shield / FocusGuard
        // Normalized once in companion object to avoid per-instance list allocation
        private val KNOWN_APP_ALIASES = listOf(
            "focus shield",
            "focusshield",
            "focus guard",
            "focusguard",
            "focuslock",
            "focus lock",
            "focus device admin",
            "focus controller",
            "focusaccessibilityservice",
            "focusdeviceadminreceiver",
            "ফোকাস শিল্ড",
            "ফোকাস গার্ড",
            "ফোকাস শেল্ড",
            "ফোকাস",
            "फोकस शील्ड",
            "फोकस गार्ड"
        ).map { normalize(it) }

        @Volatile
        private var cachedInstance: FocusShieldIdentity? = null

        fun fromContext(context: Context): FocusShieldIdentity {
            val cached = cachedInstance
            if (cached != null) return cached

            synchronized(this) {
                cachedInstance?.let { return it }
                val pkg = context.packageName ?: "com.example"
                val label = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo)?.toString()
                } catch (_: Exception) {
                    null
                } ?: "Focus Shield"
                val instance = FocusShieldIdentity(pkg, label)
                cachedInstance = instance
                return instance
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            cachedInstance = null
        }

        /**
         * Normalizes UI text:
         * - Lowercase
         * - Strips punctuation while preserving letters, digits, and multilingual combining marks/vowels
         * - Handles case and whitespace differences
         */
        fun normalize(raw: CharSequence?): String {
            if (raw.isNullOrBlank()) return ""
            val sb = StringBuilder(raw.length)
            var lastWasSpace = false
            for (i in 0 until raw.length) {
                val c = raw[i]
                val type = Character.getType(c)
                val isCharPart = Character.isLetterOrDigit(c) ||
                        type == Character.NON_SPACING_MARK.toInt() ||
                        type == Character.COMBINING_SPACING_MARK.toInt() ||
                        type == Character.ENCLOSING_MARK.toInt()
                if (c.isWhitespace()) {
                    if (!lastWasSpace && sb.isNotEmpty()) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                } else if (isCharPart) {
                    sb.append(c.lowercaseChar())
                    lastWasSpace = false
                }
            }
            return sb.toString().trim()
        }
    }

    /**
     * Checks if an AccessibilityNodeInfo represents Focus Shield.
     * Prefers authoritative package name check when present.
     */
    fun matches(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. Authoritative package check
        val nodePkg = node.packageName?.toString()?.lowercase()?.trim()
        if (!nodePkg.isNullOrEmpty() && nodePkg == normalizedPackage) {
            return true
        }

        // 2. Visible text & description check
        return matchesText(node.text) || matchesText(node.contentDescription)
    }

    /**
     * Matches raw text against Focus Shield identity with strict token boundary validation.
     * Avoids false positives on similar words (e.g. "Focus Time", "Shield Antivirus").
     */
    fun matchesText(raw: CharSequence?): Boolean {
        if (raw.isNullOrBlank()) return false
        val norm = normalize(raw)
        if (norm.isEmpty()) return false

        // Match against exact package name if embedded in text
        if (norm.contains(normalizedPackage)) {
            return true
        }

        // Match against resolved application label
        if (normalizedAppLabel.isNotEmpty() && matchesLabelWithBoundaries(norm, normalizedAppLabel)) {
            return true
        }

        // Match against known multilingual aliases
        for (alias in KNOWN_APP_ALIASES) {
            if (matchesLabelWithBoundaries(norm, alias)) {
                return true
            }
        }

        return false
    }

    /**
     * Verifies that the candidate label occurs as a distinct phrase or complete token,
     * not a partial match (e.g., matches "Focus Shield", "Focus Shield - Active",
     * but does NOT match "Focus Time" or "Shield Antivirus").
     */
    private fun matchesLabelWithBoundaries(text: String, label: String): Boolean {
        if (text == label) return true
        if (text.startsWith("$label ") || text.endsWith(" $label") || text.contains(" $label ")) {
            return true
        }
        val idx = text.indexOf(label)
        if (idx >= 0) {
            val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val afterIdx = idx + label.length
            val afterOk = afterIdx == text.length || !text[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
        }
        return false
    }
}
