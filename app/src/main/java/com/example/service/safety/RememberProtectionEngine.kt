package com.example.service.safety

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppRepository
import com.example.service.bootstrap.CriticalProtectionBootstrap
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Data class representing a configured Remember Protection target.
 *
 * Universal Matching Fields:
 * 1. Navigation Path (navigationPath: target control text to match)
 * 2. Package Name (packageName: target application package name)
 * 3. Event Type (eventType: "Type View Click" / "Type View Long Click" / "Blank" for any)
 *
 * Universal Matching Rule:
 * Package Name MATCH AND Navigation Path MATCH AND Event Type MATCH = BLOCK.
 */
@Immutable
data class SavedProtectionTarget(
    val navigationPath: String,
    val packageName: String,
    val eventType: String = "",
    val id: String = java.util.UUID.randomUUID().toString()
) {
    // Secondary constructor for compatibility with existing code and tests
    constructor(
        packageName: String,
        target: String = "",
        eventType: String = "",
        id: String = java.util.UUID.randomUUID().toString(),
        navigationPath: String = "",
        title: String = "",
        actionType: String = "",
        path: String = "",
        activityName: String = ""
    ) : this(
        navigationPath = navigationPath.ifBlank { target.ifBlank { path.ifBlank { title } } },
        packageName = packageName,
        eventType = eventType,
        id = id.ifBlank { java.util.UUID.randomUUID().toString() }
    )

    // Aliases to support existing references
    val target: String get() = navigationPath
    val path: String get() = navigationPath
    val title: String get() = ""
    val actionType: String get() = ACTION_INTERCEPT
    val activityName: String get() = ""

    // Cached normalized values for hot path evaluation
    val trimmedNavigationPath: String by lazy(LazyThreadSafetyMode.NONE) { navigationPath.trim() }
    val trimmedTarget: String get() = trimmedNavigationPath
    val trimmedPackageName: String by lazy(LazyThreadSafetyMode.NONE) { packageName.trim() }
    val trimmedEventType: String by lazy(LazyThreadSafetyMode.NONE) { eventType.trim() }
    val cachedParsedEventType: Int? by lazy(LazyThreadSafetyMode.NONE) {
        if (trimmedEventType.isEmpty()) null else RememberProtectionEngine.parseEventType(trimmedEventType)
    }

    companion object {
        const val ACTION_INTERCEPT = "Intercept"
        val ACTION_TYPES = listOf(ACTION_INTERCEPT)

        fun normalizeActionType(action: String?): String = ACTION_INTERCEPT
    }
}

/**
 * Focus Shield — Universal Remember Protection Engine
 *
 * Universal Matching Rule:
 * Package Name MATCH AND Navigation Path MATCH AND Event Type MATCH = BLOCK.
 *
 * Every RememberProtection target (Xiaomi Security, Settings, System UI, or custom apps)
 * follows the exact same 3-field universal matcher.
 */
object RememberProtectionEngine {

    const val TAG = "RememberProtection"

    @Volatile
    private var cachedTargets: List<SavedProtectionTarget>? = null

    @Volatile
    private var candidatePackages: Set<String> = emptySet()

    @Volatile
    private var targetsByPackage: Map<String, List<SavedProtectionTarget>> = emptyMap()

    private fun updateCachedTargets(targets: List<SavedProtectionTarget>) {
        cachedTargets = targets
        val byPkg = HashMap<String, MutableList<SavedProtectionTarget>>(targets.size)
        for (t in targets) {
            val cleanPkg = t.trimmedPackageName.lowercase()
            byPkg.getOrPut(cleanPkg) { mutableListOf() }.add(t)
        }
        targetsByPackage = byPkg
        candidatePackages = byPkg.keys
    }

    /**
     * Validation for adding targets: Navigation Path and Package Name are required.
     */
    fun validateTarget(target: SavedProtectionTarget): String? {
        if (target.navigationPath.trim().isEmpty()) {
            return "Navigation Path cannot be empty"
        }
        if (target.packageName.trim().isEmpty()) {
            return "Package name cannot be empty"
        }
        return null
    }

    @Volatile
    private var explicitEnabledOverride: Boolean? = null

    /**
     * Checks whether Remember Protection Master Switch is enabled.
     * Defaults to true (ON). Returns false when temporarily disabled via Master Switch.
     */
    fun isRememberProtectionActive(context: Context? = null, repository: AppRepository? = null): Boolean {
        explicitEnabledOverride?.let { return it }

        if (context != null) {
            val repo = repository ?: try {
                AppRepository.getInstance(context)
            } catch (_: Exception) {
                null
            }
            val setting = repo?.getSettingDirect(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED)
                ?: CriticalProtectionBootstrap.getSetting(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED)
                ?: try {
                    AppRepository.getDeviceProtectedPrefs(context)
                        .getString("setting_${AppRepository.KEY_REMEMBER_PROTECTION_ENABLED}", null)
                } catch (_: Exception) {
                    null
                }
            if (setting != null) {
                return setting.equals("true", ignoreCase = true)
            }
        }

        val bootstrapSetting = CriticalProtectionBootstrap.getSetting(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED)
        if (bootstrapSetting != null) {
            return bootstrapSetting.equals("true", ignoreCase = true)
        }

        return true
    }

    /**
     * Updates Remember Protection active state for runtime testing or bootstrap updates.
     */
    fun setEnabled(enabled: Boolean) {
        explicitEnabledOverride = enabled
        CriticalProtectionBootstrap.updateSetting(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED, enabled.toString())
    }

    /**
     * Verifies if the package matches any user-saved Remember Protection target.
     * Uses O(1) in-memory Set lookup for zero Binder IPC during Accessibility events.
     */
    fun isCandidatePackage(packageName: String): Boolean {
        return isCandidatePackage(null, packageName)
    }

    fun isCandidatePackage(context: Context?, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val cleanPkgLower = packageName.trim().lowercase()
        if (candidatePackages.contains(cleanPkgLower)) return true
        if (cachedTargets == null && context != null) {
            getSavedTargets(context)
            return candidatePackages.contains(cleanPkgLower)
        }
        return false
    }

    private val eventTypeParseCache = java.util.concurrent.ConcurrentHashMap<String, Int>(16)

    /**
     * Parses user-facing Event Types:
     * - "Type View Click" -> AccessibilityEvent.TYPE_VIEW_CLICKED
     * - "Type View Long Click" -> AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
     * - "Blank" / empty -> null (matches any event type)
     */
    fun parseEventType(input: String): Int? {
        val s = input.trim().uppercase()
        if (s.isEmpty() || s == "BLANK" || s == "NONE" || s == "ANY") return null

        eventTypeParseCache[s]?.let { return it }

        s.toIntOrNull()?.let {
            if (it == AccessibilityEvent.TYPE_VIEW_CLICKED || it == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                eventTypeParseCache[s] = it
                return it
            } else {
                return null
            }
        }

        val result: Int? = if (s.contains("LONG_CLICK") || s.contains("LONG_PRESS") || s.contains("LONG CLICK") || s.contains("LONG PRESS") || s.contains("TYPE_VIEW_LONG_CLICKED") || s == "TYPE VIEW LONG CLICK") {
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        } else if (s.contains("CLICK") || s.contains("TYPE_VIEW_CLICKED") || s == "TYPE VIEW CLICK") {
            AccessibilityEvent.TYPE_VIEW_CLICKED
        } else {
            val extracted = extractFirstInteger(s)
            if (extracted == AccessibilityEvent.TYPE_VIEW_CLICKED || extracted == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                extracted
            } else {
                null
            }
        }

        if (result != null) {
            eventTypeParseCache[s] = result
        }
        return result
    }

    private fun extractFirstInteger(s: String): Int? {
        var start = -1
        for (i in s.indices) {
            if (s[i].isDigit()) {
                if (start == -1) start = i
            } else if (start != -1) {
                return s.substring(start, i).toIntOrNull()
            }
        }
        if (start != -1) {
            return s.substring(start).toIntOrNull()
        }
        return null
    }

    fun eventMatches(configuredEventType: String, actualEventType: Int): Boolean {
        val configured = configuredEventType.trim()
        if (configured.isEmpty() || configured.equals("Blank", ignoreCase = true)) return true

        val expectedType = parseEventType(configured)
        return if (expectedType != null) {
            actualEventType == expectedType
        } else {
            false
        }
    }

    /**
     * Loads saved targets from memory cache or preferences.
     */
    fun getSavedTargets(context: Context): List<SavedProtectionTarget> {
        cachedTargets?.let { return it }
        return try {
            val raw = CriticalProtectionBootstrap.getSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET)
                ?: AppRepository.getDeviceProtectedPrefs(context)
                    .getString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", "")
            val decoded = decodeTargets(raw)
            updateCachedTargets(decoded)
            decoded
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sanitizeForStorage(str: String): String {
        return str.replace("|", "/").replace(";", ",")
    }

    /**
     * Decodes stored targets, migrating legacy formats to the 3-field universal format.
     */
    fun decodeTargets(raw: String?): List<SavedProtectionTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val pkg = parts[0].trim()
                val (navPath, evtType, id) = when {
                    // Universal 4-part format: pkg|navigationPath|evtType|id
                    parts.size == 4 -> {
                        Triple(
                            parts[1].trim(),
                            parts[2].trim(),
                            parts[3].trim().ifEmpty { "${pkg}_${parts[1]}".hashCode().toString() }
                        )
                    }
                    // Legacy 6-part format: pkg|title|target|id|evtType|actType
                    parts.size == 6 -> {
                        val p = parts[2].trim().ifEmpty { parts[1].trim() }
                        Triple(
                            p,
                            parts[4].trim(),
                            parts[3].trim().ifEmpty { "${pkg}_$p".hashCode().toString() }
                        )
                    }
                    // Legacy 5-part format: pkg|target|id|evtType|actType
                    parts.size == 5 -> {
                        Triple(
                            parts[1].trim(),
                            parts[3].trim(),
                            parts[2].trim().ifEmpty { "${pkg}_${parts[1]}".hashCode().toString() }
                        )
                    }
                    // Legacy 7+ fields
                    parts.size >= 7 -> {
                        val p = parts[5].trim().ifEmpty { parts[4].trim().ifEmpty { parts[1].trim() } }
                        val id = parts[6].trim().ifEmpty { "${pkg}_$p".hashCode().toString() }
                        val evt = if (parts.size >= 8) parts[7].trim() else ""
                        Triple(p, evt, id)
                    }
                    else -> {
                        Triple(
                            parts.getOrElse(1) { "" }.trim(),
                            parts.getOrElse(2) { "" }.trim(),
                            parts.getOrElse(3) { "" }.trim().ifEmpty { "${pkg}_${parts.getOrElse(1) { "" }}".hashCode().toString() }
                        )
                    }
                }
                if (pkg.isNotEmpty() && navPath.isNotEmpty()) {
                    SavedProtectionTarget(
                        navigationPath = navPath,
                        packageName = pkg,
                        eventType = evtType,
                        id = id
                    )
                } else null
            } else null
        }
    }

    /**
     * Encodes targets preserving strictly the universal fields: pkg|navigationPath|eventType|id
     */
    fun encodeTargets(targets: List<SavedProtectionTarget>): String {
        return targets.joinToString(";") {
            val pkg = sanitizeForStorage(it.packageName)
            val path = sanitizeForStorage(it.navigationPath)
            val evtType = sanitizeForStorage(it.eventType)
            val id = sanitizeForStorage(it.id)
            "$pkg|$path|$evtType|$id"
        }
    }

    /**
     * Persists all targets to cache, bootstrap, preferences, and repository.
     */
    fun saveAllTargets(
        context: Context,
        targets: List<SavedProtectionTarget>,
        repository: AppRepository? = null
    ) {
        updateCachedTargets(targets)
        val encoded = encodeTargets(targets)

        CriticalProtectionBootstrap.updateSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET, encoded)

        try {
            AppRepository.getDeviceProtectedPrefs(context).edit()
                .putString("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}", encoded)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving targets to prefs", e)
        }

        val targetRepo = repository ?: try {
            AppRepository.getInstance(context)
        } catch (_: Exception) { null }

        targetRepo?.let { repo ->
            repo.repositoryScope.launch(Dispatchers.IO) {
                try {
                    repo.setSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET, encoded)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Adds a new Remember Protection target.
     */
    fun addTarget(
        context: Context,
        target: SavedProtectionTarget,
        repository: AppRepository? = null
    ): Boolean {
        val validationErr = validateTarget(target)
        if (validationErr != null) {
            return false
        }
        val current = getSavedTargets(context).toMutableList()
        val pathText = target.navigationPath.trim()
        val id = if (target.id.isNotBlank() && !current.any { it.id == target.id }) target.id else java.util.UUID.randomUUID().toString()
        current.add(
            SavedProtectionTarget(
                navigationPath = pathText,
                packageName = target.packageName.trim(),
                eventType = target.eventType.trim(),
                id = id
            )
        )
        saveAllTargets(context, current, repository)
        return true
    }

    /**
     * Deletes a target by ID.
     */
    fun deleteTarget(
        context: Context,
        targetId: String,
        repository: AppRepository? = null
    ): Boolean {
        val current = getSavedTargets(context).toMutableList()
        val removed = current.removeAll { it.id == targetId }
        if (removed) {
            saveAllTargets(context, current, repository)
        }
        return removed
    }

    fun matchesNavigationPath(text: String, navigationPath: String): Boolean {
        if (text.isEmpty() || navigationPath.isEmpty()) return false
        val cleanText = text.trim()
        val cleanPath = navigationPath.trim()
        if (cleanPath.isEmpty()) return false
        return cleanText.contains(cleanPath, ignoreCase = true)
    }

    /**
     * Matches an accessibility event against a saved target navigation path:
     * Evaluates clicked node text and contentDescription, as well as direct event text and contentDescription.
     * View ID / Resource ID / identifier matching has been completely removed.
     */
    fun isNavigationPathMatching(
        event: AccessibilityEvent,
        navigationPath: String
    ): Boolean {
        if (navigationPath.isEmpty()) return false

        // 1. Direct text / content description match on event source node
        val s = try { event.source } catch (_: Exception) { null }
        if (s != null) {
            try {
                val nodeText = s.text?.toString()?.trim().orEmpty()
                if (nodeText.isNotEmpty() && matchesNavigationPath(nodeText, navigationPath)) {
                    return true
                }
                val nodeDesc = s.contentDescription?.toString()?.trim().orEmpty()
                if (nodeDesc.isNotEmpty() && matchesNavigationPath(nodeDesc, navigationPath)) {
                    return true
                }
            } finally {
                try { s.recycle() } catch (_: Exception) {}
            }
        }

        // 2. Direct event text or content description fallback
        event.text?.forEach { t ->
            val str = t.toString().trim()
            if (matchesNavigationPath(str, navigationPath)) {
                return true
            }
        }
        event.contentDescription?.toString()?.trim()?.let { desc ->
            if (matchesNavigationPath(desc, navigationPath)) {
                return true
            }
        }

        return false
    }

    fun isClickedNodeOrContextMatching(event: AccessibilityEvent, navigationPath: String): Boolean {
        return isNavigationPathMatching(event, navigationPath)
    }

    fun isClickedNodeOrContextMatching(event: AccessibilityEvent, target: SavedProtectionTarget): Boolean {
        return isNavigationPathMatching(event, target.trimmedNavigationPath)
    }

    /**
     * UNIVERSAL MATCHING ENGINE:
     * Evaluates Remember Protection targets according to the universal rule:
     * 1. Package Name MATCH
     * 2. Event Type MATCH
     * 3. Navigation Path MATCH
     * -> BLOCK
     */
    fun evaluate(
        packageName: String,
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo? = { null },
        context: SafetyProtectionContext,
        effectiveActivity: String = "",
        repository: AppRepository? = null
    ): SafetyProtectionDecision {
        val incomingCleanPkg = packageName.trim()
        val incomingCleanPkgLower = incomingCleanPkg.lowercase()

        // 1. Is Remember Protection active?
        if (!isRememberProtectionActive(context.context, repository)) {
            return SafetyProtectionDecision.Allow("Remember Protection is inactive")
        }

        // 2. Saved targets must exist
        val savedTargets = getSavedTargets(context.context)
        if (savedTargets.isEmpty()) {
            return SafetyProtectionDecision.Allow("No saved Remember Targets configured")
        }

        // 3. Universal Matching Rule:
        // Package Name MATCH + Event Type MATCH + Navigation Path MATCH = BLOCK
        val candidateTargets = targetsByPackage[incomingCleanPkgLower]
        if (candidateTargets.isNullOrEmpty()) {
            return SafetyProtectionDecision.Allow("No saved Remember Targets for package $incomingCleanPkg")
        }
        val eventPkg = event.packageName?.toString()?.trim().orEmpty()

        for (target in candidateTargets) {
            // Condition 1: Package Name MATCH
            val targetPkg = target.trimmedPackageName
            val pkgMatches = targetPkg.equals(incomingCleanPkg, ignoreCase = true) &&
                    (eventPkg.isEmpty() || targetPkg.equals(eventPkg, ignoreCase = true))
            if (!pkgMatches) continue

            // Condition 2: Event Type MATCH (cheap integer comparison first)
            val targetEvtType = target.trimmedEventType
            if (targetEvtType.isNotEmpty()) {
                val expectedType = target.cachedParsedEventType
                val matches = if (expectedType != null) {
                    event.eventType == expectedType
                } else {
                    eventMatches(targetEvtType, event.eventType)
                }
                if (!matches) continue
            }

            // Condition 3: Navigation Path MATCH
            val path = target.trimmedNavigationPath
            if (path.isEmpty()) continue

            val isMatched = isNavigationPathMatching(event, path)
            if (!isMatched) continue

            // All 3 universal conditions matched -> BLOCK
            Log.i(
                TAG,
                "🛡️ [REMEMBER_UNIVERSAL_MATCH] targetId='${target.id}' pkg='${target.packageName}' path='${target.navigationPath}' eventType='${target.eventType}' -> BLOCK"
            )
            return SafetyProtectionDecision.Block(
                targetName = "Remember Protection",
                targetType = ProtectionTargetType.TARGET_SPECIFIC_CONTROL,
                reason = "Matched Remember Protection target: ${target.packageName} [${target.navigationPath}]",
                actionType = BlockActionType.INTERCEPT
            )
        }

        return SafetyProtectionDecision.Allow("Saved target criteria not met")
    }

    @androidx.annotation.VisibleForTesting
    fun addSavedTargetForTesting(target: SavedProtectionTarget) {
        val list = (cachedTargets ?: emptyList()).toMutableList()
        list.add(target)
        updateCachedTargets(list)
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting(context: Context? = null) {
        explicitEnabledOverride = null
        updateCachedTargets(emptyList())
        eventTypeParseCache.clear()
        CriticalProtectionBootstrap.updateSetting(AppRepository.KEY_SAVED_PROTECTION_TARGET, "")
        CriticalProtectionBootstrap.updateSetting(AppRepository.KEY_REMEMBER_PROTECTION_ENABLED, "true")
        AppRepository.resetForTesting()
        if (context != null) {
            try {
                AppRepository.getDeviceProtectedPrefs(context).edit()
                    .remove("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}")
                    .remove("setting_default_remember_targets_v1")
                    .remove("setting_${AppRepository.KEY_REMEMBER_PROTECTION_ENABLED}")
                    .apply()
            } catch (_: Exception) {}
            try {
                context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE).edit()
                    .remove("setting_${AppRepository.KEY_SAVED_PROTECTION_TARGET}")
                    .remove("setting_default_remember_targets_v1")
                    .remove("setting_${AppRepository.KEY_REMEMBER_PROTECTION_ENABLED}")
                    .apply()
            } catch (_: Exception) {}
        }
    }
}
