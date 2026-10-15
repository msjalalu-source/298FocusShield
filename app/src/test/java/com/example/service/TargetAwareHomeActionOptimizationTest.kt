package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.accessibility.handlers.performHomeAction
import com.example.service.protection.CommonBlockingAction
import com.example.service.protection.ProtectionDecision
import com.example.service.protection.ProtectionRequest
import com.example.service.protection.ProtectionType
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetAwareHomeActionOptimizationTest {

    private lateinit var context: Context
    private lateinit var accessibilityService: FocusAccessibilityService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
        accessibilityService.resetHomeActionCooldownsForTesting()
    }

    /**
     * Case A & Case D: Explicit package is preferred; stale currentForegroundPackage cannot override it.
     */
    @Test
    fun testExplicitTargetPreferredOverStaleForeground() {
        accessibilityService.setCurrentForegroundPackageForTesting("com.stale.package")

        // Package A triggers a block while currentForegroundPackage is still Package B
        val result = accessibilityService.handlerContext.performHomeAction("com.explicit.package")
        assertTrue("Explicit target Home action should succeed", result)

        val explicitTime = accessibilityService.getLastHomeActionTime("com.explicit.package")
        val staleTime = accessibilityService.getLastHomeActionTime("com.stale.package")

        assertTrue("Cooldown must be recorded for explicit target package", explicitTime > 0L)
        assertEquals("Cooldown must NOT be recorded for stale foreground package", 0L, staleTime)
    }

    /**
     * Case B: Blank explicit package preserves foreground fallback.
     */
    @Test
    fun testBlankTargetPreservesForegroundFallback() {
        accessibilityService.setCurrentForegroundPackageForTesting("com.active.foreground")

        val result = accessibilityService.handlerContext.performHomeAction("")
        assertTrue("Fallback Home action should succeed", result)

        val fallbackTime = accessibilityService.getLastHomeActionTime("com.active.foreground")
        assertTrue("Cooldown must be keyed to current foreground package on blank input", fallbackTime > 0L)

        // Whitespace-only string should also fallback
        val whitespaceResult = accessibilityService.handlerContext.performHomeAction("   ")
        assertFalse("Subsequent blank action within 1000ms should be suppressed by fallback cooldown", whitespaceResult)
    }

    /**
     * Case C: Null explicit package preserves foreground fallback.
     */
    @Test
    fun testNullTargetPreservesForegroundFallback() {
        accessibilityService.setCurrentForegroundPackageForTesting("com.active.foreground")

        // 1. Explicit null argument
        val result = accessibilityService.handlerContext.performHomeAction(null)
        assertTrue("Null target Home action should succeed via fallback", result)

        val fallbackTime = accessibilityService.getLastHomeActionTime("com.active.foreground")
        assertTrue("Cooldown must be keyed to current foreground package on null input", fallbackTime > 0L)

        // 2. Extension function with zero arguments
        accessibilityService.resetHomeActionCooldownsForTesting()
        val noArgResult = accessibilityService.handlerContext.performHomeAction()
        assertTrue("Zero-argument extension should succeed via fallback", noArgResult)
        assertTrue(
            "Cooldown must be recorded for foreground package",
            accessibilityService.getLastHomeActionTime("com.active.foreground") > 0L
        )
    }

    /**
     * Case F: Same package repeated within cooldown is suppressed.
     */
    @Test
    fun testSamePackageCooldownSuppression() {
        accessibilityService.setCurrentForegroundPackageForTesting("com.other.package")

        val firstResult = accessibilityService.handlerContext.performHomeAction("com.target.app")
        assertTrue("First action for target app should succeed", firstResult)

        val recordedTime = accessibilityService.getLastHomeActionTime("com.target.app")
        assertTrue(recordedTime > 0L)

        val secondResult = accessibilityService.handlerContext.performHomeAction("com.target.app")
        assertFalse("Repeated action for same package within cooldown must be suppressed", secondResult)
        assertEquals(
            "Timestamp should remain unchanged after suppressed action",
            recordedTime,
            accessibilityService.getLastHomeActionTime("com.target.app")
        )
    }

    /**
     * Case E: Different packages have independent cooldowns.
     * Package A triggers Home action, and immediately Package B triggers Home action.
     * Package B must NOT be suppressed by Package A.
     */
    @Test
    fun testDifferentPackageCooldownIndependence() {
        val resultA = accessibilityService.handlerContext.performHomeAction("com.package.alpha")
        assertTrue("Action for Package A should succeed", resultA)

        val resultB = accessibilityService.handlerContext.performHomeAction("com.package.beta")
        assertTrue("Action for Package B immediately following A must NOT be suppressed", resultB)

        val timeA = accessibilityService.getLastHomeActionTime("com.package.alpha")
        val timeB = accessibilityService.getLastHomeActionTime("com.package.beta")
        assertTrue(timeA > 0L)
        assertTrue(timeB > 0L)

        // Both should now be individually in cooldown
        assertFalse(accessibilityService.handlerContext.performHomeAction("com.package.alpha"))
        assertFalse(accessibilityService.handlerContext.performHomeAction("com.package.beta"))
    }

    /**
     * Fallback when both target package and current foreground are blank uses "unknown" without crashing.
     */
    @Test
    fun testUnknownFallbackWhenNoForeground() {
        accessibilityService.setCurrentForegroundPackageForTesting("")

        val result = accessibilityService.handlerContext.performHomeAction(null)
        assertTrue("Action should succeed using unknown fallback", result)

        val unknownTime = accessibilityService.getLastHomeActionTime("unknown")
        assertTrue("Cooldown should be recorded under 'unknown'", unknownTime > 0L)
    }

    /**
     * Verifies that CommonBlockingAction forwards request.packageName into performHome.
     */
    @Test
    fun testCommonBlockingActionPassesExplicitTarget() {
        val capturedTarget = AtomicReference<String?>(null)
        val blockingAction = CommonBlockingAction(
            performBack = { false }, // Back returns false to trigger fallback to Home
            performHome = { target ->
                capturedTarget.set(target)
                true
            }
        )

        val request = ProtectionRequest.SearchKeyword(
            packageName = "com.google.android.youtube",
            target = com.example.service.search.SearchBlockingTarget.YOUTUBE,
            query = "test",
            matchedKeyword = "test"
        )
        val decision = ProtectionDecision.Block(
            type = ProtectionType.SEARCH_KEYWORD,
            reason = "Test"
        )

        blockingAction.execute(request, decision)

        assertEquals(
            "CommonBlockingAction must pass request.packageName to performHome",
            "com.google.android.youtube",
            capturedTarget.get()
        )
    }
}
