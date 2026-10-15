package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Helper to open Accessibility settings with targeted deep-linking.
 * Attempts to direct the user directly to the "Downloaded Apps" or specific
 * Focus Shield Accessibility Service configuration screen first,
 * with graceful fallback to standard Accessibility Settings if unsupported by the OEM.
 */
object AccessibilityHelper {

    private const val TAG = "AccessibilityHelper"

    fun openAccessibilityServiceSettings(context: Context) {
        val serviceComponent = ComponentName(context, FocusAccessibilityService::class.java).flattenToString()

        val candidateIntents = mutableListOf<Intent>()

        // 1. Android 14+ (API 34+) official accessibility details intent for specific app
        if (Build.VERSION.SDK_INT >= 34) {
            candidateIntents.add(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }

        // 2. Direct accessibility settings targeting specific service component via fragment args
        candidateIntents.add(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", serviceComponent)
                val bundle = Bundle().apply {
                    putString(":settings:fragment_args_key", serviceComponent)
                }
                putExtra(":settings:show_fragment_args", bundle)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        // 3. MIUI / HyperOS Downloaded Apps / Installed Services preference fragment
        candidateIntents.add(
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", "com.android.settings.accessibility.DownloadedServicesPreferenceFragment")
                putExtra(":settings:fragment_args_key", serviceComponent)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        // 4. Xiaomi / MIUI Downloaded Apps activity
        candidateIntents.add(
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.accessibility.InstalledWebApps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        // 5. Samsung / OEM Installed Services SubSettings
        candidateIntents.add(
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", "com.android.settings.accessibility.AccessibilityInstalledServiceActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        // Try candidate deep-links in priority order
        for (intent in candidateIntents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "Candidate accessibility intent failed: ${intent.component ?: intent.action}", e)
            }
        }

        // 6. Safe root fallback to standard Android Accessibility Settings
        try {
            val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open general accessibility settings", e)
        }
    }
}
