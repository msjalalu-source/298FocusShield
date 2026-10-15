package com.example.service

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.bootstrap.CriticalReadinessState
import com.example.service.dpc.DeviceAdminManager

/**
 * Capability-based detection engine for startup, health diagnostics, and system integrations.
 * Avoids hardcoding OEM/OS assumptions by dynamically querying runtime capabilities.
 */
object StartupCapabilityDetector {

    data class ProtectionHealthReport(
        val readinessState: CriticalReadinessState,
        val isSettingEnabled: Boolean,
        val isServiceConnected: Boolean,
        val isDeviceAdminActive: Boolean,
        val lastEventTimeMs: Long,
        val lastEventAgeMs: Long,
        val isHealthy: Boolean,
        val statusMessage: String
    )

    data class StartupCapabilities(
        val isDirectBootStorageAvailable: Boolean,
        val isUserUnlocked: Boolean,
        val isNotificationPermissionGranted: Boolean,
        val isAccessibilityServiceConfigured: Boolean,
        val isXiaomiSecurityCenterAvailable: Boolean,
        val isXiaomiPowerKeeperAvailable: Boolean,
        val capabilitySummary: String
    )

    fun isAccessibilityServiceAlive(): Boolean = FocusAccessibilityService.isServiceConnected()

    fun isAccessibilitySettingEnabled(context: Context): Boolean {
        return try {
            val expectedServiceName = "${context.packageName}/${FocusAccessibilityService::class.java.canonicalName}"
            val expectedShortName = "${context.packageName}/.service.FocusAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(context.packageName) ||
                    enabledServices.contains(expectedServiceName) ||
                    enabledServices.contains(expectedShortName)
        } catch (_: Exception) {
            false
        }
    }

    fun getProtectionReadinessState(context: Context): CriticalReadinessState {
        val settingEnabled = isAccessibilitySettingEnabled(context)
        if (!settingEnabled) {
            return CriticalReadinessState.NOT_READY
        }

        val serviceConnected = FocusAccessibilityService.isServiceConnected()
        val bootstrapState = CriticalProtectionBootstrap.currentReadinessState

        return when {
            serviceConnected && (bootstrapState == CriticalReadinessState.READY) -> CriticalReadinessState.READY
            serviceConnected && (bootstrapState == CriticalReadinessState.STARTING) -> CriticalReadinessState.STARTING
            !serviceConnected -> CriticalReadinessState.DEGRADED
            bootstrapState == CriticalReadinessState.FAILED -> CriticalReadinessState.FAILED
            else -> CriticalReadinessState.READY
        }
    }

    fun getHealthReport(context: Context): ProtectionHealthReport {
        val settingEnabled = isAccessibilitySettingEnabled(context)
        val serviceConnected = FocusAccessibilityService.isServiceConnected()
        val adminActive = DeviceAdminManager.isAdminActive(context)
        val lastEventTime = FocusAccessibilityService.getLastEventTime()
        val now = SystemClock.elapsedRealtime()
        val lastEventAge = if (lastEventTime > 0L) now - lastEventTime else -1L

        val readinessState = getProtectionReadinessState(context)
        val isHealthy = settingEnabled && serviceConnected && readinessState == CriticalReadinessState.READY

        val statusMsg = when (readinessState) {
            CriticalReadinessState.READY -> "Protection Pipeline is Active and Enforcing."
            CriticalReadinessState.DEGRADED -> "Accessibility Setting is ON, but Service process was suspended/killed. Re-binding on next interaction."
            CriticalReadinessState.STARTING -> "Protection Engine is bootstrapping critical security snapshot."
            CriticalReadinessState.NOT_READY -> "Accessibility Service is not enabled in System Settings."
            CriticalReadinessState.FAILED -> "Protection Engine encountered an unrecoverable failure."
        }

        return ProtectionHealthReport(
            readinessState = readinessState,
            isSettingEnabled = settingEnabled,
            isServiceConnected = serviceConnected,
            isDeviceAdminActive = adminActive,
            lastEventTimeMs = lastEventTime,
            lastEventAgeMs = lastEventAge,
            isHealthy = isHealthy,
            statusMessage = statusMsg
        )
    }

    fun detectCapabilities(context: Context): StartupCapabilities {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val isUserUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }

        val isDirectBootAvailable = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() != null
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val areNotificationsEnabled = notificationManager?.areNotificationsEnabled() ?: true

        val isAccessibilityConfigured = isAccessibilitySettingEnabled(context)

        val isSecurityCenterPresent = isPackageInstalled(context, "com.miui.securitycenter")
        val isPowerKeeperPresent = isPackageInstalled(context, "com.miui.powerkeeper")

        val summary = buildString {
            append("DirectBoot=").append(if (isDirectBootAvailable) "YES" else "NO")
            append(", UserUnlocked=").append(if (isUserUnlocked) "YES" else "NO")
            append(", AccessibilityEnabled=").append(if (isAccessibilityConfigured) "YES" else "NO")
            if (isSecurityCenterPresent) append(", XiaomiSecurityCenter=PRESENT")
            if (isPowerKeeperPresent) append(", XiaomiPowerKeeper=PRESENT")
        }

        return StartupCapabilities(
            isDirectBootStorageAvailable = isDirectBootAvailable,
            isUserUnlocked = isUserUnlocked,
            isNotificationPermissionGranted = areNotificationsEnabled,
            isAccessibilityServiceConfigured = isAccessibilityConfigured,
            isXiaomiSecurityCenterAvailable = isSecurityCenterPresent,
            isXiaomiPowerKeeperAvailable = isPowerKeeperPresent,
            capabilitySummary = summary
        )
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

