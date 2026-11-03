package com.example.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary diagnostic helper to observe accessibility events and blocking attempts via Toast.
 * Set ENABLED to false to disable all diagnostics with zero overhead.
 */
object TempDiag {

    const val ENABLED = true

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Throttling maps
    private val lastMessageTime = ConcurrentHashMap<String, Long>()
    private val lastStatePackageTime = ConcurrentHashMap<String, Long>()

    fun onEventReceived(service: FocusAccessibilityService, event: AccessibilityEvent?) {
        if (!ENABLED) return
        try {
            if (event == null) return

            val eventType = event.eventType
            val isState = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val isClick = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED

            if (!isState && !isClick) return

            val rawPackage = event.packageName?.toString() ?: ""
            if (rawPackage.isEmpty()) return

            val ownPkg = service.packageName
            val ownAppPkg = service.applicationContext?.packageName
            if (rawPackage == ownPkg || rawPackage == ownAppPkg) return

            val now = SystemClock.elapsedRealtime()

            // Throttle: For STATE events from the same package, show at most once every 3 seconds
            if (isState) {
                val lastPkgTime = lastStatePackageTime[rawPackage] ?: 0L
                if (now - lastPkgTime < 3000L) {
                    return
                }
            }

            val typeStr = if (isState) "STATE" else "CLICK"
            val isLocked = service.isAppLocked(rawPackage)
            val lockedStr = if (isLocked) "YES" else "NO"

            val sb = StringBuilder()
            sb.append(rawPackage).append("\n")
            sb.append(typeStr).append(" | LOCKED: ").append(lockedStr)

            if (isClick) {
                val source: AccessibilityNodeInfo? = try {
                    event.source
                } catch (t: Throwable) {
                    null
                }

                if (source == null) {
                    sb.append("\nSOURCE: null")
                } else {
                    try {
                        val isPassword = try { source.isPassword } catch (t: Throwable) { false }
                        val nodeText = if (isPassword) {
                            "[hidden]"
                        } else {
                            source.text?.toString()?.take(30) ?: ""
                        }
                        val nodeDesc = source.contentDescription?.toString()?.take(30) ?: ""
                        val nodeId = source.viewIdResourceName?.take(30) ?: ""

                        sb.append("\ntext: ").append(nodeText)
                            .append(" | desc: ").append(nodeDesc)
                            .append(" | id: ").append(nodeId)
                    } finally {
                        try {
                            source.recycle()
                        } catch (t: Throwable) {
                            // ignored intentionally
                        }
                    }
                }
            }

            val message = sb.toString()
            if (showToastIfAllowed(service, message, now)) {
                if (isState) {
                    lastStatePackageTime[rawPackage] = now
                }
            }
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    fun onBlockAttempt(context: Context, result: Boolean, targetPackage: String? = null) {
        if (!ENABLED) return
        try {
            if (isOwnPackage(context, targetPackage)) return
            val msg = "BLOCK -> HOME, result: $result"
            showToastIfAllowed(context, msg, SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    fun onBlockSkipped(context: Context, reason: String, targetPackage: String? = null) {
        if (!ENABLED) return
        try {
            if (isOwnPackage(context, targetPackage)) return
            val msg = "BLOCK skipped: $reason"
            showToastIfAllowed(context, msg, SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun isOwnPackage(context: Context, targetPackage: String?): Boolean {
        if (targetPackage.isNullOrEmpty()) return false
        val ownPkg = context.packageName
        val ownAppPkg = context.applicationContext?.packageName
        return targetPackage == ownPkg || targetPackage == ownAppPkg
    }

    private fun showToastIfAllowed(context: Context, message: String, now: Long): Boolean {
        // Throttle: do not show the same message text again within 2 seconds
        val lastTime = lastMessageTime[message] ?: 0L
        if (now - lastTime < 2000L) {
            return false
        }
        lastMessageTime[message] = now

        // Cleanup stale entries occasionally to prevent memory growth
        if (lastMessageTime.size > 50) {
            lastMessageTime.entries.removeIf { now - it.value > 10_000L }
        }
        if (lastStatePackageTime.size > 50) {
            lastStatePackageTime.entries.removeIf { now - it.value > 10_000L }
        }

        try {
            val appContext = context.applicationContext ?: context
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            } else {
                mainHandler.post {
                    try {
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        // ignored intentionally
                    }
                }
            }
            return true
        } catch (t: Throwable) {
            return false
        }
    }
}
