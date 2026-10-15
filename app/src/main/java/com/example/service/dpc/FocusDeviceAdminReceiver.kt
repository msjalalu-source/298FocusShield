package com.example.service.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Standard Android Device Administration and Device Policy Controller (DPC) Receiver.
 *
 * Responsibilities:
 * 1. Handles device administrator lifecycle events.
 * 2. Provides the system ComponentName for DevicePolicyManager operations (e.g., setUninstallBlocked).
 * 3. Safely logs state transitions without crashing or executing blocking operations on the main thread.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "🛡️ FocusDeviceAdminReceiver enabled as Device Administrator")
        try {
            val repo = com.example.data.AppRepository.getInstance(context)
            repo.repositoryScope.launch {
                repo.setSetting(com.example.data.AppRepository.KEY_UNINSTALL_PROTECTION, "true")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync uninstall protection on enable", e)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "⚠️ FocusDeviceAdminReceiver disabled as Device Administrator")
        try {
            val repo = com.example.data.AppRepository.getInstance(context)
            repo.repositoryScope.launch {
                repo.setSetting(com.example.data.AppRepository.KEY_UNINSTALL_PROTECTION, "false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync uninstall protection on disable", e)
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "🛡️ Profile/Device provisioning completed for Focus Shield DPC")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Entering lock task mode for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Exiting lock task mode")
    }

    companion object {
        private const val TAG = "FocusDeviceAdminReceiver"
    }
}
