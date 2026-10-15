package com.example.service.protection

import com.example.service.search.SearchBlockingTarget

/**
 * Lightweight request model containing only the information required for a protection decision.
 */
sealed class ProtectionRequest {
    abstract val type: ProtectionType
    abstract val packageName: String

    data class SearchKeyword(
        override val packageName: String,
        val target: SearchBlockingTarget,
        val query: String,
        val matchedKeyword: String,
        val clearSearchBox: (() -> Boolean)? = null
    ) : ProtectionRequest() {
        override val type: ProtectionType = ProtectionType.SEARCH_KEYWORD
    }
}
