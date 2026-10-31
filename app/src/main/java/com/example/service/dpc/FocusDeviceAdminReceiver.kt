package com.example.service.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
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
        try {
            val repo = com.example.data.AppRepository.getInstance(context)
            repo.repositoryScope.launch {
                repo.setSetting(com.example.data.AppRepository.KEY_UNINSTALL_PROTECTION, "true")
            }
        } catch (e: Exception) {
            // ignored intentionally
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        try {
            val repo = com.example.data.AppRepository.getInstance(context)
            repo.repositoryScope.launch {
                repo.setSetting(com.example.data.AppRepository.KEY_UNINSTALL_PROTECTION, "false")
            }
        } catch (e: Exception) {
            // ignored intentionally
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
    }

    companion object {
        private const val TAG = "FocusDeviceAdminReceiver"
    }
}
