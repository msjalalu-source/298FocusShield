package com.example.service

import android.content.Context
import android.view.accessibility.AccessibilityEvent
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
class TelegramRememberProtectionDiagnosticTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RememberProtectionEngine.resetForTesting(context)
    }

    @Test
    fun testRememberProtection_OnlyMatchesNavigationPathPackageAndEventType() {
        // Configure Remember Protection target strictly using Navigation Path, Package Name, Event Type
        val target = SavedProtectionTarget(
            packageName = "org.thunderdog.challegram",
            navigationPath = "Search",
            eventType = "TYPE_VIEW_CLICKED"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        val safetyContext = SafetyProtectionContext(
            context = context,
            identity = FocusShieldIdentity.fromContext(context),
            performBack = { true }
        )

        // 1. Event without matching Navigation Path -> ALLOW (not blocked)
        val eventWithoutText = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "org.thunderdog.challegram"
            className = "android.widget.ImageView"
        }
        val decisionWithoutText = RememberProtectionEngine.evaluate(
            packageName = "org.thunderdog.challegram",
            event = eventWithoutText,
            context = safetyContext
        )
        assertTrue(decisionWithoutText is SafetyProtectionDecision.Allow)

        // 2. Event with matching contentDescription -> BLOCK
        val eventWithDesc = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "org.thunderdog.challegram"
            className = "android.widget.ImageView"
            contentDescription = "Search"
        }
        val decisionWithDesc = RememberProtectionEngine.evaluate(
            packageName = "org.thunderdog.challegram",
            event = eventWithDesc,
            context = safetyContext
        )
        assertTrue(decisionWithDesc is SafetyProtectionDecision.Block)

        // 3. Event with different event type (e.g. TYPE_VIEW_FOCUSED) -> ALLOW
        val eventFocused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply {
            packageName = "org.thunderdog.challegram"
            className = "android.widget.EditText"
            contentDescription = "Search"
        }
        val decisionFocused = RememberProtectionEngine.evaluate(
            packageName = "org.thunderdog.challegram",
            event = eventFocused,
            context = safetyContext
        )
        assertTrue(decisionFocused is SafetyProtectionDecision.Allow)

        // 4. Event with different package name -> ALLOW
        val eventDifferentPkg = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.other.app"
            className = "android.widget.ImageView"
            contentDescription = "Search"
        }
        val decisionDifferentPkg = RememberProtectionEngine.evaluate(
            packageName = "com.other.app",
            event = eventDifferentPkg,
            context = safetyContext
        )
        assertTrue(decisionDifferentPkg is SafetyProtectionDecision.Allow)
    }
}
