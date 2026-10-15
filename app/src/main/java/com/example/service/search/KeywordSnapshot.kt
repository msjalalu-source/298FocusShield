package com.example.service.search

import com.example.data.BlockedSearchKeyword
import java.util.Collections

/**
 * Immutable in-memory snapshot of active blocked search keywords.
 *
 * Thread-safe: Once created, instances are completely immutable and safe for
 * concurrent reads across multiple threads without locking or synchronization overhead.
 */
class KeywordSnapshot private constructor(
    val enabledKeywords: List<SearchKeywordEngine.EnabledKeyword>,
    private val normalizedSet: Set<String>
) {
    val size: Int get() = enabledKeywords.size

    fun isEmpty(): Boolean = enabledKeywords.isEmpty()

    fun isNotEmpty(): Boolean = enabledKeywords.isNotEmpty()

    operator fun get(index: Int): SearchKeywordEngine.EnabledKeyword = enabledKeywords[index]

    fun containsNormalized(normalized: String): Boolean = normalizedSet.contains(normalized)

    companion object {
        val EMPTY = KeywordSnapshot(
            enabledKeywords = Collections.emptyList(),
            normalizedSet = Collections.emptySet()
        )

        /**
         * Safely transforms, normalizes, validates, and deduplicates active keywords
         * into an immutable snapshot.
         *
         * Pipeline:
         * Database entities
         * -> Keyword validation (non-null, non-blank)
         * -> KeywordNormalizer.normalize() (do NOT trust stale or previously stored normalizedKeyword blindly)
         * -> Validated normalized keyword check (non-empty)
         * -> Deduplication (first seen wins)
         * -> Immutable in-memory snapshot
         */
        fun create(keywords: List<BlockedSearchKeyword>?): KeywordSnapshot {
            if (keywords.isNullOrEmpty()) return EMPTY

            val seenNormalized = HashSet<String>(keywords.size)
            val validatedList = ArrayList<SearchKeywordEngine.EnabledKeyword>(keywords.size)

            for (item in keywords) {
                if (!item.enabled) continue
                val raw = item.keyword
                if (raw.isNullOrBlank()) continue

                // Always normalize through KeywordNormalizer to guarantee correct normalization
                val normalized = KeywordNormalizer.normalize(raw)
                if (normalized.isEmpty()) continue

                // Deduplicate normalized keywords: keep the first entry
                if (seenNormalized.add(normalized)) {
                    validatedList.add(
                        SearchKeywordEngine.EnabledKeyword(
                            id = item.id,
                            originalKeyword = raw.trim(),
                            normalizedKeyword = normalized,
                            matchMode = KeywordMatchMode.fromString(item.matchMode)
                        )
                    )
                }
            }

            if (validatedList.isEmpty()) return EMPTY

            return KeywordSnapshot(
                enabledKeywords = Collections.unmodifiableList(validatedList),
                normalizedSet = Collections.unmodifiableSet(seenNormalized)
            )
        }
    }
}
