package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Auto-Prompt & Exemption Manager inspired by BlockerHero.
 * Handles battery optimization whitelist requests, autostart guidance, overlay permissions,
 * and MIUI/HyperOS cleaner immunity settings.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"
    const val PREFS_SYSTEM = "focus_shield_system"
    const val KEY_BATTERY_RESTRICTION_PROMPT_DONE = "battery_restriction_prompt_done"
    const val KEY_AUTOSTART_PROMPT_DONE = "autostart_prompt_done"

    /**
     * Checks if the user has already configured or completed the Battery Saver (No restrictions) setup step.
     */
    fun isBatteryRestrictionDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_SYSTEM, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BATTERY_RESTRICTION_PROMPT_DONE, false)
    }

    /**
     * Records that the Battery Saver (No restrictions) setup step has been completed or acknowledged.
     */
    fun setBatteryRestrictionDone(context: Context, done: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_SYSTEM, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BATTERY_RESTRICTION_PROMPT_DONE, done).apply()
    }

    /**
     * Checks if the user has already configured or completed the Auto-Start setup step.
     */
    fun isAutoStartDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_SYSTEM, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTOSTART_PROMPT_DONE, false)
    }

    /**
     * Records that the Auto-Start setup step has been completed or acknowledged.
     */
    fun setAutoStartDone(context: Context, done: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_SYSTEM, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTOSTART_PROMPT_DONE, done).apply()
    }

    /**
     * Direct navigation intent to Xiaomi Autostart / Security Center settings
     */
    fun openXiaomiAutostartSettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartActivity")),
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Direct navigation intent to App Details & Battery Saver Settings
     */
    fun openAppDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details", e)
        }
    }
}
