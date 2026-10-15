package com.example.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "blocked_search_keywords",
    indices = [
        Index(value = ["normalizedKeyword"], unique = true),
        Index(value = ["enabled"])
    ]
)
data class BlockedSearchKeyword(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val normalizedKeyword: String = "",
    val enabled: Boolean = true,
    val matchMode: String = "CONTAINS", // "CONTAINS", "EXACT", "WORD"
    val createdAt: Long = System.currentTimeMillis()
)
