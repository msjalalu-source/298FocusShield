package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // Locked Apps
    @Query("SELECT * FROM locked_apps ORDER BY appName ASC")
    fun getAllAppsFlow(): Flow<List<LockedApp>>

    @Query("SELECT * FROM locked_apps")
    suspend fun getAllAppsList(): List<LockedApp>

    @Query("SELECT * FROM locked_apps WHERE packageName = :packageName")
    suspend fun getAppByPackage(packageName: String): LockedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: LockedApp): Long

    @Update
    suspend fun updateApp(app: LockedApp)

    @Query("UPDATE locked_apps SET appName = :appName, isSystemApp = :isSystemApp WHERE packageName = :packageName")
    suspend fun updateAppMetadata(packageName: String, appName: String, isSystemApp: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<LockedApp>)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun deleteAppByPackage(packageName: String)

    // Unlock Requests (Delayed Unlock Timer)
    @Query("SELECT * FROM unlock_requests ORDER BY unlockAtTime ASC")
    fun getAllUnlockRequestsFlow(): Flow<List<UnlockRequest>>

    @Query("SELECT * FROM unlock_requests WHERE status = 'PENDING'")
    suspend fun getPendingUnlockRequests(): List<UnlockRequest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnlockRequest(request: UnlockRequest): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnlockRequests(requests: List<UnlockRequest>): List<Long>

    @Update
    suspend fun updateUnlockRequest(request: UnlockRequest)

    @Query("DELETE FROM unlock_requests WHERE id = :id")
    suspend fun deleteUnlockRequest(id: Int)

    @Query("DELETE FROM unlock_requests WHERE targetKey = :targetKey OR targetType = :targetType")
    suspend fun deleteUnlockRequestsByTargetKeyOrType(targetKey: String, targetType: String)

    // Settings
    @Query("SELECT * FROM app_settings")
    fun getAllSettingsFlow(): Flow<List<AppSetting>>

    @Query("SELECT * FROM app_settings")
    suspend fun getAllSettingsList(): List<AppSetting>

    @Query("SELECT value FROM app_settings WHERE key = :key")
    suspend fun getSettingValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: AppSetting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: List<AppSetting>)

    @Query("DELETE FROM app_settings WHERE key = :key")
    suspend fun deleteSettingByKey(key: String)

    // Blocked Search Keywords
    @Query("SELECT * FROM blocked_search_keywords ORDER BY createdAt DESC")
    fun getAllBlockedKeywordsFlow(): Flow<List<BlockedSearchKeyword>>

    @Query("SELECT * FROM blocked_search_keywords WHERE enabled = 1 ORDER BY createdAt DESC")
    fun getActiveBlockedKeywordsFlow(): Flow<List<BlockedSearchKeyword>>

    @Query("SELECT * FROM blocked_search_keywords ORDER BY createdAt DESC")
    suspend fun getAllBlockedKeywordsList(): List<BlockedSearchKeyword>

    @Query("SELECT * FROM blocked_search_keywords WHERE enabled = 1")
    suspend fun getActiveBlockedKeywordsList(): List<BlockedSearchKeyword>

    @Query("SELECT * FROM blocked_search_keywords WHERE normalizedKeyword = :normalized LIMIT 1")
    suspend fun getBlockedKeywordByNormalized(normalized: String): BlockedSearchKeyword?

    @Query("SELECT * FROM blocked_search_keywords WHERE id = :id LIMIT 1")
    suspend fun getBlockedKeywordById(id: Long): BlockedSearchKeyword?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedKeyword(keyword: BlockedSearchKeyword): Long

    @Update
    suspend fun updateBlockedKeyword(keyword: BlockedSearchKeyword)

    @Query("UPDATE blocked_search_keywords SET enabled = :enabled WHERE id = :id")
    suspend fun updateBlockedKeywordEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM blocked_search_keywords WHERE id = :id")
    suspend fun deleteBlockedKeywordById(id: Long)

    @Query("DELETE FROM blocked_search_keywords")
    suspend fun deleteAllBlockedKeywords()
}
