package com.example.service

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.service.safety.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationClickRememberProtectionTest {

    private lateinit var context: Context
    private lateinit var safetyContext: SafetyProtectionContext

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RememberProtectionEngine.resetForTesting(context)
        safetyContext = SafetyProtectionContext(
            context = context,
            identity = FocusShieldIdentity.fromContext(context),
            performBack = { true }
        )
    }

    @Test
    fun testNotificationClick_MatchesDescendantPackageAndText() {
        // Configure Remember Protection target for supervision limit
        val target = SavedProtectionTarget(
            packageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Create simulated System UI click event
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.android.systemui"
            className = "android.widget.FrameLayout"
        }

        // Test using snapshot matching directly
        val snapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.android.systemui",
                text = "",
                contentDescription = ""
            ),
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.google.android.gms.supervision",
                text = "20 min limit set for NewPipe",
                contentDescription = ""
            )
        )

        val matches = RememberProtectionEngine.matchesSnapshot(
            snapshots = snapshots,
            targetPackageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            isSystemUiEvent = true
        )
        assertTrue("Notification descendant with matching package and text must match", matches)

        // Evaluate via RememberProtectionEngine with pre-cached snapshots
        val isNavMatched = RememberProtectionEngine.isNavigationPathMatching(
            event = event,
            navigationPath = "20 min limit set for NewPipe",
            targetPackageName = "com.google.android.gms.supervision",
            isSystemUiEvent = true,
            cachedSnapshots = snapshots
        )
        assertTrue("isNavigationPathMatching must succeed for System UI notification click", isNavMatched)
    }

    @Test
    fun testNotificationClick_UnrelatedPackageInChild_IsNotBlocked() {
        val target = SavedProtectionTarget(
            packageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Child node belongs to WhatsApp, not supervision
        val snapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.whatsapp",
                text = "20 min limit set for NewPipe",
                contentDescription = ""
            )
        )

        val matches = RememberProtectionEngine.matchesSnapshot(
            snapshots = snapshots,
            targetPackageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            isSystemUiEvent = true
        )
        assertFalse("Unrelated package in descendant must NOT match target", matches)
    }

    @Test
    fun testNotificationClick_MatchingPackageDifferentText_IsNotBlocked() {
        val target = SavedProtectionTarget(
            packageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Child node belongs to supervision, but text is different
        val snapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.google.android.gms.supervision",
                text = "Account settings updated",
                contentDescription = ""
            )
        )

        val matches = RememberProtectionEngine.matchesSnapshot(
            snapshots = snapshots,
            targetPackageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            isSystemUiEvent = true
        )
        assertFalse("Different text must NOT match target", matches)
    }

    @Test
    fun testUnrelatedSystemUiClick_IsNotBlocked() {
        val target = SavedProtectionTarget(
            packageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Quick Settings Wi-Fi toggle in System UI
        val snapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.android.systemui",
                text = "Wi-Fi",
                contentDescription = "Wi-Fi, Connected"
            )
        )

        val matches = RememberProtectionEngine.matchesSnapshot(
            snapshots = snapshots,
            targetPackageName = "com.google.android.gms.supervision",
            navigationPath = "20 min limit set for NewPipe",
            isSystemUiEvent = true
        )
        assertFalse("Unrelated System UI click must NOT match supervision target", matches)
    }

    @Test
    fun testOrdinaryInAppTarget_ContinuesWorking() {
        val target = SavedProtectionTarget(
            packageName = "com.android.settings",
            navigationPath = "Manage apps",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Direct matching via event text
        val eventWithText = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.android.settings"
            text.add("Manage apps")
        }

        val decision = RememberProtectionEngine.evaluate(
            packageName = "com.android.settings",
            event = eventWithText,
            context = safetyContext
        )
        assertTrue("In-app matching must trigger Block decision", decision is SafetyProtectionDecision.Block)

        // Descendant matching for in-app clickable row containing child text
        val eventEmptyText = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.android.settings"
        }
        val inAppSnapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.android.settings",
                text = "Manage apps",
                contentDescription = ""
            )
        )
        val inAppMatched = RememberProtectionEngine.matchesSnapshot(
            snapshots = inAppSnapshots,
            targetPackageName = "com.android.settings",
            navigationPath = "Manage apps",
            isSystemUiEvent = false
        )
        assertTrue("In-app child text matching must succeed", inAppMatched)
    }

    @Test
    fun testGenericBehavior_WithCustomPackageAndText() {
        // Fully generic test with arbitrary package and custom text
        val target = SavedProtectionTarget(
            packageName = "com.custom.familyapp",
            navigationPath = "Dismiss warning",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        val matchingSnapshots = listOf(
            RememberProtectionEngine.NodeTextSnapshot(
                packageName = "com.custom.familyapp",
                text = "Warning: 10 mins remaining. Dismiss warning to proceed.",
                contentDescription = ""
            )
        )
        val matches = RememberProtectionEngine.matchesSnapshot(
            snapshots = matchingSnapshots,
            targetPackageName = "com.custom.familyapp",
            navigationPath = "Dismiss warning",
            isSystemUiEvent = true
        )
        assertTrue("Generic custom package and text must match", matches)
    }
}
