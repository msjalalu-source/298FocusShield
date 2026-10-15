package com.example.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "locked_apps",
    indices = [
        Index(value = ["isAppLocked"])
    ]
)
data class LockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isAppLocked: Boolean = false,
    val isSystemApp: Boolean = false,
    val lockedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "unlock_requests",
    indices = [
        Index(value = ["status"]),
        Index(value = ["targetKey"]),
        Index(value = ["unlockAtTime"])
    ]
)
data class UnlockRequest(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val targetType: String, // "APP_LOCK", "CONTROL_CENTER_EDIT_LOCK", "UNINSTALL_PROTECTION", "NEW_INSTALL_APP", "REMEMBER_PROTECTION_MASTER", "REMEMBER_TARGET_DELETE", "SEARCH_KEYWORD"
    val targetKey: String, // Package name or feature key
    val requestedAt: Long = System.currentTimeMillis(),
    val unlockAtTime: Long, // When it can be unlocked (requestedAt + hours * 3600000)
    val unlockDurationHours: Int = 12,
    val status: String = "PENDING" // "PENDING", "COMPLETED", "CANCELLED"
)

@Immutable
@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

