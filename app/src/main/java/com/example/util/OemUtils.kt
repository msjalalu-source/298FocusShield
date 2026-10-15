package com.example.util

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Clean OEM and Device Management Utility
 * Provides safe detection of Xiaomi/MIUI/HyperOS environment and standard Android platform capabilities.
 * Strictly avoids unauthorized hacks or undocumented private APIs.
 */
object OemUtils {

    enum class ReliabilityStatus {
        PROTECTED,
        SERVICE_RECOVERED,
        OEM_RESTRICTION_DETECTED
    }

    /**
     * Checks if current manufacturer or OS flavor is Xiaomi/Redmi/POCO/MIUI/HyperOS
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase(Locale.ROOT) ?: ""
        val brand = Build.BRAND?.lowercase(Locale.ROOT) ?: ""
        val fingerprint = Build.FINGERPRINT?.lowercase(Locale.ROOT) ?: ""
        return manufacturer.contains("xiaomi") ||
                brand.contains("xiaomi") ||
                brand.contains("redmi") ||
                brand.contains("poco") ||
                fingerprint.contains("miui") ||
                fingerprint.contains("hyperos")
    }

    /**
     * Determines the overall runtime reliability status for UI transparency
     */
    fun getReliabilityStatus(context: Context, isServiceActive: Boolean): ReliabilityStatus {
        return when {
            isServiceActive -> ReliabilityStatus.PROTECTED
            isXiaomiDevice() -> ReliabilityStatus.OEM_RESTRICTION_DETECTED
            else -> ReliabilityStatus.PROTECTED
        }
    }
}
