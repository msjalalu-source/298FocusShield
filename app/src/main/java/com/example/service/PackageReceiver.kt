package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.AppRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Authoritative single BroadcastReceiver handling Android package lifecycle:
 * - ACTION_PACKAGE_ADDED: Detects new installations and applies security policies.
 * - ACTION_PACKAGE_REPLACED: Handles app updates without resetting lock/timer states.
 * - ACTION_PACKAGE_REMOVED: Handles uninstalls cleanly.
 *
 * NOTE: On Android 8.0+ (API 26+), ACTION_PACKAGE_ADDED is not delivered to manifest-declared
 * receivers due to background execution limits. Dynamic registration via register(context)
 * is required for the OS to deliver package events at runtime.
 */
class PackageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReceiver"
        private val isRegistered = AtomicBoolean(false)
        private val receiverInstance by lazy { PackageReceiver() }
        private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /**
         * Dynamically registers PackageReceiver for runtime package events.
         * Safe to call multiple times (idempotent).
         */
        fun register(context: Context) {
            if (isRegistered.getAndSet(true)) return
            try {
                val appContext = context.applicationContext ?: context
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
                ContextCompat.registerReceiver(
                    appContext,
                    receiverInstance,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                Log.i(TAG, "✅ Dynamically registered PackageReceiver for package lifecycle events (API ${Build.VERSION.SDK_INT})")
            } catch (e: Exception) {
                isRegistered.set(false)
                Log.e(TAG, "❌ Failed to dynamically register PackageReceiver", e)
            }
        }

        fun unregister(context: Context) {
            if (!isRegistered.getAndSet(false)) return
            try {
                val appContext = context.applicationContext ?: context
                appContext.unregisterReceiver(receiverInstance)
                Log.i(TAG, "Unregistered dynamic PackageReceiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister dynamic PackageReceiver", e)
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun isDynamicallyRegistered(): Boolean = isRegistered.get()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val packageName = intent.data?.schemeSpecificPart
            ?: intent.data?.encodedSchemeSpecificPart
            ?: return

        if (packageName.isBlank() || packageName == context.packageName) return
        if (action != Intent.ACTION_PACKAGE_ADDED &&
            action != Intent.ACTION_PACKAGE_REPLACED &&
            action != Intent.ACTION_PACKAGE_REMOVED
        ) {
            return
        }

        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val isFeatureOn = NewAppBlockStore.isFeatureEnabled(context)

        Log.d(TAG, "Received action: $action, package: $packageName, replacing: $isReplacing, featureEnabled: $isFeatureOn")

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (!isReplacing && !NewAppBlockStore.isExemptPackage(packageName) && isFeatureOn) {
                    NewAppBlockStore.addBlockedApp(context, packageName)
                }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                NewAppBlockStore.onPackageReplaced(context, packageName)
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                if (!isReplacing) {
                    NewAppBlockStore.removeApp(context, packageName)
                }
            }
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val repository = AppRepository.getInstance(context)

                when (action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (!isReplacing && !NewAppBlockStore.isExemptPackage(packageName)) {
                            repository.handleNewlyInstalledPackage(packageName)
                        }
                    }
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        repository.onPackageReplaced(packageName)
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        repository.onPackageRemoved(packageName, isReplacing)
                    }
                }
            } catch (e: Exception) {
                Log.e("PackageReceiver", "Error processing package event $action for $packageName", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
