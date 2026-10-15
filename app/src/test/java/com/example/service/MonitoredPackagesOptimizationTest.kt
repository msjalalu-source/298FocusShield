package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.bootstrap.CriticalProtectionBootstrap
import org.junit.After
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
class MonitoredPackagesOptimizationTest {

    private lateinit var context: Context
    private lateinit var accessibilityService: FocusAccessibilityService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        CriticalProtectionBootstrap.resetForTesting()
        com.example.service.safety.RememberProtectionEngine.resetForTesting(context)
        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
    }

    @After
    fun tearDown() {
        CriticalProtectionBootstrap.resetForTesting()
        com.example.service.safety.RememberProtectionEngine.resetForTesting(context)
    }

    @Test
    fun testEmptyPackageReturnsFalse() {
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(""))
    }

    @Test
    fun testLockedAppInConsolidatedSet() {
        val lockedPkg = "com.monitored.lockedapp"
        accessibilityService.setCachedLockedAppsForTesting(mapOf(lockedPkg to "Locked App"))

        val monitoredSet = accessibilityService.getMonitoredPackagesSetForTesting()
        assertTrue("Consolidated set must contain locked package", monitoredSet.contains(lockedPkg))
        assertTrue("isPotentiallyProtectedOrMonitored must return true for locked package",
            accessibilityService.isPotentiallyProtectedOrMonitored(lockedPkg))
    }

    @Test
    fun testNewInstallBlockedAppInConsolidatedSet() {
        val newInstallPkg = "com.monitored.newinstall"
        accessibilityService.setCachedNewInstalledBlockedAppsForTesting(setOf(newInstallPkg))

        val monitoredSet = accessibilityService.getMonitoredPackagesSetForTesting()
        assertTrue("Consolidated set must contain new install blocked package", monitoredSet.contains(newInstallPkg))
        assertTrue("isPotentiallyProtectedOrMonitored must return true for new install blocked package",
            accessibilityService.isPotentiallyProtectedOrMonitored(newInstallPkg))
    }

    @Test
    fun testBootstrapLockedAndNewInstallInConsolidatedSet() {
        val bootstrapLockedPkg = "com.bootstrap.lockedapp"
        val bootstrapNewInstallPkg = "com.bootstrap.newinstall"
        CriticalProtectionBootstrap.addDiscoveredLockedApp(bootstrapLockedPkg, "Bootstrap App")
        NewAppBlockStore.addBlockedApp(context, bootstrapNewInstallPkg)

        accessibilityService.rebuildMonitoredPackagesSetForTesting()

        val monitoredSet = accessibilityService.getMonitoredPackagesSetForTesting()
        assertTrue(monitoredSet.contains(bootstrapLockedPkg))
        assertTrue(monitoredSet.contains(bootstrapNewInstallPkg))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(bootstrapLockedPkg))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(bootstrapNewInstallPkg))
    }

    @Test
    fun testSystemUiPackageReturnsTrue() {
        val systemUiPkg = "com.android.systemui"
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(systemUiPkg))
    }

    @Test
    fun testSearchTargetPackageReturnsTrue() {
        val youtubePkg = "com.google.android.youtube"
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(youtubePkg))
    }

    @Test
    fun testRememberProtectionCandidateReturnsTrueWhenActive() {
        com.example.service.safety.RememberProtectionEngine.setEnabled(true)
        val settingsPkg = "com.android.settings"
        val miuiSecurityPkg = "com.miui.securitycenter"
        com.example.service.safety.RememberProtectionEngine.addSavedTargetForTesting(
            com.example.service.safety.SavedProtectionTarget(
                packageName = settingsPkg,
                title = "Settings",
                path = "Settings",
                id = "settings_test_target"
            )
        )
        com.example.service.safety.RememberProtectionEngine.addSavedTargetForTesting(
            com.example.service.safety.SavedProtectionTarget(
                packageName = miuiSecurityPkg,
                title = "Security",
                path = "Security",
                id = "security_test_target"
            )
        )
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(settingsPkg))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(miuiSecurityPkg))
    }

    @Test
    fun testRememberProtectionCandidateReturnsFalseWhenDisabled() {
        com.example.service.safety.RememberProtectionEngine.setEnabled(false)
        val settingsPkg = "com.android.settings"
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(settingsPkg))
    }

    @Test
    fun testInstallerAndVendingPackageReturnsTrue() {
        val installerPkg = "com.android.packageinstaller"
        val vendingPkg = "com.android.vending"
        val googleInstallerPkg = "com.google.android.packageinstaller"
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(installerPkg))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(vendingPkg))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(googleInstallerPkg))
    }

    @Test
    fun testUnmonitoredPackageReturnsFalse() {
        val unmonitoredPkg = "com.unmonitored.calculator"
        val randomGamePkg = "com.random.puzzle.game"
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(unmonitoredPkg))
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(randomGamePkg))
    }

    @Test
    fun testDynamicSyncOnPackageInstalled() {
        val newApp = "com.dynamic.newapp"
        FocusAccessibilityService.notifyPackageInstalled(newApp, shouldBlock = true)

        val monitoredSet = accessibilityService.getMonitoredPackagesSetForTesting()
        assertTrue("Newly notified blocked package must be in consolidated set", monitoredSet.contains(newApp))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(newApp))
    }

    @Test
    fun testConsolidatedSetUpdatesAtomically() {
        val pkgA = "com.app.a"
        val pkgB = "com.app.b"
        accessibilityService.setCachedLockedAppsForTesting(mapOf(pkgA to "App A"))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(pkgA))
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(pkgB))

        // Replace with new set
        accessibilityService.setCachedLockedAppsForTesting(mapOf(pkgB to "App B"))
        assertFalse(accessibilityService.isPotentiallyProtectedOrMonitored(pkgA))
        assertTrue(accessibilityService.isPotentiallyProtectedOrMonitored(pkgB))
    }
}
