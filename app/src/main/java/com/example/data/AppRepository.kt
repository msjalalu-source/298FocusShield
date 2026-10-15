package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.bootstrap.CriticalProtectionState
import com.example.service.dpc.DeviceAdminManager
import com.example.service.TemporaryUnlockManager
import com.example.service.NewAppBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppRepository(private val appDao: AppDao, context: Context) {

    private val context: Context = context.applicationContext

    val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        repositoryScope.launch {
            ensureDefaultBlockedKeywords()
        }
    }

    val allAppsFlow: Flow<List<LockedApp>> = appDao.getAllAppsFlow().distinctUntilChanged()
    val allUnlockRequestsFlow: Flow<List<UnlockRequest>> = appDao.getAllUnlockRequestsFlow().distinctUntilChanged()
    val allSettingsFlow: Flow<List<AppSetting>> = appDao.getAllSettingsFlow().distinctUntilChanged()
    val allBlockedKeywordsFlow: Flow<List<BlockedSearchKeyword>> = appDao.getAllBlockedKeywordsFlow().distinctUntilChanged()
    val activeBlockedKeywordsFlow: Flow<List<BlockedSearchKeyword>> = appDao.getActiveBlockedKeywordsFlow().distinctUntilChanged()

    suspend fun getAllAppsList(): List<LockedApp> = appDao.getAllAppsList()

    // In-memory thread-safe caches for ultra-fast, zero-overhead lookups across services
    private val settingsCache get() = sharedSettingsCache
    private var cachedLockedPackages
        get() = sharedLockedPackages
        set(value) { sharedLockedPackages = value }

    private val fastSnapshotPrefs by lazy {
        getDeviceProtectedPrefs(context)
    }

    data class ProtectionSnapshot(
        val lockedApps: Map<String, String>, // pkg -> appName
        val knownApps: Set<String>,
        val settings: Map<String, String>,
        val blockedKeywords: List<BlockedSearchKeyword> = emptyList(),
        val isControlCenterLockEnabled: Boolean = false,
        val isControlCenterProtectionReady: Boolean = false,
        val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
        val createdAtMs: Long = 0L,
        val isStale: Boolean = false,
        val isValid: Boolean = true
    )

    fun loadFastProtectionSnapshot(): ProtectionSnapshot {
        return loadDeviceProtectedSnapshot(context)
    }

    fun saveFastProtectionSnapshot(
        lockedApps: Map<String, String>,
        knownApps: Set<String>,
        settings: Map<String, String>,
        blockedKeywords: List<BlockedSearchKeyword> = emptyList()
    ) {
        try {
            val lockedSet = lockedApps.map { "${it.key}|${it.value}" }.toSet()
            val keywordSet = blockedKeywords.map {
                "${it.id}|${it.keyword}|${it.normalizedKeyword}|${it.enabled}|${it.matchMode}"
            }.toSet()

            val editor = fastSnapshotPrefs.edit()
            editor.putInt("snap_schema_version", SNAPSHOT_SCHEMA_VERSION)
            editor.putLong("snap_created_at_ms", System.currentTimeMillis())
            editor.putStringSet("snap_locked_apps", lockedSet)
            editor.putStringSet("snap_known_apps", knownApps)
            if (keywordSet.isNotEmpty() || blockedKeywords.isEmpty()) {
                editor.putStringSet("snap_blocked_keywords", keywordSet)
            }

            // Save essential non-sensitive protection flags
            for ((k, v) in settings) {
                if (k != "pin_hash") {
                    editor.putString("setting_$k", v)
                }
            }

            // Sync Control Center Edit Lock to DE storage for Direct Boot & Fast Path
            val ccEditLocked = if (settings.containsKey(KEY_CONTROL_CENTER_EDIT_LOCKED)) {
                settings[KEY_CONTROL_CENTER_EDIT_LOCKED] == "true"
            } else {
                fastSnapshotPrefs.getBoolean("snap_control_center_edit_locked", false) ||
                        fastSnapshotPrefs.getString("setting_$KEY_CONTROL_CENTER_EDIT_LOCKED", "false") == "true"
            }
            editor.putBoolean("snap_control_center_edit_locked", ccEditLocked)

            editor.apply()

            // Update in-memory CriticalProtectionBootstrap state
            com.example.service.bootstrap.CriticalProtectionBootstrap.updateStateFromRepository(
                lockedApps = lockedApps,
                knownApps = knownApps,
                settings = settings,
                blockedKeywords = blockedKeywords
            )
        } catch (_: Exception) {}
    }

    suspend fun initializeDefaultsIfEmpty(): Unit = withContext(Dispatchers.IO) {
        // 1. Immediately refresh memory caches to restore protection state in ~2ms
        refreshMemoryCaches()

        // Safely clean up obsolete Master Password data without crashing
        try {
            appDao.deleteSettingByKey("pin_hash")
            settingsCache.remove("pin_hash")
            fastSnapshotPrefs.edit().remove("setting_pin_hash").apply()
        } catch (_: Exception) {}

        // 2. Initialize default app settings and reset legacy default ONs in a single batch
        val defaultsToSet = mutableMapOf<String, String>()
        if (getSetting(KEY_UNLOCK_DELAY_HOURS) == null || getSetting("delay_timer_12h_v1") == null) {
            defaultsToSet[KEY_UNLOCK_DELAY_HOURS] = "12" // Default 12 hours delayed unlock standard
            defaultsToSet["delay_timer_12h_v1"] = "true"
        }
        if (getSetting("defaults_reset_v4") == null) {
            defaultsToSet[KEY_CONTROL_CENTER_EDIT_LOCKED] = "false"
            defaultsToSet[KEY_NEW_INSTALL_APP_BLOCKED] = "true"
            defaultsToSet[KEY_UNINSTALL_PROTECTION] = "false"
            defaultsToSet["defaults_reset_v4"] = "true"
        }

        if (getSetting("search_blocking_switches_v2") == null) {
            defaultsToSet[KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED] = "false"
            defaultsToSet["search_blocking_switches_v2"] = "true"
        }
        if (getSetting(KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED) == null) {
            defaultsToSet[KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED] = "false"
        }
        if (getSetting(KEY_REMEMBER_PROTECTION_ENABLED) == null) {
            defaultsToSet[KEY_REMEMBER_PROTECTION_ENABLED] = "true"
        }

        if (defaultsToSet.isNotEmpty()) {
            setSettings(defaultsToSet)
        }

        // 3. Ensure default blocked keywords are populated (both fresh installs and existing installs)
        ensureDefaultBlockedKeywords()

        // 4. Ensure default remember protection configurations are populated (both fresh installs and existing installs)
        ensureDefaultRememberTargets()

        // 5. Scan installed applications (syncInstalledApps handles its own cache refresh)
        syncInstalledApps()

        // Asynchronously sync Device Admin policy in background IO without blocking boot/UI
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                DeviceAdminManager.syncAndVerifyPolicyState(context, this@AppRepository)
            } catch (e: Exception) {
                android.util.Log.e("AppRepository", "Error during background policy sync", e)
            }
        }
    }

    suspend fun syncDevicePolicies() = withContext(Dispatchers.IO) {
        try {
            DeviceAdminManager.syncAndVerifyPolicyState(context, this@AppRepository)
        } catch (e: Exception) {
            android.util.Log.e("AppRepository", "Error syncing device policies", e)
        }
    }

    suspend fun refreshMemoryCaches() = withContext(Dispatchers.IO) {
        val allApps = appDao.getAllAppsList()
        val lockMap = HashMap<String, String>(allApps.size)
        val knownSet = HashSet<String>(allApps.size)

        for (app in allApps) {
            knownSet.add(app.packageName)
            if (app.isAppLocked) lockMap[app.packageName] = app.appName
        }

        val settingsList = appDao.getAllSettingsList()
        val settingsMap = HashMap<String, String>(settingsList.size)
        for (s in settingsList) {
            settingsMap[s.key] = s.value
            settingsCache[s.key] = s.value
        }

        val keywords = try {
            appDao.getAllBlockedKeywordsList()
        } catch (_: Exception) {
            emptyList()
        }

        cachedLockedPackages = lockMap.keys.toSet()

        saveFastProtectionSnapshot(
            lockedApps = lockMap,
            knownApps = knownSet,
            settings = settingsMap,
            blockedKeywords = keywords
        )
    }

    fun isAppLockedCached(packageName: String): Boolean = cachedLockedPackages.contains(packageName)

    @Volatile private var lastInstalledAppsSyncTime = 0L

    suspend fun syncInstalledApps(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastInstalledAppsSyncTime > 0L && (now - lastInstalledAppsSyncTime < 15_000L)) {
            return@withContext
        }

        val pm = context.packageManager
        val installedAppsInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Single batch query for all launcher activities instead of per-app IPC calls
        val launchablePackages = try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            activities.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        packageSyncMutex.withLock {
            val currentDbApps = appDao.getAllAppsList().associateBy { it.packageName }
            val validInstalledPackages = mutableListOf<String>()
            val appsToInsert = mutableListOf<LockedApp>()

            for (info in installedAppsInfo) {
                // Skip our own app
                if (info.packageName == context.packageName) continue

                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isUserApp = !isSystem
                val isLaunchable = launchablePackages.contains(info.packageName)

                // Only include user-installed apps or launchable system apps
                if (!isUserApp && !isLaunchable) {
                    continue
                }

                validInstalledPackages.add(info.packageName)

                if (!currentDbApps.containsKey(info.packageName)) {
                    val appName = try {
                        pm.getApplicationLabel(info).toString()
                    } catch (e: Exception) {
                        info.packageName
                    }
                    appsToInsert.add(
                        LockedApp(
                            packageName = info.packageName,
                            appName = appName,
                            isAppLocked = false,
                            isSystemApp = isSystem
                        )
                    )
                }
            }

            if (appsToInsert.isNotEmpty()) {
                appDao.insertApps(appsToInsert)
            }

            lastInstalledAppsSyncTime = android.os.SystemClock.elapsedRealtime()

            // NOTE: We deliberately do NOT delete missing apps from Room database (deleteAppsNotIn is disabled)
            // to prevent wiping user-configured lock policies and delay timers during sync or temporary disablement.

            refreshMemoryCaches()
        }
    }

    /**
     * Non-destructive insert or update. If the app already exists in Room, its security policies,
     * lock states, and delay timers are strictly preserved.
     */
    suspend fun insertOrUpdateApp(app: LockedApp) = withContext(Dispatchers.IO) {
        val existing = appDao.getAppByPackage(app.packageName)
        if (existing == null) {
            appDao.insertApp(app)
        } else {
            val merged = existing.copy(
                appName = app.appName,
                isSystemApp = app.isSystemApp
            )
            appDao.updateApp(merged)
        }
        refreshMemoryCaches()
    }

    /**
     * Authoritative centralized method to handle newly installed packages.
     * Invoked by both PackageReceiver (broadcast) and FocusAccessibilityService (runtime fallback).
     *
     * Validates package, filters self/settings/system apps, enforces new-install security policies,
     * persists state to Room DB, and updates fast in-memory caches.
     */
    suspend fun handleNewlyInstalledPackage(
        packageName: String,
        forceAutoLock: Boolean = false
    ): LockedApp? = withContext(Dispatchers.IO) {
        if (packageName.isBlank() || packageName == context.packageName || NewAppBlockStore.isExemptPackage(packageName)) return@withContext null
        if (TemporaryUnlockManager.isSettingsPackage(packageName)) return@withContext null

        val pm = context.packageManager
        val (appName, isSystem) = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val name = pm.getApplicationLabel(appInfo).toString()
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            Pair(name, isSys)
        } catch (e: Exception) {
            val isSysFallback = packageName == "android" || packageName.startsWith("com.android.")
            Pair(packageName, isSysFallback)
        }

        packageSyncMutex.withLock {
            // Never auto-block system packages as a new install
            if (isSystem) {
                val existing = appDao.getAppByPackage(packageName)
                if (existing == null) {
                    val systemApp = LockedApp(
                        packageName = packageName,
                        appName = appName,
                        isAppLocked = false,
                        isSystemApp = true
                    )
                    appDao.insertApp(systemApp)
                    refreshMemoryCaches()
                }
                return@withContext null
            }

            val shouldLockApp = forceAutoLock

            val existing = appDao.getAppByPackage(packageName)
            if (existing != null) {
                val updated = existing.copy(
                    appName = if (existing.appName.isNotBlank() && existing.appName != packageName) existing.appName else appName,
                    isAppLocked = existing.isAppLocked || shouldLockApp,
                    isSystemApp = false
                )
                appDao.updateApp(updated)
                refreshMemoryCaches()
                return@withContext updated
            }

            // Brand new install: apply authoritative security policy
            val newApp = LockedApp(
                packageName = packageName,
                appName = appName,
                isAppLocked = shouldLockApp,
                isSystemApp = false
            )
            appDao.insertApp(newApp)
            refreshMemoryCaches()
            return@withContext newApp
        }
    }

    /**
     * Handler for package update / upgrade (ACTION_PACKAGE_REPLACED).
     * Strictly preserves existing lock, new-install block, and timer state.
     */
    suspend fun onPackageReplaced(packageName: String) = withContext(Dispatchers.IO) {
        if (packageName.isBlank() || packageName == context.packageName) return@withContext
        com.example.service.accessibility.handlers.AppLabelCache.remove(packageName)
        val pm = context.packageManager
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            appDao.updateAppMetadata(packageName, appName, isSys)
            com.example.service.accessibility.handlers.AppLabelCache.put(packageName, appName)
        } catch (_: Exception) {}
        refreshMemoryCaches()
    }

    /**
     * Handler for package uninstall (ACTION_PACKAGE_REMOVED).
     */
    suspend fun onPackageRemoved(packageName: String, isReplacing: Boolean) = withContext(Dispatchers.IO) {
        if (packageName.isBlank() || isReplacing) return@withContext
        com.example.service.accessibility.handlers.AppLabelCache.remove(packageName)
        appDao.deleteAppByPackage(packageName)
        refreshMemoryCaches()
    }

    /**
     * Secondary runtime safety net called by AccessibilityService when an unmonitored window is detected.
     * Routes through centralized handleNewlyInstalledPackage logic.
     */
    suspend fun onPackageDiscoveredRuntime(packageName: String): LockedApp? = withContext(Dispatchers.IO) {
        handleNewlyInstalledPackage(packageName)
    }

    /**
     * Centralized security decision evaluating active protection policies for an application.
     */
    suspend fun shouldBlock(packageName: String): Boolean = withContext(Dispatchers.IO) {
        shouldBlockFast(packageName)
    }

    /**
     * Fast in-memory evaluation for launch path checks.
     */
    fun shouldBlockFast(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        return NewAppBlockStore.isBlocked(packageName)
    }

    // Schedule Delayed Unlock Request (Standard 12h Delay)
    suspend fun createUnlockRequest(targetType: String, targetKey: String): UnlockRequest = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val unlockAt = now + (DELAYED_UNLOCK_HOURS.toLong() * 3600 * 1000)
        val request = UnlockRequest(
            targetType = targetType,
            targetKey = targetKey,
            requestedAt = now,
            unlockAtTime = unlockAt,
            unlockDurationHours = DELAYED_UNLOCK_HOURS,
            status = "PENDING"
        )
        val generatedId = appDao.insertUnlockRequest(request).toInt()
        request.copy(id = generatedId)
    }

    suspend fun createUnlockRequests(items: List<Pair<String, String>>): List<UnlockRequest> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val unlockAt = now + (DELAYED_UNLOCK_HOURS.toLong() * 3600 * 1000)
        val requests = items.map { (targetType, targetKey) ->
            UnlockRequest(
                targetType = targetType,
                targetKey = targetKey,
                requestedAt = now,
                unlockAtTime = unlockAt,
                unlockDurationHours = DELAYED_UNLOCK_HOURS,
                status = "PENDING"
            )
        }
        val generatedIds = appDao.insertUnlockRequests(requests)
        requests.mapIndexed { index, req -> req.copy(id = generatedIds[index].toInt()) }
    }

    suspend fun checkAndProcessUnlockRequests() = withContext(Dispatchers.IO) {
        val pending = appDao.getPendingUnlockRequests()
        val now = System.currentTimeMillis()
        var changed = false
        for (req in pending) {
            if (now >= req.unlockAtTime) {
                // Execute unlock action!
                when (req.targetType) {
                    "NEW_INSTALL_APP" -> NewAppBlockStore.removeApp(context, req.targetKey)
                    "NEW_INSTALL_APP_BLOCK" -> setSetting(KEY_NEW_INSTALL_APP_BLOCKED, "false")
                    "CONTROL_CENTER_EDIT_LOCK", "CONTROL_CENTER_EDIT_LOCKED" -> setSetting(KEY_CONTROL_CENTER_EDIT_LOCKED, "false")
                    "UNINSTALL_PROTECTION" -> {
                        setSetting(KEY_UNINSTALL_PROTECTION, "false")
                        DeviceAdminManager.deactivateAdmin(context)
                    }
                    "SEARCH_BLOCKING_YOUTUBE", "SEARCH_BLOCKING_YOUTUBE_ENABLED" -> setSetting(KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED, "false")
                    "SEARCH_BLOCKING_NEWPIPE", "SEARCH_BLOCKING_NEWPIPE_ENABLED" -> {
                        // Obsolete NewPipe search blocking request - safely complete without action
                    }
                    "SEARCH_KEYWORD", "BLOCKED_KEYWORD" -> {
                        val keywordId = req.targetKey.toLongOrNull()
                        if (keywordId != null) {
                            setBlockedKeywordEnabled(keywordId, false)
                        }
                    }
                    "REMEMBER_TARGET_DELETE", "REMEMBER_PATH_DELETE" -> {
                        com.example.service.safety.RememberProtectionEngine.deleteTarget(context, req.targetKey, this@AppRepository)
                        val remainingTargets = com.example.service.safety.RememberProtectionEngine.getSavedTargets(context)
                        val encoded = com.example.service.safety.RememberProtectionEngine.encodeTargets(remainingTargets)
                        setSetting(KEY_SAVED_PROTECTION_TARGET, encoded)
                    }
                    "REMEMBER_PROTECTION_MASTER" -> {
                        setSetting(KEY_REMEMBER_PROTECTION_ENABLED, "false")
                    }
                    else -> {
                        when (req.targetKey) {
                            KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED -> setSetting(KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED, "false")
                            KEY_REMEMBER_PROTECTION_ENABLED -> setSetting(KEY_REMEMBER_PROTECTION_ENABLED, "false")
                        }
                    }
                }
                appDao.updateUnlockRequest(req.copy(status = "COMPLETED"))
                changed = true
            }
        }
        if (changed) {
            refreshMemoryCaches()
        }
    }

    suspend fun deleteUnlockRequest(id: Int) = withContext(Dispatchers.IO) {
        appDao.deleteUnlockRequest(id)
    }

    // Setting Helpers with In-Memory Cache
    suspend fun getSetting(key: String): String? = withContext(Dispatchers.IO) {
        sharedSettingsCache[key]
            ?: com.example.service.bootstrap.CriticalProtectionBootstrap.getSetting(key)
            ?: appDao.getSettingValue(key)?.also { sharedSettingsCache[key] = it }
    }

    fun getSettingDirect(key: String): String? =
        sharedSettingsCache[key] ?: com.example.service.bootstrap.CriticalProtectionBootstrap.getSetting(key)

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        val existing = sharedSettingsCache[key]
            ?: com.example.service.bootstrap.CriticalProtectionBootstrap.getSetting(key)
            ?: appDao.getSettingValue(key)?.also { sharedSettingsCache[key] = it }
        if (existing == value) {
            return@withContext
        }
        sharedSettingsCache[key] = value
        appDao.insertSetting(AppSetting(key, value))
        try {
            val editor = fastSnapshotPrefs.edit()
            editor.putString("setting_$key", value)
            if (key == KEY_CONTROL_CENTER_EDIT_LOCKED) {
                editor.putBoolean("snap_control_center_edit_locked", value == "true")
            }
            if (key == KEY_NEW_INSTALL_APP_BLOCKED) {
                NewAppBlockStore.setFeatureEnabled(context, value == "true")
            }
            editor.apply()
        } catch (_: Exception) {}
        com.example.service.bootstrap.CriticalProtectionBootstrap.updateSetting(key, value)
    }

    suspend fun setSettings(settings: Map<String, String>) = withContext(Dispatchers.IO) {
        if (settings.isEmpty()) return@withContext
        val changedSettings = settings.filter { (k, v) ->
            val existing = sharedSettingsCache[k]
                ?: com.example.service.bootstrap.CriticalProtectionBootstrap.getSetting(k)
                ?: appDao.getSettingValue(k)?.also { sharedSettingsCache[k] = it }
            existing != v
        }
        if (changedSettings.isEmpty()) return@withContext

        for ((k, v) in changedSettings) {
            sharedSettingsCache[k] = v
        }
        appDao.insertSettings(changedSettings.map { AppSetting(it.key, it.value) })
        try {
            val editor = fastSnapshotPrefs.edit()
            for ((k, v) in changedSettings) {
                editor.putString("setting_$k", v)
                if (k == KEY_CONTROL_CENTER_EDIT_LOCKED) {
                    editor.putBoolean("snap_control_center_edit_locked", v == "true")
                }
                if (k == KEY_NEW_INSTALL_APP_BLOCKED) {
                    NewAppBlockStore.setFeatureEnabled(context, v == "true")
                }
            }
            editor.apply()
        } catch (_: Exception) {}
        for ((k, v) in changedSettings) {
            com.example.service.bootstrap.CriticalProtectionBootstrap.updateSetting(k, v)
        }
    }

    suspend fun requestUnblockNewInstallApp(packageName: String) = withContext(Dispatchers.IO) {
        val pendingRequests = appDao.getPendingUnlockRequests()
        val existing = pendingRequests.find { it.targetType == "NEW_INSTALL_APP" && it.targetKey == packageName }
        if (existing == null) {
            createUnlockRequest("NEW_INSTALL_APP", packageName)
        }
    }

    val blockedNewAppsFlow: kotlinx.coroutines.flow.Flow<Set<String>> = NewAppBlockStore.blockedAppsFlow

    suspend fun unblockNewInstallApp(packageName: String): Unit = withContext(Dispatchers.IO) {
        // Direct unblock bypass strictly closed: enforces standard 12-hour delayed unlock
        requestUnblockNewInstallApp(packageName)
    }

    // Blocked Search Keywords management
    suspend fun getAllBlockedKeywords(): List<BlockedSearchKeyword> = withContext(Dispatchers.IO) {
        try {
            appDao.getAllBlockedKeywordsList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getActiveBlockedKeywords(): List<BlockedSearchKeyword> = withContext(Dispatchers.IO) {
        try {
            appDao.getActiveBlockedKeywordsList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addBlockedKeyword(
        keyword: String,
        matchMode: String = "CONTAINS"
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val trimmed = keyword.trim()
            val normalized = com.example.service.search.KeywordNormalizer.normalize(trimmed)
            if (normalized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Keyword cannot be empty"))
            }
            val existing = appDao.getBlockedKeywordByNormalized(normalized)
            if (existing != null) {
                return@withContext Result.failure(IllegalStateException("Keyword already exists"))
            }
            val entity = BlockedSearchKeyword(
                keyword = trimmed,
                normalizedKeyword = normalized,
                enabled = true,
                matchMode = matchMode,
                createdAt = System.currentTimeMillis()
            )
            val id = appDao.insertBlockedKeyword(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateBlockedKeyword(
        id: Long,
        keyword: String,
        matchMode: String,
        enabled: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val trimmed = keyword.trim()
            val normalized = com.example.service.search.KeywordNormalizer.normalize(trimmed)
            if (normalized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Keyword cannot be empty"))
            }
            val existing = appDao.getBlockedKeywordByNormalized(normalized)
            if (existing != null && existing.id != id) {
                return@withContext Result.failure(IllegalStateException("Another keyword with the same text already exists"))
            }
            val entity = BlockedSearchKeyword(
                id = id,
                keyword = trimmed,
                normalizedKeyword = normalized,
                enabled = enabled,
                matchMode = matchMode,
                createdAt = System.currentTimeMillis()
            )
            appDao.updateBlockedKeyword(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setBlockedKeywordEnabled(id: Long, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = appDao.getBlockedKeywordById(id)
            if (existing != null && existing.enabled == enabled) {
                return@withContext Result.success(Unit)
            }
            appDao.updateBlockedKeywordEnabled(id, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBlockedKeyword(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            appDao.deleteUnlockRequestsByTargetKeyOrType(id.toString(), "SEARCH_KEYWORD")
            appDao.deleteBlockedKeywordById(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAllBlockedKeywords(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val allKeywords = appDao.getAllBlockedKeywordsList()
            for (kw in allKeywords) {
                appDao.deleteUnlockRequestsByTargetKeyOrType(kw.id.toString(), "SEARCH_KEYWORD")
            }
            appDao.deleteAllBlockedKeywords()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Automatically ensures all 16 required default blocked keywords/patterns are present in the database.
     * Idempotent & non-destructive:
     * - Checks existing keywords by normalized text to prevent duplicates
     * - Preserves user-added keywords untouched
     * - Only inserts missing default items
     */
    suspend fun ensureDefaultBlockedKeywords(): Unit = withContext(Dispatchers.IO) {
        try {
            val existingList = appDao.getAllBlockedKeywordsList()
            val existingNormalized = existingList.map { it.normalizedKeyword }.toSet()

            var insertedAny = false
            for (item in DEFAULT_BLOCKED_ITEMS) {
                val trimmed = item.trim()
                val normalized = com.example.service.search.KeywordNormalizer.normalize(trimmed)
                if (normalized.isNotEmpty() && !existingNormalized.contains(normalized)) {
                    val entity = BlockedSearchKeyword(
                        keyword = trimmed,
                        normalizedKeyword = normalized,
                        enabled = true,
                        matchMode = "CONTAINS",
                        createdAt = System.currentTimeMillis()
                    )
                    appDao.insertBlockedKeyword(entity)
                    insertedAny = true
                }
            }

            if (insertedAny) {
                refreshMemoryCaches()
            }
        } catch (e: Exception) {
            android.util.Log.e("AppRepository", "Error ensuring default blocked keywords", e)
        }
    }

    /**
     * Automatically ensures all 7 required default Remember Protection configurations are present.
     * Idempotent & non-destructive:
     * - Checks existing configurations to prevent duplicates
     * - Preserves user-added configurations untouched
     * - Only inserts missing default items
     */
    suspend fun ensureDefaultRememberTargets(): Unit = withContext(Dispatchers.IO) {
        try {
            val raw = getSetting(KEY_SAVED_PROTECTION_TARGET)
                ?: com.example.service.bootstrap.CriticalProtectionBootstrap.getSetting(KEY_SAVED_PROTECTION_TARGET)
                ?: getDeviceProtectedPrefs(context).getString("setting_$KEY_SAVED_PROTECTION_TARGET", "")
                ?: ""

            val existingTargets = com.example.service.safety.RememberProtectionEngine.decodeTargets(raw).toMutableList()
            var updated = false

            for (defaultTarget in DEFAULT_REMEMBER_TARGETS) {
                val existingIndex = existingTargets.indexOfFirst { existing ->
                    (existing.id.isNotEmpty() && existing.id == defaultTarget.id) ||
                    (existing.packageName.trim().equals(defaultTarget.packageName.trim(), ignoreCase = true) &&
                    existing.navigationPath.trim().equals(defaultTarget.navigationPath.trim(), ignoreCase = true))
                }
                if (existingIndex < 0) {
                    existingTargets.add(defaultTarget)
                    updated = true
                } else {
                    val existing = existingTargets[existingIndex]
                    if (existing.id.startsWith("default_remember_target_") &&
                        (existing.eventType != defaultTarget.eventType || existing.navigationPath != defaultTarget.navigationPath)) {
                        existingTargets[existingIndex] = existing.copy(
                            eventType = defaultTarget.eventType,
                            navigationPath = defaultTarget.navigationPath
                        )
                        updated = true
                    }
                }
            }

            if (updated) {
                val encoded = com.example.service.safety.RememberProtectionEngine.encodeTargets(existingTargets)
                setSetting(KEY_SAVED_PROTECTION_TARGET, encoded)
                com.example.service.safety.RememberProtectionEngine.saveAllTargets(
                    context = context,
                    targets = existingTargets,
                    repository = this@AppRepository
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AppRepository", "Error ensuring default remember targets", e)
        }
    }

    companion object {
        val DEFAULT_BLOCKED_ITEMS: List<String> = listOf(
            "Aashiq Banaya",
            "hot",
            "adult",
            "porn",
            "sex",
            "x",
            "xxx",
            "18+",
            "intimate",
            "kiss",
            "v=0bAVd9jJE2Q&vl=en",
            "v=19E65tOn3tI",
            "v=kbObKrBoIjI",
            "v=3QCgD4R4Ly8",
            "v=RlbGLrMuZ5I",
            "v=7vBsI0Hv5L0"
        )

        val DEFAULT_REMEMBER_TARGETS: List<com.example.service.safety.SavedProtectionTarget> = listOf(
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "edit",
                packageName = "miui.systemui.plugin",
                eventType = "Type View Click",
                id = "default_remember_target_1"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "focus shield",
                packageName = "com.miui.home",
                eventType = "Type View Long Click",
                id = "default_remember_target_2"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "battery",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_3"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "Set up portable hotspot",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_4"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "Focus shield",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_5"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "settings",
                packageName = "com.miui.securitycenter",
                eventType = "Type View Click",
                id = "default_remember_target_6"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "privacy",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_7"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "style",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_8"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "apps",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_9"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "background",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_10"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "Home screen",
                packageName = "com.android.settings",
                eventType = "Type View Click",
                id = "default_remember_target_11"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "Manage apps",
                packageName = "com.miui.securitycenter",
                eventType = "TYPE_VIEW_CLICKED",
                id = "default_remember_target_12"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "Battery",
                packageName = "com.miui.securitycenter",
                eventType = "TYPE_VIEW_CLICKED",
                id = "default_remember_target_13"
            ),
            com.example.service.safety.SavedProtectionTarget(
                navigationPath = "DNS",
                packageName = "com.android.settings",
                eventType = "TYPE_VIEW_CLICKED",
                id = "default_remember_target_14"
            )
        )

        private val packageSyncMutex = Mutex()

        private val sharedSettingsCache = ConcurrentHashMap<String, String>()
        @Volatile private var sharedLockedPackages: Set<String> = emptySet()

        @Volatile private var INSTANCE: AppRepository? = null

        fun getInstance(context: Context): AppRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context.applicationContext)
                INSTANCE ?: AppRepository(db.appDao(), context.applicationContext).also { INSTANCE = it }
            }
        }

        fun updateSharedSettingCache(key: String, value: String) {
            sharedSettingsCache[key] = value
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            sharedSettingsCache.clear()
            sharedLockedPackages = emptySet()
            INSTANCE = null
        }

        const val DELAYED_UNLOCK_HOURS = 12
        const val KEY_UNLOCK_DELAY_HOURS = "unlock_delay_hours"
        const val KEY_CONTROL_CENTER_EDIT_LOCKED = "control_center_edit_locked"
        const val KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED = "search_blocking_youtube_enabled"
        const val KEY_NEW_INSTALL_APP_BLOCKED = "new_install_app_blocked"
        const val KEY_UNINSTALL_PROTECTION = "uninstall_protection"
        const val KEY_REMEMBER_PROTECTION_ENABLED = "remember_protection_enabled"
        const val KEY_SAVED_PROTECTION_TARGET = "saved_protection_target"
        const val KEY_PROTECTION_ENABLED = "protection_enabled"

        const val SNAPSHOT_SCHEMA_VERSION = 2
        private const val SNAPSHOT_STALE_THRESHOLD_MS = 30L * 24 * 3600 * 1000L

        fun getDeviceProtectedPrefs(context: Context): android.content.SharedPreferences {
            val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    context.createDeviceProtectedStorageContext()
                } catch (_: Exception) {
                    context
                }
            } else {
                context
            }
            return storageContext.getSharedPreferences("focus_guard_fast_snapshot", Context.MODE_PRIVATE)
        }

        /**
         * Level A: Minimal Critical Snapshot.
         * Loads ONLY the critical fields needed before AccessibilityService handlers connect.
         * Strictly avoids:
         * - prefs.all
         * - iterating/parsing large string sets
         * - Room/SQLite access
         * - synchronous SharedPreferences writes (apply/commit)
         */
        fun loadMinimalCriticalSnapshot(context: Context): CriticalProtectionState {
            return try {
                val prefs = getDeviceProtectedPrefs(context)

                val ccEditLocked = prefs.getBoolean("snap_control_center_edit_locked", false) ||
                        prefs.getString("setting_$KEY_CONTROL_CENTER_EDIT_LOCKED", "false") == "true"

                val isProtectionEnabled = prefs.getString("setting_$KEY_PROTECTION_ENABLED", "true") != "false"
                val newInstallAppBlocked = true

                val rawKnown = prefs.getStringSet("snap_known_apps", emptySet()) ?: emptySet()
                val knownApps = rawKnown.filter { it.isNotBlank() }.toSet()

                val rawLocked = prefs.getStringSet("snap_locked_apps", emptySet()) ?: emptySet()
                val lockedApps = rawLocked.mapNotNull { entry ->
                    val parts = entry.split("|", limit = 2)
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        parts[0] to (if (parts.size > 1) parts[1] else "")
                    } else null
                }.toMap()

                val savedTargetsRaw = prefs.getString("setting_$KEY_SAVED_PROTECTION_TARGET", "") ?: ""

                val minimalSettings = HashMap<String, String>(8).apply {
                    put(KEY_PROTECTION_ENABLED, isProtectionEnabled.toString())
                    put(KEY_CONTROL_CENTER_EDIT_LOCKED, ccEditLocked.toString())
                    put(KEY_NEW_INSTALL_APP_BLOCKED, "true")
                    if (savedTargetsRaw.isNotBlank()) {
                        put(KEY_SAVED_PROTECTION_TARGET, savedTargetsRaw)
                    }
                }

                CriticalProtectionState(
                    readinessState = com.example.service.bootstrap.CriticalReadinessState.READY,
                    controlCenterLockEnabled = ccEditLocked,
                    controlCenterProtectionReady = true,
                    isProtectionEnabled = isProtectionEnabled,
                    lockedApps = lockedApps,
                    knownApps = knownApps,
                    blockedKeywords = emptyList(),
                    settings = minimalSettings,
                    isValid = true
                )
            } catch (e: Exception) {
                CriticalProtectionState(
                    readinessState = com.example.service.bootstrap.CriticalReadinessState.READY,
                    controlCenterLockEnabled = false,
                    controlCenterProtectionReady = true,
                    isProtectionEnabled = true,
                    isValid = false
                )
            }
        }

        /**
         * Level B: Full Protection Snapshot.
         * Loads locked apps, known apps, blocked new installs, and all settings.
         * Any maintenance operations (schema migration, expired timer cleanup) run here in background.
         */
        fun loadFullProtectionSnapshot(context: Context, performMaintenanceWrites: Boolean = true): ProtectionSnapshot {
            return try {
                val prefs = getDeviceProtectedPrefs(context)
                val storedSchema = prefs.getInt("snap_schema_version", 1)
                val createdAt = prefs.getLong("snap_created_at_ms", 0L)
                val now = System.currentTimeMillis()

                // Schema migration support: auto-upgrade v1 to v2 schema
                if (performMaintenanceWrites && storedSchema < SNAPSHOT_SCHEMA_VERSION) {
                    try {
                        prefs.edit()
                            .putInt("snap_schema_version", SNAPSHOT_SCHEMA_VERSION)
                            .putLong("snap_created_at_ms", if (createdAt > 0L) createdAt else now)
                            .apply()
                    } catch (_: Exception) {}
                }

                val lockedSet = prefs.getStringSet("snap_locked_apps", emptySet()) ?: emptySet()
                val lockedMap = HashMap<String, String>(lockedSet.size)
                for (entry in lockedSet) {
                    if (entry.isBlank()) continue
                    val idx = entry.indexOf('|')
                    if (idx != -1) {
                        val pkg = entry.substring(0, idx).trim()
                        val name = entry.substring(idx + 1).trim()
                        if (pkg.isNotEmpty()) {
                            lockedMap[pkg] = if (name.isNotEmpty()) name else pkg
                        }
                    } else {
                        val pkg = entry.trim()
                        if (pkg.isNotEmpty()) {
                            lockedMap[pkg] = pkg
                        }
                    }
                }

                val rawKnownApps = prefs.getStringSet("snap_known_apps", emptySet()) ?: emptySet()
                val knownApps = rawKnownApps.filter { it.isNotBlank() }.toSet()

                val settingsMap = HashMap<String, String>()
                val allPrefs = prefs.all
                for ((key, value) in allPrefs) {
                    if (key.startsWith("setting_") && value is String) {
                        settingsMap[key.removePrefix("setting_")] = value
                    }
                }

                val isStale = createdAt > 0L && (now - createdAt > SNAPSHOT_STALE_THRESHOLD_MS)

                val ccEditLocked = settingsMap[KEY_CONTROL_CENTER_EDIT_LOCKED] == "true" ||
                        prefs.getBoolean("snap_control_center_edit_locked", false)

                val rawKeywords = prefs.getStringSet("snap_blocked_keywords", emptySet()) ?: emptySet()
                val keywordList = mutableListOf<BlockedSearchKeyword>()
                for (raw in rawKeywords) {
                    val parts = raw.split('|')
                    if (parts.size >= 5) {
                        val id = parts[0].toLongOrNull() ?: 0L
                        val kw = parts[1]
                        val norm = parts[2]
                        val enabled = parts[3].toBooleanStrictOrNull() ?: true
                        val mode = parts[4]
                        keywordList.add(
                            BlockedSearchKeyword(
                                id = id,
                                keyword = kw,
                                normalizedKeyword = norm,
                                enabled = enabled,
                                matchMode = mode
                            )
                        )
                    }
                }

                ProtectionSnapshot(
                    lockedApps = lockedMap,
                    knownApps = knownApps,
                    settings = settingsMap,
                    blockedKeywords = keywordList,
                    isControlCenterLockEnabled = ccEditLocked,
                    isControlCenterProtectionReady = true,
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    createdAtMs = createdAt,
                    isStale = isStale,
                    isValid = true
                )
            } catch (_: Exception) {
                ProtectionSnapshot(
                    lockedApps = emptyMap(),
                    knownApps = emptySet(),
                    settings = emptyMap(),
                    isControlCenterLockEnabled = false,
                    isControlCenterProtectionReady = false,
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    isValid = false
                )
            }
        }

        fun loadDeviceProtectedSnapshot(context: Context): ProtectionSnapshot {
            return loadFullProtectionSnapshot(context, performMaintenanceWrites = false)
        }
    }
}

