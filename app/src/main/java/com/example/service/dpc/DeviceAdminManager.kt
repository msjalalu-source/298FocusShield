package com.example.service.dpc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.data.AppRepository

/**
 * Official Android Device Administration Manager.
 *
 * Implements standard, officially supported Android Device Administration APIs (DeviceAdminReceiver)
 * for Uninstall Protection without ADB commands, root, or unsupported workarounds.
 *
 * How Android OS enforces protection:
 * When an app is registered as an active Device Administrator, the Android operating system
 * refuses direct uninstallation from the launcher or app manager until Device Admin is explicitly
 * deactivated in system settings.
 */
object DeviceAdminManager {

    private const val TAG = "DeviceAdminManager"

    /**
     * Returns the ComponentName for our registered FocusDeviceAdminReceiver.
     */
    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context.applicationContext, FocusDeviceAdminReceiver::class.java)
    }

    /**
     * Safely retrieves the system DevicePolicyManager service.
     */
    fun getDevicePolicyManager(context: Context): DevicePolicyManager? {
        return try {
            context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        } catch (e: Exception) {
            // ignored intentionally
            null
        }
    }

    /**
     * Checks if Focus Shield is currently active as a Device Administrator.
     */
    fun isAdminActive(context: Context): Boolean {
        val dpm = getDevicePolicyManager(context) ?: return false
        val admin = getAdminComponent(context)
        return try {
            dpm.isAdminActive(admin)
        } catch (e: Exception) {
            // ignored intentionally
            false
        }
    }

    /**
     * Creates the official Android system Intent to request Device Admin activation.
     */
    fun createAddAdminIntent(context: Context, explanation: String? = null): Intent {
        val admin = getAdminComponent(context)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            if (!explanation.isNullOrBlank()) {
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            }
        }
    }

    /**
     * Safely deactivates Device Administrator privileges.
     */
    fun deactivateAdmin(context: Context): Boolean {
        val dpm = getDevicePolicyManager(context) ?: return false
        val admin = getAdminComponent(context)
        return try {
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin)
            }
            true
        } catch (e: Exception) {
            // ignored intentionally
            false
        }
    }

    /**
     * Synchronizes persistent repository settings with real OS-level Device Admin state.
     */
    suspend fun syncAndVerifyPolicyState(context: Context, repository: AppRepository) {
        val appContext = context.applicationContext
        val isSettingEnabled = repository.getSetting(AppRepository.KEY_UNINSTALL_PROTECTION) == "true"
        val isActive = isAdminActive(appContext)

        if (isSettingEnabled && !isActive) {
            // If Device Admin was disabled in Android system settings, sync persistent setting to false
            repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "false")
        }
    }
}
