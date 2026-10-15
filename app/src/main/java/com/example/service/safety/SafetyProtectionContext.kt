package com.example.service.safety

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Encapsulates the execution context and environment for Safety Protection evaluations.
 */
class SafetyProtectionContext(
    val context: Context,
    val identity: FocusShieldIdentity = FocusShieldIdentity.fromContext(context),
    val performBack: () -> Boolean,
    val performHome: ((targetPackage: String?) -> Boolean)? = null
) {
    private var cachedEffectiveRoot: AccessibilityNodeInfo? = null
    private var isEffectiveRootResolved: Boolean = false
    private var isDerivedFromSource: Boolean = false

    /**
     * Resolves the effective root node safely from supplier or event source parent chain.
     */
    fun getEffectiveRootNode(
        rootSupplier: () -> AccessibilityNodeInfo?,
        event: AccessibilityEvent,
        sourceSupplier: (() -> AccessibilityNodeInfo?)? = null
    ): AccessibilityNodeInfo? {
        if (isEffectiveRootResolved) {
            return cachedEffectiveRoot
        }
        val root = try { rootSupplier() } catch (_: Exception) { null }
        if (root != null) {
            cachedEffectiveRoot = root
            isEffectiveRootResolved = true
            isDerivedFromSource = false
            return root
        }

        val source = try {
            sourceSupplier?.invoke() ?: event.source
        } catch (_: Exception) { null }
        if (source == null) {
            isEffectiveRootResolved = true
            cachedEffectiveRoot = null
            return null
        }
        var curr: AccessibilityNodeInfo = source
        var depth = 0
        val maxDepth = 20
        while (depth < maxDepth) {
            val parent = try { curr.parent } catch (_: Exception) { null } ?: break
            val isCycle = parent === curr || (try { parent == curr } catch (_: Throwable) { false })
            if (isCycle) break
            depth++
            val prev = curr
            curr = parent
            try { prev.recycle() } catch (_: Exception) {}
        }
        cachedEffectiveRoot = curr
        isEffectiveRootResolved = true
        isDerivedFromSource = true
        return curr
    }

    /**
     * Releases event-scoped resources. Recycles cached effective root if it was created
     * from climbing event.source (rootSupplier nodes are recycled by caller).
     */
    fun release() {
        if (isDerivedFromSource && cachedEffectiveRoot != null) {
            try {
                cachedEffectiveRoot?.recycle()
            } catch (_: Throwable) {}
        }
        cachedEffectiveRoot = null
        isEffectiveRootResolved = false
    }
}
