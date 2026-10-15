package com.example.service.accessibility.handlers

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.safety.FocusShieldIdentity
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SafetyProtectionContext
import com.example.service.safety.SafetyProtectionDecision
import com.example.service.systemui.NavigationRestrictionEngine

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

    private fun isDuplicateEvent(packageName: String, event: AccessibilityEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val type = event.eventType
        val className = event.className?.toString() ?: ""

        val isDuplicate = (type == lastEventType &&
                packageName == lastEventPackage &&
                className == lastEventClass &&
                now - lastEventTime < 40L)

        if (isDuplicate) return true

        lastEventTime = now
        lastEventType = type
        lastEventPackage = packageName
        lastEventClass = className
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
                    Log.i(RememberProtectionEngine.TAG, "🛡️ [REMEMBER_BLOCK] Enforcing ${decision.targetName} (${decision.targetType}): ${decision.reason} action=${decision.actionType}")
                    when (decision.actionType) {
                        com.example.service.safety.BlockActionType.BACK_ONLY,
                        com.example.service.safety.BlockActionType.GLOBAL_BACK -> {
                            ctx.performBackAction()
                        }
                        com.example.service.safety.BlockActionType.NAVIGATION_HOME,
                        com.example.service.safety.BlockActionType.GLOBAL_HOME -> {
                            NavigationRestrictionEngine.executeNavigationPrevention(
                                targetName = decision.targetName,
                                performBack = { false },
                                performHome = { ctx.performHomeAction(packageName) }
                            )
                        }
                        else -> {
                            NavigationRestrictionEngine.executeNavigationPrevention(
                                targetName = decision.targetName,
                                performBack = ctx.performBackAction,
                                performHome = { ctx.performHomeAction(packageName) }
                            )
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
