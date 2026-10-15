package com.example.service.search

import java.text.Normalizer
import java.util.Locale

/**
 * High-performance, Unicode-safe text normalizer for keyword matching.
 *
 * Normalization pipeline:
 * Raw query -> Trim -> Unicode normalization (NFKC) -> Strip zero-width chars -> Case fold -> Whitespace collapse
 *
 * Supports:
 * - English
 * - Bangla (বাংলা)
 * - Mixed-language text
 * - Emoji & symbols
 * - Evasion attempt prevention (zero-width joiners/spaces)
 */
object KeywordNormalizer {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200D\\uFEFF]")

    // High-performance bounded LRU cache (256 entries) to avoid repeated Unicode NFKC & Regex allocations
    private const val MAX_CACHE_SIZE = 256
    private val normalizationCache = object : java.util.LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    /**
     * Normalizes a search query or keyword for deterministic comparison.
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        // Fast memory lookup
        synchronized(normalizationCache) {
            val cached = normalizationCache[input]
            if (cached != null) return cached
        }

        // 1. Remove zero-width characters (anti-evasion)
        val stripped = ZERO_WIDTH_REGEX.replace(input, "")

        // 2. Unicode standard normalization (NFKC decomposes compatibility chars and recomposes canonical equivalents)
        val unicodeNormalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC)

        // 3. Lowercase normalization (Locale.ROOT prevents regional casing quirks while handling full Unicode)
        val lowercased = unicodeNormalized.lowercase(Locale.ROOT)

        // 4. Collapse multiple whitespace sequences into a single standard ASCII space and trim
        val result = WHITESPACE_REGEX.replace(lowercased, " ").trim()

        synchronized(normalizationCache) {
            normalizationCache[input] = result
        }

        return result
    }

    /**
     * Validates if a user-entered keyword is valid for saving.
     * Returns a Result with normalized string or error message.
     */
    fun validateKeyword(raw: String?, existingKeywords: List<String> = emptyList()): ValidationResult {
        if (raw.isNullOrBlank()) {
            return ValidationResult.Error("Keyword cannot be empty")
        }

        val normalized = normalize(raw)
        if (normalized.isEmpty()) {
            return ValidationResult.Error("Keyword cannot contain only spaces or invisible characters")
        }

        if (normalized.length > 100) {
            return ValidationResult.Error("Keyword is too long (maximum 100 characters)")
        }

        val isDuplicate = existingKeywords.any { normalize(it) == normalized }
        if (isDuplicate) {
            return ValidationResult.Error("Keyword already exists in the blocked list")
        }

        return ValidationResult.Success(normalized)
    }

    sealed class ValidationResult {
        data class Success(val normalized: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }
}
