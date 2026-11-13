package com.example.service.accessibility.handlers

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.safety.FocusShieldIdentity
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SafetyProtectionContext
import com.example.service.safety.SafetyProtectionDecision

/**
 * Dedicated handler routing Accessibility events into the Remember Protection engine.
 */
class RememberProtectionHandler(
    private val contextProvider: () -> AccessibilityHandlerContext
) : AccessibilityHandler {

    @Volatile private var lastEventTime = 0L
    @Volatile private var lastEventType = 0
    @Volatile private var lastEventPackage = ""
    @Volatile private var lastEventClass = ""
    @Volatile private var lastEventIdentity = ""

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pendingDelayedHomeRunnable: Runnable? = null

    fun cancelPending() {
        pendingDelayedHomeRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDelayedHomeRunnable = null
        }
    }

    private fun extractEventIdentity(event: AccessibilityEvent): String {
        val eventText = if (!event.text.isNullOrEmpty()) event.text.joinToString(",") { it.toString().trim() } else ""
        val eventDesc = event.contentDescription?.toString()?.trim().orEmpty()

        val (nodeViewId, nodeText, nodeDesc) = try {
            val s = event.source
            if (s != null) {
                try {
                    Triple(
                        s.viewIdResourceName?.trim().orEmpty(),
                        s.text?.toString()?.trim().orEmpty(),
                        s.contentDescription?.toString()?.trim().orEmpty()
                    )
                } finally {
                    try { s.recycle() } catch (_: Exception) {}
                }
            } else {
                Triple("", "", "")
            }
        } catch (_: Exception) {
            Triple("", "", "")
        }

        val effectiveText = if (nodeText.isNotEmpty()) nodeText else eventText
        val effectiveDesc = if (nodeDesc.isNotEmpty()) nodeDesc else eventDesc

        return "$nodeViewId|$effectiveText|$effectiveDesc"
    }

    internal fun isDuplicateEvent(packageName: String, event: AccessibilityEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val type = event.eventType
        val className = event.className?.toString() ?: ""
        val identity = extractEventIdentity(event)

        val isDuplicate = (type == lastEventType &&
                packageName == lastEventPackage &&
                className == lastEventClass &&
                identity == lastEventIdentity &&
                now - lastEventTime < 40L)

        if (isDuplicate) return true

        lastEventTime = now
        lastEventType = type
        lastEventPackage = packageName
        lastEventClass = className
        lastEventIdentity = identity
        return false
    }

    override fun canHandle(packageName: String, event: AccessibilityEvent): Boolean {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            return false
        }
        val ctx = contextProvider()
        if (!RememberProtectionEngine.isRememberProtectionActive(ctx.context)) return false
        return RememberProtectionEngine.isCandidatePackage(ctx.context, packageName)
    }

    override fun handleEvent(
        packageName: String,
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        val ctx = contextProvider()
        if (!RememberProtectionEngine.isRememberProtectionActive(ctx.context)) return false

        if (isDuplicateEvent(packageName, event)) {
            return false
        }

        val safetyContext = SafetyProtectionContext(
            context = ctx.context,
            identity = FocusShieldIdentity.fromContext(ctx.context),
            performBack = ctx.performBackAction,
            performHome = { targetPkg -> ctx.performHomeAction(targetPkg) }
        )

        try {
            val decision = RememberProtectionEngine.evaluate(
                packageName = packageName,
                event = event,
                rootSupplier = rootSupplier,
                context = safetyContext
            )

            return when (decision) {
                is SafetyProtectionDecision.Block -> {
                    when (decision.actionType) {
                        com.example.service.safety.BlockActionType.BACK_ONLY,
                        com.example.service.safety.BlockActionType.GLOBAL_BACK -> {
                            ctx.performBackAction()
                        }
                        else -> {
                            ctx.performBackAction()
                            cancelPending()
                            val delayedHome = Runnable {
                                pendingDelayedHomeRunnable = null
                                try {
                                    ctx.performHomeAction(packageName)
                                } catch (_: Throwable) {
                                    // ignored intentionally
                                }
                            }
                            pendingDelayedHomeRunnable = delayedHome
                            mainHandler.postDelayed(delayedHome, 150L)
                        }
                    }
                    true
                }
                is SafetyProtectionDecision.Allow -> {
                    false
                }
            }
        } finally {
            safetyContext.release()
        }
    }
}
