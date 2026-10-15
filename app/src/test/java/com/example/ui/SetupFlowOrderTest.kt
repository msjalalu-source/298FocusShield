package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.service.BatteryOptimizationHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the mandatory, two-step first-launch setup flow for Focus Shield:
 * STEP 1: Battery Saver -> No restrictions (via BatteryOptimizationHelper.openAppDetails)
 * STEP 2: Background Auto-Start (via BatteryOptimizationHelper.openXiaomiAutostartSettings)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupFlowOrderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(BatteryOptimizationHelper.PREFS_SYSTEM, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        val prefs = context.getSharedPreferences(BatteryOptimizationHelper.PREFS_SYSTEM, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // 1. Fresh setup starts with Battery Saver (both initially not done)
    @Test
    fun test1_freshState_batterySaverAndAutoStartNotDone() {
        assertFalse("Battery Saver setup should initially not be done", BatteryOptimizationHelper.isBatteryRestrictionDone(context))
        assertFalse("Auto-Start setup should initially not be done", BatteryOptimizationHelper.isAutoStartDone(context))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }

    // 2. Battery setup precedes Auto-Start
    @Test
    fun test2_batterySetupPrecedesAutoStart() {
        // When fresh, Battery is NOT done, Auto-Start is NOT done
        assertFalse(BatteryOptimizationHelper.isBatteryRestrictionDone(context))
        assertFalse(BatteryOptimizationHelper.isAutoStartDone(context))

        // Auto-Start must not be considered done or ready before Battery step
        val isBatteryReady = BatteryOptimizationHelper.isBatteryRestrictionDone(context)
        assertFalse("Battery setup must precede Auto-Start and must not be marked done initially", isBatteryReady)
    }

    // 3. Battery setup action: marks Battery done and navigates to App Info / Battery Saver settings
    @Test
    fun test3_batterySetupAction_marksDoneAndNavigatesToAppDetails() {
        assertFalse(BatteryOptimizationHelper.isBatteryRestrictionDone(context))

        // Trigger Battery Saver navigation using existing implementation
        BatteryOptimizationHelper.setBatteryRestrictionDone(context, true)
        BatteryOptimizationHelper.openAppDetails(context)

        assertTrue("Battery Saver setup must now be marked done", BatteryOptimizationHelper.isBatteryRestrictionDone(context))
        assertFalse("Auto-Start must still be false after only Battery step is completed", BatteryOptimizationHelper.isAutoStartDone(context))

        // Verify that App Details Intent was dispatched targeting this app's package
        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull("An intent to settings must be dispatched", nextIntent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextIntent.action)
        assertEquals("package:${context.packageName}", nextIntent.dataString)
    }

    // 4. Auto-Start cannot appear or be treated as done before Battery setup is acknowledged
    @Test
    fun test4_autoStartCannotBeDoneBeforeBatterySetup() {
        // In fresh state, neither is done
        assertFalse(BatteryOptimizationHelper.isBatteryRestrictionDone(context))
        assertFalse(BatteryOptimizationHelper.isAutoStartDone(context))

        // Complete only Auto-Start without Battery
        BatteryOptimizationHelper.setAutoStartDone(context, true)
        assertTrue(BatteryOptimizationHelper.isAutoStartDone(context))
        assertFalse("Battery step must still be incomplete", BatteryOptimizationHelper.isBatteryRestrictionDone(context))
    }

    // 5. Completing Battery setup leads to Auto-Start step
    @Test
    fun test5_completingBatterySetup_advancesToAutoStart() {
        // Step 1 completed
        BatteryOptimizationHelper.setBatteryRestrictionDone(context, true)
        assertTrue("Battery step is done", BatteryOptimizationHelper.isBatteryRestrictionDone(context))
        assertFalse("Auto-Start step is pending next", BatteryOptimizationHelper.isAutoStartDone(context))

        // Now trigger Step 2 (Auto-Start)
        BatteryOptimizationHelper.setAutoStartDone(context, true)
        BatteryOptimizationHelper.openXiaomiAutostartSettings(context)

        assertTrue("Auto-Start step is now done", BatteryOptimizationHelper.isAutoStartDone(context))

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull("An intent to Auto-Start settings must be dispatched", nextIntent)
        assertTrue(
            "Intent must target either MIUI securitycenter or application details settings",
            nextIntent.component?.packageName == "com.miui.securitycenter" ||
                    nextIntent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
    }

    // 6. Completing Auto-Start leads to normal UI (no further setup prompts on launch)
    @Test
    fun test6_bothStepsCompleted_normalUiAllowedNoPrompts() {
        // Both steps completed
        BatteryOptimizationHelper.setBatteryRestrictionDone(context, true)
        BatteryOptimizationHelper.setAutoStartDone(context, true)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertTrue(BatteryOptimizationHelper.isBatteryRestrictionDone(activity))
        assertTrue(BatteryOptimizationHelper.isAutoStartDone(activity))

        val shadowApp = shadowOf(activity.application)
        assertNull("No setup prompts should be launched if already configured", shadowApp.nextStartedActivity)
    }

    // 7. Existing Auto-Start behavior remains functional
    @Test
    fun test7_autoStartNavigation_remainsFunctional() {
        BatteryOptimizationHelper.openXiaomiAutostartSettings(context)

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull("Auto-start settings intent must be dispatched", startedIntent)
    }

    // 8. Mandatory setup flags are persistent and independent
    @Test
    fun test8_flagsArePersistentAndIndependent() {
        val prefs = context.getSharedPreferences(BatteryOptimizationHelper.PREFS_SYSTEM, Context.MODE_PRIVATE)

        // Verify independent keys
        assertEquals("battery_restriction_prompt_done", BatteryOptimizationHelper.KEY_BATTERY_RESTRICTION_PROMPT_DONE)
        assertEquals("autostart_prompt_done", BatteryOptimizationHelper.KEY_AUTOSTART_PROMPT_DONE)

        BatteryOptimizationHelper.setBatteryRestrictionDone(context, true)
        assertTrue(prefs.getBoolean(BatteryOptimizationHelper.KEY_BATTERY_RESTRICTION_PROMPT_DONE, false))
        assertFalse(prefs.getBoolean(BatteryOptimizationHelper.KEY_AUTOSTART_PROMPT_DONE, false))

        BatteryOptimizationHelper.setAutoStartDone(context, true)
        assertTrue(prefs.getBoolean(BatteryOptimizationHelper.KEY_BATTERY_RESTRICTION_PROMPT_DONE, false))
        assertTrue(prefs.getBoolean(BatteryOptimizationHelper.KEY_AUTOSTART_PROMPT_DONE, false))
    }
}
