package com.example.service.search

import com.example.data.BlockedSearchKeyword
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single, central, lightweight search keyword engine.
 *
 * Responsibilities:
 * - Keyword normalization (Unicode NFKC, lowercasing, anti-evasion zero-width stripping, whitespace collapse)
 * - In-memory enabled-keyword snapshot (O(N) memory-only matching, zero disk/Room I/O in hot path)
 * - Simple literal matching (CONTAINS, EXACT) without dynamically generated regexes
 * - Blocked / Allowed decision
 */
class SearchKeywordEngine {

    data class EnabledKeyword(
        val id: Long,
        val originalKeyword: String,
        val normalizedKeyword: String,
        val matchMode: KeywordMatchMode
    )

    @Volatile
    private var snapshot: KeywordSnapshot = KeywordSnapshot.EMPTY

    @Volatile
    var isYouTubeEnabled: Boolean = false

    /**
     * Updates the in-memory snapshot of enabled keywords.
     * Builds a new immutable KeywordSnapshot and atomically replaces the old snapshot.
     * Safe for concurrent reads across threads without locking or mutation.
     */
    fun updateKeywords(keywords: List<BlockedSearchKeyword>?) {
        snapshot = KeywordSnapshot.create(keywords)
    }

    fun getActiveKeywordCount(): Int = snapshot.size

    fun hasActiveKeywords(): Boolean = snapshot.isNotEmpty()

    fun getEnabledKeywords(): List<EnabledKeyword> = snapshot.enabledKeywords

    fun getSnapshot(): KeywordSnapshot = snapshot

    /**
     * Normalizes a search query or keyword for deterministic literal comparison.
     */
    fun normalize(input: String?): String {
        return KeywordNormalizer.normalize(input)
    }

    /**
     * Checks if search blocking is enabled for the specified target app.
     */
    fun isTargetEnabled(target: SearchBlockingTarget): Boolean {
        return when (target) {
            SearchBlockingTarget.YOUTUBE -> isYouTubeEnabled
        }
    }

    /**
     * Simple normalized literal matching.
     * For CONTAINS: normalizedQuery.contains(normalizedBlockedKeyword)
     * For EXACT: normalizedQuery == normalizedBlockedKeyword
     * For WORD: normalizedQuery.contains(normalizedBlockedKeyword) (PRESERVED UNCHANGED)
     * Does NOT use dynamically generated regular expressions for normal keyword matching.
     */
    fun isMatch(normalizedQuery: String, normalizedKeyword: String, matchMode: KeywordMatchMode): Boolean {
        if (normalizedQuery.isEmpty() || normalizedKeyword.isEmpty()) return false
        return when (matchMode) {
            KeywordMatchMode.EXACT -> normalizedQuery == normalizedKeyword
            KeywordMatchMode.CONTAINS,
            KeywordMatchMode.WORD -> normalizedQuery.contains(normalizedKeyword)
        }
    }

    /**
     * Evaluates a pre-normalized search query against active blocked keywords.
     */
    fun evaluateNormalized(
        normalizedQuery: String,
        target: SearchBlockingTarget,
        originalQuery: String? = null
    ): SearchPolicyResult {
        if (!isTargetEnabled(target)) {
            return SearchPolicyResult.Allowed
        }
        if (normalizedQuery.isEmpty()) {
            return SearchPolicyResult.Allowed
        }
        val currentSnapshot = snapshot
        if (currentSnapshot.isEmpty()) {
            return SearchPolicyResult.Allowed
        }

        val keywords = currentSnapshot.enabledKeywords
        val size = keywords.size
        for (i in 0 until size) {
            val item = keywords[i]
            if (isMatch(normalizedQuery, item.normalizedKeyword, item.matchMode)) {
                return SearchPolicyResult.Blocked(
                    keywordId = item.id,
                    matchedKeyword = item.originalKeyword,
                    target = target,
                    matchMode = item.matchMode
                )
            }
        }

        return SearchPolicyResult.Allowed
    }

    /**
     * Evaluates a search query for a specific target app against active blocked keywords.
     * Hot path: zero Room DB queries, zero disk I/O, zero regex compilation, memory-only.
     */
    fun evaluate(rawQuery: String?, target: SearchBlockingTarget): SearchPolicyResult {
        if (!isTargetEnabled(target)) {
            return SearchPolicyResult.Allowed
        }
        if (rawQuery.isNullOrBlank()) {
            return SearchPolicyResult.Allowed
        }
        val currentSnapshot = snapshot
        if (currentSnapshot.isEmpty()) {
            return SearchPolicyResult.Allowed
        }
        val normalizedQuery = normalize(rawQuery)
        if (normalizedQuery.isEmpty()) {
            return SearchPolicyResult.Allowed
        }
        return evaluateNormalized(normalizedQuery, target, rawQuery)
    }
}
