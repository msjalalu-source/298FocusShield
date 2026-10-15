package com.example.service.accessibility.handlers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppRepository
import com.example.service.search.SearchDetectorRouter
import com.example.service.search.SearchKeywordEngine

/**
 * Interface defining the base contract for accessibility event handlers.
 */
interface AccessibilityHandler {
    /**
     * Quickly determines if this handler can process the given package and event.
     */
    fun canHandle(packageName: String, event: AccessibilityEvent): Boolean

    /**
     * Executes the handling logic.
     * @return true if the event was consumed/handled (terminates the pipeline), false to continue.
     */
    fun handleEvent(
        packageName: String,
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean
}

/**
 * Context provided to handlers containing required services and state accessors.
 * Completely decoupled from database initialization — can execute immediately using fast snapshot.
 */
data class AccessibilityHandlerContext(
    val service: AccessibilityService,
    val repositoryProvider: () -> AppRepository? = { null },
    val searchKeywordEngine: SearchKeywordEngine = SearchKeywordEngine(),
    val searchDetectorRouter: SearchDetectorRouter,
    val isPackageSystemCore: (String) -> Boolean,
    val getSetting: (String) -> String?,
    val isSettingEnabled: (String, Boolean) -> Boolean,
    val getLockedAppsMap: () -> Map<String, String> = { emptyMap() },
    val getKnownAppsSet: () -> Set<String> = { emptySet() },
    val isAppLocked: (String) -> Boolean = { pkg -> getLockedAppsMap().containsKey(pkg) },
    val getLockedAppName: (String) -> String? = { pkg -> getLockedAppsMap()[pkg] },
    val isAppKnown: (String) -> Boolean = { pkg -> getKnownAppsSet().contains(pkg) },
    val performHomeAction: (targetPackage: String?) -> Boolean = { false },
    val performBackAction: () -> Boolean,
    val showToast: (String, Long) -> Unit,
    val getCurrentActivity: () -> String = { "" },
    val recordException: (tag: String, message: String, throwable: Throwable) -> Unit = { tag, msg, tr ->
        Log.e(tag, msg, tr)
    },
    val protectionEngine: com.example.service.protection.ProtectionEngine? = null,
    val coroutineScope: kotlinx.coroutines.CoroutineScope? = null
) {
    val repository: AppRepository? get() = repositoryProvider()
    val context: Context get() = service.applicationContext
    val cachedSettings: Map<String, String> = object : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>> get() = emptySet()
        override fun get(key: String): String? = getSetting(key)
        override fun containsKey(key: String): Boolean = getSetting(key) != null
    }

    val effectiveProtectionEngine: com.example.service.protection.ProtectionEngine by lazy {
        protectionEngine ?: com.example.service.protection.ProtectionEngine(
            stateProvider = object : com.example.service.protection.ProtectionStateProvider {
                override fun isSearchBlockingEnabled(target: com.example.service.search.SearchBlockingTarget): Boolean =
                    searchKeywordEngine.isTargetEnabled(target)
            },
            blockingAction = com.example.service.protection.CommonBlockingAction(
                performBack = performBackAction,
                performHome = performHomeAction,
                showToast = showToast
            )
        )
    }

    /**
     * Centralized decision evaluating if a package should be blocked by any active protection policy.
     */
    fun shouldBlock(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        return isAppLocked(packageName)
    }
}

/**
 * Convenience extension for performing Home action with default/fallback target package.
 */
fun AccessibilityHandlerContext.performHomeAction(): Boolean = performHomeAction(null)

