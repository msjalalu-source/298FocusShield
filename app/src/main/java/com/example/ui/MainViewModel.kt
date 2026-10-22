package com.example.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ScreenDestination {
    TABS,
    REMEMBER_PROTECTION,
    SEARCH_KEYWORDS,
    NEW_INSTALL_APP_BLOCK,
    MANUAL_APP_BLOCK
}

@Immutable
data class MainUiState(
    val apps: List<LockedApp> = emptyList(),
    val unlockRequests: List<UnlockRequest> = emptyList(),
    val settingsMap: Map<String, String> = emptyMap(),
    val blockedKeywords: List<BlockedSearchKeyword> = emptyList(),
    val blockedNewApps: Set<String> = emptySet(),
    val isAccessibilityEnabled: Boolean = false,
    val isAccessibilityServiceAlive: Boolean = false,
    val protectionReadinessState: com.example.service.bootstrap.CriticalReadinessState = com.example.service.bootstrap.CriticalReadinessState.NOT_READY,
    val isDeviceAdminActive: Boolean = false,
    val searchQuery: String = "",
    val activeTab: Int = 0,
    val currentScreen: ScreenDestination = ScreenDestination.TABS,
    val pendingUnlockDialogTarget: Pair<String, String>? = null, // (Type, Key)
    val selectedRememberTargetId: String? = null
)

private data class UiStateCore(
    val searchQuery: String,
    val activeTab: Int,
    val currentScreen: ScreenDestination,
    val pendingUnlockTarget: Pair<String, String>?,
    val isAccessibilityEnabled: Boolean,
    val isAccessibilityServiceAlive: Boolean,
    val protectionReadinessState: com.example.service.bootstrap.CriticalReadinessState,
    val isDeviceAdminActive: Boolean,
    val selectedRememberTargetId: String?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    private val _activeTab = MutableStateFlow(0)
    private val _currentScreen = MutableStateFlow(ScreenDestination.TABS)
    private val _pendingUnlockTarget = MutableStateFlow<Pair<String, String>?>(null)
    private val _selectedRememberTargetId = MutableStateFlow<String?>(null)
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    private val _isAccessibilityServiceAlive = MutableStateFlow(false)
    private val _protectionReadinessState = MutableStateFlow(com.example.service.bootstrap.CriticalReadinessState.NOT_READY)
    private val _isDeviceAdminActive = MutableStateFlow(com.example.service.dpc.DeviceAdminManager.isAdminActive(application))

    // 1. Cached Settings Map - Only recomputes when settings table emits and values change
    private val _settingsMapFlow: Flow<Map<String, String>> = repository.allSettingsFlow
        .map { list -> list.associate { it.key to it.value } }
        .distinctUntilChanged()

    // 2. Pending Unlock Requests - Only re-filters when unlock requests table changes
    private val _pendingUnlockRequestsFlow: Flow<List<UnlockRequest>> = repository.allUnlockRequestsFlow
        .map { list -> list.filter { it.status == "PENDING" } }
        .distinctUntilChanged()

    // 3. Filtered Apps - Only re-filters when apps or search query changes
    // Debounce search query by 150ms during typing to eliminate unnecessary list re-filtering jank.
    // When the query is cleared (empty string), apply instantly (0ms) to restore full list without delay.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val _debouncedSearchQuery: Flow<String> = _searchQuery
        .debounce { query -> if (query.isEmpty()) 0L else 150L }
        .distinctUntilChanged()

    private val _filteredAppsFlow: Flow<List<LockedApp>> = combine(
        repository.allAppsFlow,
        _debouncedSearchQuery
    ) { apps, query ->
        val search = query.trim()
        val ownPackage = getApplication<Application>().packageName
        val visible = apps.filter { it.packageName != ownPackage }
        if (search.isEmpty()) {
            visible
        } else {
            visible.filter {
                it.appName.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
            }
        }
    }.distinctUntilChanged()

    private data class NavCore(
        val searchQuery: String,
        val activeTab: Int,
        val currentScreen: ScreenDestination,
        val pendingTarget: Pair<String, String>?,
        val selectedRememberTargetId: String?
    )

    private val _navCoreFlow: Flow<NavCore> = combine(
        _searchQuery,
        _activeTab,
        _currentScreen,
        _pendingUnlockTarget,
        _selectedRememberTargetId
    ) { search, tab, screen, pendingTarget, selectedTargetId ->
        NavCore(search, tab, screen, pendingTarget, selectedTargetId)
    }.distinctUntilChanged()

    private data class SystemStatusCore(
        val isAccessibilityEnabled: Boolean,
        val isAccessibilityServiceAlive: Boolean,
        val protectionReadinessState: com.example.service.bootstrap.CriticalReadinessState,
        val isDeviceAdminActive: Boolean
    )

    private val _systemStatusFlow: Flow<SystemStatusCore> = combine(
        _isAccessibilityEnabled,
        _isAccessibilityServiceAlive,
        _protectionReadinessState,
        _isDeviceAdminActive
    ) { accessEnabled, serviceAlive, readiness, adminActive ->
        SystemStatusCore(
            isAccessibilityEnabled = accessEnabled,
            isAccessibilityServiceAlive = serviceAlive,
            protectionReadinessState = readiness,
            isDeviceAdminActive = adminActive
        )
    }.distinctUntilChanged()

    // 4. Navigation and UI status core flow
    private val _uiCoreFlow: Flow<UiStateCore> = combine(
        _navCoreFlow,
        _systemStatusFlow
    ) { nav, sys ->
        UiStateCore(
            searchQuery = nav.searchQuery,
            activeTab = nav.activeTab,
            currentScreen = nav.currentScreen,
            pendingUnlockTarget = nav.pendingTarget,
            isAccessibilityEnabled = sys.isAccessibilityEnabled,
            isAccessibilityServiceAlive = sys.isAccessibilityServiceAlive,
            protectionReadinessState = sys.protectionReadinessState,
            isDeviceAdminActive = sys.isDeviceAdminActive,
            selectedRememberTargetId = nav.selectedRememberTargetId
        )
    }.distinctUntilChanged()

    private val _dataFlow = combine(
        _filteredAppsFlow,
        _pendingUnlockRequestsFlow,
        _settingsMapFlow
    ) { apps, pendingRequests, settingsMap ->
        Triple(apps, pendingRequests, settingsMap)
    }

    private val _protectionSubFlow = combine(
        repository.allBlockedKeywordsFlow,
        repository.blockedNewAppsFlow
    ) { keywords, newApps ->
        keywords to newApps
    }

    val uiState: StateFlow<MainUiState> = combine(
        _dataFlow,
        _protectionSubFlow,
        _uiCoreFlow
    ) { (apps, pendingRequests, settingsMap), (blockedKeywords, blockedNewApps), core ->
        MainUiState(
            apps = apps,
            unlockRequests = pendingRequests,
            settingsMap = settingsMap,
            blockedKeywords = blockedKeywords,
            blockedNewApps = blockedNewApps,
            isAccessibilityEnabled = core.isAccessibilityEnabled,
            isAccessibilityServiceAlive = core.isAccessibilityServiceAlive,
            protectionReadinessState = core.protectionReadinessState,
            isDeviceAdminActive = core.isDeviceAdminActive,
            searchQuery = core.searchQuery,
            activeTab = core.activeTab,
            currentScreen = core.currentScreen,
            pendingUnlockDialogTarget = core.pendingUnlockTarget,
            selectedRememberTargetId = core.selectedRememberTargetId
        )
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun navigateTo(destination: ScreenDestination) {
        _currentScreen.value = destination
    }

    fun navigateToRememberTarget(targetId: String) {
        _selectedRememberTargetId.value = targetId
        _currentScreen.value = ScreenDestination.REMEMBER_PROTECTION
    }

    fun clearSelectedRememberTarget() {
        _selectedRememberTargetId.value = null
    }

    fun navigateBack() {
        _selectedRememberTargetId.value = null
        _currentScreen.value = ScreenDestination.TABS
    }

    init {
        refreshAccessibilityStatus()
        refreshDeviceAdminStatus()
        viewModelScope.launch {
            repository.initializeDefaultsIfEmpty()
            repository.checkAndProcessUnlockRequests()
        }
        viewModelScope.launch {
            _pendingUnlockRequestsFlow.collectLatest { pendingList ->
                if (pendingList.isEmpty()) {
                    return@collectLatest
                }
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val dueList = pendingList.filter { it.unlockAtTime <= now }
                    if (dueList.isNotEmpty()) {
                        try {
                            repository.checkAndProcessUnlockRequests()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val remaining = pendingList.filter { it.unlockAtTime > now }
                    if (remaining.isEmpty()) {
                        break
                    }
                    val minWait = remaining.minOf { it.unlockAtTime - now }
                    kotlinx.coroutines.delay(minWait.coerceIn(500L, 60_000L))
                }
            }
        }
    }

    fun checkAndProcessUnlockRequests() {
        viewModelScope.launch {
            repository.checkAndProcessUnlockRequests()
        }
    }

    fun refreshAccessibilityStatus() {
        val app = getApplication<Application>()
        val settingEnabled = com.example.service.StartupCapabilityDetector.isAccessibilitySettingEnabled(app)
        val serviceAlive = com.example.service.StartupCapabilityDetector.isAccessibilityServiceAlive()
        val readiness = com.example.service.StartupCapabilityDetector.getProtectionReadinessState(app)

        _isAccessibilityEnabled.value = settingEnabled
        _isAccessibilityServiceAlive.value = serviceAlive
        _protectionReadinessState.value = readiness
    }

    fun refreshDeviceAdminStatus() {
        val app = getApplication<Application>()
        val isActive = com.example.service.dpc.DeviceAdminManager.isAdminActive(app)
        _isDeviceAdminActive.value = isActive
        viewModelScope.launch {
            try {
                val currentSetting = repository.getSetting(AppRepository.KEY_UNINSTALL_PROTECTION)
                if (isActive && currentSetting != "true") {
                    repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "true")
                } else if (!isActive && currentSetting == "true") {
                    repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "false")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setUninstallProtection(enabled: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (enabled) {
                repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "true")
                _isDeviceAdminActive.value = com.example.service.dpc.DeviceAdminManager.isAdminActive(app)
            } else {
                repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "false")
                com.example.service.dpc.DeviceAdminManager.deactivateAdmin(app)
                _isDeviceAdminActive.value = com.example.service.dpc.DeviceAdminManager.isAdminActive(app)
            }
        }
    }

    fun toggleUninstallProtection(currentState: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val existing = uiState.value.unlockRequests.find {
                it.targetType == "UNINSTALL_PROTECTION" || it.targetKey == AppRepository.KEY_UNINSTALL_PROTECTION
            }
            if (currentState) {
                // Disabling requires standard 12-hour delayed unlock
                if (existing == null) {
                    repository.createUnlockRequest("UNINSTALL_PROTECTION", AppRepository.KEY_UNINSTALL_PROTECTION)
                }
            } else {
                // Enabling is instant; cancel any pending unlock request
                if (existing != null) {
                    repository.deleteUnlockRequest(existing.id)
                }
                if (com.example.service.dpc.DeviceAdminManager.isAdminActive(app)) {
                    repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "true")
                    _isDeviceAdminActive.value = true
                }
            }
        }
    }

    private var lastInstalledAppsSyncTime = 0L

    fun syncInstalledApps(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastInstalledAppsSyncTime < 30_000L) {
            return
        }
        lastInstalledAppsSyncTime = now
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.syncInstalledApps()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setActiveTab(tabIndex: Int) {
        _activeTab.value = tabIndex
    }

    fun setPendingUnlockTarget(target: Pair<String, String>?) {
        _pendingUnlockTarget.value = target
    }

    private fun handleProtectedToggle(
        featureKey: String,
        targetType: String,
        currentState: Boolean
    ) {
        viewModelScope.launch {
            val pending = uiState.value.unlockRequests.find {
                it.targetKey == featureKey || it.targetType == targetType
            }
            if (currentState) {
                if (pending == null) {
                    repository.createUnlockRequest(targetType, featureKey)
                }
            } else {
                if (pending != null) {
                    repository.deleteUnlockRequest(pending.id)
                }
                if (featureKey == AppRepository.KEY_NEW_INSTALL_APP_BLOCKED) {
                    Log.i("NewInstallDebug", "Settings toggle: KEY_NEW_INSTALL_APP_BLOCKED = true")
                }
                repository.setSetting(featureKey, "true")
            }
        }
    }

    fun toggleFeatureSetting(key: String, currentState: Boolean) {
        if (key == AppRepository.KEY_UNINSTALL_PROTECTION) {
            toggleUninstallProtection(currentState)
            return
        }
        val targetType = when (key) {
            AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED -> "SEARCH_BLOCKING_YOUTUBE"
            AppRepository.KEY_NEW_INSTALL_APP_BLOCKED -> "NEW_INSTALL_APP_BLOCK"
            else -> null
        }
        if (targetType != null) {
            handleProtectedToggle(key, targetType, currentState)
        } else {
            viewModelScope.launch {
                repository.setSetting(key, if (currentState) "false" else "true")
            }
        }
    }

    fun toggleSearchBlockingYouTube(currentState: Boolean) {
        toggleFeatureSetting(AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED, currentState)
    }

    fun toggleNewInstallAppBlocked(currentState: Boolean) {
        toggleFeatureSetting(AppRepository.KEY_NEW_INSTALL_APP_BLOCKED, currentState)
    }

    fun toggleBlockedKeyword(id: Long, currentState: Boolean) {
        viewModelScope.launch {
            val targetKey = id.toString()
            val existing = uiState.value.unlockRequests.find {
                (it.targetType == "SEARCH_KEYWORD" || it.targetType == "BLOCKED_KEYWORD") && it.targetKey == targetKey
            }
            if (currentState) {
                // Disabling keyword requires 12-hour delayed unlock
                if (existing == null) {
                    repository.createUnlockRequest("SEARCH_KEYWORD", targetKey)
                }
            } else {
                // Enabling keyword is instant, cancel pending unlock request if any
                if (existing != null) {
                    repository.deleteUnlockRequest(existing.id)
                }
                repository.setBlockedKeywordEnabled(id, true)
            }
        }
    }

    fun addBlockedKeyword(
        keyword: String,
        matchMode: String = "CONTAINS",
        onResult: ((Result<Long>) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = repository.addBlockedKeyword(keyword, matchMode)
            onResult?.invoke(result)
        }
    }

    fun setBlockedKeywordEnabled(id: Long, enabled: Boolean) {
        toggleBlockedKeyword(id, !enabled)
    }

    fun deleteBlockedKeyword(id: Long) {
        viewModelScope.launch {
            repository.deleteBlockedKeyword(id)
        }
    }

    fun deleteAllBlockedKeywords() {
        viewModelScope.launch {
            repository.deleteAllBlockedKeywords()
        }
    }

    fun setDelayHours() {
        viewModelScope.launch {
            // Strictly enforce 12 hours standard for delayed unlock
            repository.setSetting(AppRepository.KEY_UNLOCK_DELAY_HOURS, "10")
        }
    }

    fun cancelUnlockRequest(id: Int) {
        viewModelScope.launch {
            repository.deleteUnlockRequest(id)
        }
    }

    fun addSavedProtectionTarget(target: com.example.service.safety.SavedProtectionTarget): Boolean {
        val success = com.example.service.safety.RememberProtectionEngine.addTarget(getApplication(), target, repository)
        if (success) {
            viewModelScope.launch {
                val targets = com.example.service.safety.RememberProtectionEngine.getSavedTargets(getApplication())
                val encoded = com.example.service.safety.RememberProtectionEngine.encodeTargets(targets)
                repository.setSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET, encoded)
            }
        }
        return success
    }

    fun deleteSavedProtectionTarget(targetId: String) {
        viewModelScope.launch {
            val existing = uiState.value.unlockRequests.find {
                (it.targetType == "REMEMBER_TARGET_DELETE" || it.targetType == "REMEMBER_PATH_DELETE") && it.targetKey == targetId
            }
            if (existing == null) {
                repository.createUnlockRequest("REMEMBER_TARGET_DELETE", targetId)
            }
        }
    }

    fun requestUnblockNewInstallApp(packageName: String) {
        viewModelScope.launch {
            repository.requestUnblockNewInstallApp(packageName)
        }
    }

    fun toggleRememberProtectionMaster(currentState: Boolean) {
        viewModelScope.launch {
            val existing = uiState.value.unlockRequests.find {
                it.targetType == "REMEMBER_PROTECTION_MASTER" ||
                        it.targetKey == AppRepository.KEY_REMEMBER_PROTECTION_ENABLED
            }
            if (currentState) {
                // User requests to turn OFF:
                // Do NOT disable immediately. Protection remains active/ON during the 12-hour cooldown.
                // Start existing 12-hour delayed unlock timer if not already running.
                if (existing == null) {
                    repository.createUnlockRequest("REMEMBER_PROTECTION_MASTER", AppRepository.KEY_REMEMBER_PROTECTION_ENABLED)
                }
            } else {
                // User requests to turn ON (or cancel deactivation):
                // Instantly re-enable and clear any pending unlock request
                if (existing != null) {
                    repository.deleteUnlockRequest(existing.id)
                }
                repository.setSetting(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED, "true")
            }
        }
    }

    fun setAppLocked(packageName: String, locked: Boolean) {
        viewModelScope.launch {
            repository.setAppLocked(packageName, locked)
        }
    }

    fun requestUnblockApp(packageName: String) {
        viewModelScope.launch {
            repository.requestUnblockApp(packageName)
        }
    }
}
