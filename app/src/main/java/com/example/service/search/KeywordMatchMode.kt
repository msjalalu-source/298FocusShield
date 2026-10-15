package com.example.service.search

enum class KeywordMatchMode {
    CONTAINS,
    EXACT,
    WORD;

    companion object {
        fun fromString(value: String?): KeywordMatchMode {
            return when (value?.uppercase()) {
                "EXACT" -> EXACT
                "WORD" -> WORD
                else -> CONTAINS
            }
        }
    }
}
