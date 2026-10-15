package com.example.service

/**
 * Utility for recognizing system settings and security center packages.
 */
object TemporaryUnlockManager {

    fun isSettingsPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
                lower == "com.miui.securitycenter" ||
                lower == "com.miui.powerkeeper" ||
                lower == "com.miui.cleanmaster" ||
                lower == "com.miui.guardprovider" ||
                lower.contains("settings") ||
                lower.contains("securitycenter") ||
                lower.contains("powerkeeper")
    }
}

