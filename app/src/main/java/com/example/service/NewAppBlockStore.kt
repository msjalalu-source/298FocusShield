package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.data.AppRepository
import com.example.service.bootstrap.CriticalProtectionBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative, minimal, high-performance store for newly installed app blocking.
 *
 * Architecture:
 * - Single authoritative in-memory Set (ConcurrentHashMap.newKeySet) for zero-allocation, O(1) foreground checking.
 * - Device-Protected SharedPreferences persistence using asynchronous apply() (survives process death and reboots).
 * - Immediate in-memory updates before async persistence.
 * - Idempotent add/remove with duplicate suppression to eliminate redundant disk I/O.
 * - Zero polling loops, zero database tables, zero hierarchy traversals, zero hot-path disk reads.
 */
object NewAppBlockStore {

    private const val TAG = "NewAppBlockStore"
    const val PREFS_KEY_BLOCKED_NEW_APPS = "snap_new_install_blocked"
    const val PREF_FEATURE_ENABLED = AppRepository.KEY_NEW_INSTALL_APP_BLOCKED
    const val EXEMPT_PACKAGE = "com.muslim.browser.pro"

    fun isExemptPackage(packageName: String?): Boolean = packageName == EXEMPT_PACKAGE

    // Thread-safe in-memory cache for ultra-fast foreground lookup in AccessibilityService hot path
    private val inMemoryBlockedApps = ConcurrentHashMap.newKeySet<String>()

    // Reactive StateFlow for UI observation (zero overhead on foreground hot path)
    private val _blockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val blockedAppsFlow: StateFlow<Set<String>> = _blockedAppsFlow.asStateFlow()

    private fun updateFlow() {
        _blockedAppsFlow.value = Collections.unmodifiableSet(HashSet(inMemoryBlockedApps))
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isFeatureEnabledCache = true

    @Volatile
    private var appContext: Context? = null

    private val initLock = Any()

    @Volatile
    private var isTestingSimulation = false

    /**
     * Initializes in-memory cache from persistence. Safe to call multiple times (idempotent).
     */
    fun init(context: Context, forceReload: Boolean = false) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (isInitialized && !forceReload) return

        synchronized(initLock) {
            if (isInitialized && !forceReload) return
            try {
                val prefs = getPrefs(context)
                val persisted = prefs.getStringSet(PREFS_KEY_BLOCKED_NEW_APPS, emptySet()) ?: emptySet()
                inMemoryBlockedApps.clear()
                for (pkg in persisted) {
                    if (pkg.isNotBlank()) {
                        inMemoryBlockedApps.add(pkg)
                    }
                }
                updateFlow()
                val settingVal = prefs.getString("setting_${AppRepository.KEY_NEW_INSTALL_APP_BLOCKED}", "true")
                val unlockAt = prefs.getLong("snap_new_install_unlock_at", 0L)
                if (unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
                    isFeatureEnabledCache = false
                    prefs.edit()
                        .putString("setting_${AppRepository.KEY_NEW_INSTALL_APP_BLOCKED}", "false")
                        .remove("snap_new_install_unlock_at")
                        .apply()
                } else {
                    isFeatureEnabledCache = settingVal != "false"
                }
                isInitialized = true
                isTestingSimulation = false
                Log.d(TAG, "Initialized with ${inMemoryBlockedApps.size} blocked new apps (enabled: $isFeatureEnabledCache)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NewAppBlockStore", e)
            }
        }
    }

    private fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            init(context)
        }
    }

    /**
     * Check whether the "New Installed Apps" protection feature is enabled.
     */
    fun isFeatureEnabled(): Boolean = isFeatureEnabledCache

    fun isFeatureEnabled(context: Context): Boolean {
        if (!isInitialized && !isTestingSimulation) {
            init(context)
        }
        val prefs = getPrefs(context)
        val unlockAt = prefs.getLong("snap_new_install_unlock_at", 0L)
        if (unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
            isFeatureEnabledCache = false
            prefs.edit()
                .putString("setting_${AppRepository.KEY_NEW_INSTALL_APP_BLOCKED}", "false")
                .remove("snap_new_install_unlock_at")
                .apply()
        }
        return isFeatureEnabledCache
    }

    /**
     * Set feature enabled/disabled state.
     */
    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        isFeatureEnabledCache = enabled
        try {
            val prefs = getPrefs(context)
            val editor = prefs.edit().putString("setting_${AppRepository.KEY_NEW_INSTALL_APP_BLOCKED}", enabled.toString())
            if (enabled) {
                editor.remove("snap_new_install_unlock_at")
            }
            editor.apply()
        } catch (_: Exception) {}
        Log.i("NewInstallDebug", "NewAppBlockStore setFeatureEnabled: $enabled")
    }

    fun enableFeature(context: Context) {
        setFeatureEnabled(context, true)
    }

    fun disableFeature(context: Context) {
        setFeatureEnabled(context, false)
    }

    /**
     * O(1) in-memory check for foreground AccessibilityService hot path.
     * ZERO disk I/O, ZERO database queries, ZERO hierarchy scans, ZERO string allocations.
     */
    fun isBlocked(packageName: String?): Boolean = isBlocked(packageName, null)

    fun isBlocked(packageName: String?, fallbackContext: Context? = null): Boolean {
        if (packageName.isNullOrEmpty() || isExemptPackage(packageName)) return false
        if (!isInitialized && !isTestingSimulation) {
            val ctx = fallbackContext?.applicationContext ?: appContext
            if (ctx != null) {
                init(ctx)
            }
        }
        return inMemoryBlockedApps.contains(packageName)
    }

    private fun isFastSystemPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val lower = packageName.lowercase()
        return lower == "android" ||
                lower == "com.android.systemui" ||
                lower.contains("keyguard") ||
                lower.contains("inputmethod") ||
                lower.contains("keyboard") ||
                lower.contains("dialer") ||
                lower.contains("telecom") ||
                lower.contains("permissioncontroller") ||
                lower.contains("packageinstaller") ||
                lower == "com.google.android.gms" ||
                lower == "com.google.android.gsf" ||
                lower == "com.android.vending" ||
                lower.startsWith("com.android.settings") ||
                lower.startsWith("com.miui.securitycenter") ||
                lower.startsWith("com.miui.securityadd") ||
                lower.startsWith("com.miui.powerkeeper") ||
                lower.startsWith("com.miui.cleanmaster") ||
                lower.startsWith("com.miui.guardprovider") ||
                lower.startsWith("com.xiaomi.misettings") ||
                lower.startsWith("com.samsung.android.settings")
    }

    /**
     * Adds a newly installed package to the blocked set if valid and not self/system.
     * Idempotent: returns false if already blocked, avoiding duplicate persistence or notifications.
     */
    fun addBlockedApp(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty() || packageName == context.packageName || isExemptPackage(packageName)) return false
        ensureInitialized(context)

        // Fast path: skip known core system packages immediately without IPC
        if (isFastSystemPackage(packageName)) {
            return false
        }

        // Authoritative check: skip OEM/other system apps via PackageManager before state modification
        if (isSystemPackage(context, packageName)) {
            return false
        }

        val added = inMemoryBlockedApps.add(packageName)
        if (!added) {
            // Already blocked - avoid duplicate persistence or service overhead
            return false
        }

        isFeatureEnabledCache = true
        // 1. Immediately notify AccessibilityService (O(1) direct add, no full set rebuild)
        FocusAccessibilityService.notifyPackageInstalled(packageName, shouldBlock = true)

        // 2. Update reactive StateFlow for UI
        updateFlow()

        // 3. Persist asynchronously
        persistAsync(context)

        Log.d(TAG, "addBlockedApp: packageName = $packageName, total = ${inMemoryBlockedApps.size}")
        return true
    }

    /**
     * Internal direct addition for in-memory synchronization without duplicate disk writes.
     */
    internal fun addBlockedAppDirect(packageName: String) {
        if (packageName.isNotEmpty() && !isExemptPackage(packageName)) {
            inMemoryBlockedApps.add(packageName)
            isFeatureEnabledCache = true
            updateFlow()
        }
    }

    /**
     * Internal direct removal for in-memory synchronization without duplicate disk writes.
     */
    internal fun removeAppDirect(packageName: String) {
        if (packageName.isNotEmpty()) {
            inMemoryBlockedApps.remove(packageName)
            updateFlow()
        }
    }

    /**
     * Removes an unblocked/uninstalled package from the blocked set.
     * Idempotent: returns false if not present, avoiding unnecessary disk I/O.
     */
    fun removeApp(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        ensureInitialized(context)
        val removed = inMemoryBlockedApps.remove(packageName)
        if (!removed) return false

        FocusAccessibilityService.notifyPackageInstalled(packageName, shouldBlock = false)
        updateFlow()
        persistAsync(context)
        Log.i(TAG, "Removed app from blocked set: $packageName")
        return true
    }

    /**
     * Clears all blocked new apps (e.g. Unblock All).
     */
    fun clear(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (inMemoryBlockedApps.isEmpty()) return
        inMemoryBlockedApps.clear()
        FocusAccessibilityService.rebuildMonitoredPackages()
        updateFlow()
        persistAsync(context)
        isInitialized = true
        isTestingSimulation = false
        Log.i(TAG, "Cleared all blocked new apps")
    }

    /**
     * Handles package replacement (app update).
     * Preserves blocked status if already blocked; does not add duplicate state or trigger redundant disk I/O.
     */
    fun onPackageReplaced(context: Context, packageName: String) {
        // No-op: inMemoryBlockedApps already contains the state if previously blocked,
        // and disk persistence is already identical. No disk write or state mutation required.
    }

    /**
     * Returns an unmodifiable snapshot of currently blocked packages.
     */
    fun getBlockedApps(): Set<String> {
        return Collections.unmodifiableSet(HashSet(inMemoryBlockedApps))
    }

    @androidx.annotation.VisibleForTesting
    internal fun setBlockedAppsForTesting(apps: Set<String>) {
        inMemoryBlockedApps.clear()
        inMemoryBlockedApps.addAll(apps)
        updateFlow()
        isInitialized = false
        isTestingSimulation = true
    }

    private fun persistAsync(context: Context) {
        try {
            val snapshot = HashSet<String>(inMemoryBlockedApps)
            getPrefs(context).edit()
                .putStringSet(PREFS_KEY_BLOCKED_NEW_APPS, snapshot)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to asynchronously persist blocked apps", e)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return AppRepository.getDeviceProtectedPrefs(context)
    }

    private fun isSystemPackage(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }
    }
}
