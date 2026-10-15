package com.example.service.search

sealed class SearchPolicyResult {

    data object Allowed : SearchPolicyResult()

    data class Blocked(
        val keywordId: Long,
        val matchedKeyword: String,
        val target: SearchBlockingTarget,
        val matchMode: KeywordMatchMode = KeywordMatchMode.CONTAINS
    ) : SearchPolicyResult()
}
