package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SavedProtectionTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage proving Remember Protection operates standalone and independently
 * after the complete removal of the UI Element Picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberProtectionStandaloneTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RememberProtectionEngine.saveAllTargets(context, emptyList())
    }

    @Test
    fun testRememberProtection_initializesStandalone() {
        val targets = RememberProtectionEngine.getSavedTargets(context)
        assertNotNull(targets)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun testRememberProtection_savesAndRetrievesTargetsWithoutPicker() {
        val target = SavedProtectionTarget(
            navigationPath = "Settings > Display",
            packageName = "com.android.settings",
            eventType = "Type View Click"
        )

        val updatedList = listOf(target)
        RememberProtectionEngine.saveAllTargets(context, updatedList)

        val retrieved = RememberProtectionEngine.getSavedTargets(context)
        assertEquals(1, retrieved.size)
        assertEquals("com.android.settings", retrieved[0].packageName)
        assertEquals("Settings > Display", retrieved[0].navigationPath)
        assertEquals("Type View Click", retrieved[0].eventType)
    }

    @Test
    fun testRememberProtection_matchesTargetCriteria() {
        val target = SavedProtectionTarget(
            navigationPath = "Apps & notifications",
            packageName = "com.android.settings",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        val retrieved = RememberProtectionEngine.getSavedTargets(context)
        assertEquals(1, retrieved.size)

        val exists = retrieved.any {
            it.packageName.equals("com.android.settings", ignoreCase = true) &&
                    it.navigationPath.equals("Apps & notifications", ignoreCase = true) &&
                    it.eventType.equals("Type View Click", ignoreCase = true)
        }
        assertTrue("Saved protection target must match configured criteria", exists)
    }

    @Test
    fun testRememberProtection_targetWithResourceIdentifier() {
        val target = SavedProtectionTarget(
            navigationPath = "org.thunderdog.challegram:id/menu_btn_search",
            packageName = "org.thunderdog.challegram",
            eventType = "TYPE_VIEW_CLICKED"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        val retrieved = RememberProtectionEngine.getSavedTargets(context)
        assertEquals(1, retrieved.size)
        assertEquals("org.thunderdog.challegram:id/menu_btn_search", retrieved[0].navigationPath)
        assertEquals("org.thunderdog.challegram", retrieved[0].packageName)
    }
}
