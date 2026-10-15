package com.example.service.systemui

import android.os.Build

/**
 * Utility for recognizing system UI packages.
 * Quick Toggle Edit Block feature has been completely removed.
 */
object SystemUiDetector {

    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val MIUI_SYSTEMUI_PLUGIN_PACKAGE = "com.android.systemui.plugin"
    private const val MIUI_NOTIFICATION_PACKAGE = "com.miui.notification"
    private const val SEC_SYSTEMUI_PACKAGE = "com.samsung.android.app.cocktailbarservice"

    private val SYSTEMUI_PACKAGES: Set<String> = hashSetOf(
        SYSTEMUI_PACKAGE,
        MIUI_SYSTEMUI_PLUGIN_PACKAGE,
        MIUI_NOTIFICATION_PACKAGE,
        "com.miui.systemui",
        "com.miui.controlcenter",
        "com.miui.center"
    )

    // Known device manufacturer flags
    val isXiaomi: Boolean = run {
        val man = Build.MANUFACTURER
        val brand = Build.BRAND
        man.contains("xiaomi", ignoreCase = true) || man.contains("redmi", ignoreCase = true) || man.contains("poco", ignoreCase = true) ||
                brand.contains("xiaomi", ignoreCase = true) || brand.contains("redmi", ignoreCase = true) || brand.contains("poco", ignoreCase = true)
    }

    val isSamsung: Boolean = run {
        val man = Build.MANUFACTURER
        val brand = Build.BRAND
        man.contains("samsung", ignoreCase = true) || brand.contains("samsung", ignoreCase = true)
    }

    fun resetStateForTesting() {
        // No state needed
    }

    /**
     * Checks if the package belongs to SystemUI using exact and OEM-specific matching.
     */
    fun isSystemUiPackage(pkg: CharSequence?): Boolean {
        if (pkg.isNullOrBlank()) return false

        val pkgStr = if (pkg is String) pkg else pkg.toString()

        // Fast path: Exact match against pre-compiled HashSet (Zero allocation)
        if (SYSTEMUI_PACKAGES.contains(pkgStr)) return true
        if (isSamsung && pkgStr == SEC_SYSTEMUI_PACKAGE) return true

        // Fast check: Contains "systemui" without allocating lowercase string
        if (pkgStr.contains("systemui", ignoreCase = true)) return true

        // Fallback: Case-insensitive match for OEM packages (e.g. if mixed-case is sent)
        val lower = pkgStr.lowercase()
        return SYSTEMUI_PACKAGES.contains(lower) ||
                (isSamsung && lower == SEC_SYSTEMUI_PACKAGE)
    }
}
