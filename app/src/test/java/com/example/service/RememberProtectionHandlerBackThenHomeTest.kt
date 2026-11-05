package com.example.service

import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.accessibility.handlers.RememberProtectionHandler
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SavedProtectionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberProtectionHandlerBackThenHomeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RememberProtectionEngine.resetForTesting(context)
        RememberProtectionEngine.setEnabled(true)
    }

    @Test
    fun testRememberProtectionBlock_ExecutesBackImmediately_ThenHomeAfter150ms() {
        var backActionCount = 0
        var homeActionCount = 0
        var homeActionTargetPackage = ""

        val service = org.robolectric.Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
        val handlerContext = AccessibilityHandlerContext(
            service = service,
            repositoryProvider = { null },
            searchDetectorRouter = com.example.service.search.SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { null },
            isSettingEnabled = { _, defaultVal -> defaultVal },
            performHomeAction = { targetPkg ->
                homeActionCount++
                homeActionTargetPackage = targetPkg ?: ""
                true
            },
            performBackAction = {
                backActionCount++
                true
            },
            showToast = { _, _ -> },
            coroutineScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val handler = RememberProtectionHandler { handlerContext }

        // Configure a target
        val target = SavedProtectionTarget(
            packageName = "com.android.settings",
            navigationPath = "Battery",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        // Create matching click event
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.android.settings"
            text.add("Battery")
        }

        val handled = handler.handleEvent(
            packageName = "com.android.settings",
            event = event,
            rootSupplier = { null }
        )

        assertTrue("Matching event must be handled by Remember Protection", handled)
        assertEquals("BACK action must be called immediately once upon block", 1, backActionCount)
        assertEquals("HOME action must not be executed immediately (delayed by 150ms)", 0, homeActionCount)

        // Advance main looper by 100ms (before 150ms threshold)
        shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        assertEquals("HOME action must not have executed yet at 100ms", 0, homeActionCount)

        // Advance main looper past 150ms
        shadowOf(Looper.getMainLooper()).idleFor(60L, TimeUnit.MILLISECONDS)
        assertEquals("HOME action must be executed after 150ms", 1, homeActionCount)
        assertEquals("com.android.settings", homeActionTargetPackage)
    }

    @Test
    fun testCancelPending_PreventsDelayedHomeExecution() {
        var backActionCount = 0
        var homeActionCount = 0

        val service = org.robolectric.Robolectric.buildService(FocusAccessibilityService::class.java).create().get()
        val handlerContext = AccessibilityHandlerContext(
            service = service,
            repositoryProvider = { null },
            searchDetectorRouter = com.example.service.search.SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { null },
            isSettingEnabled = { _, defaultVal -> defaultVal },
            performHomeAction = { _ ->
                homeActionCount++
                true
            },
            performBackAction = {
                backActionCount++
                true
            },
            showToast = { _, _ -> },
            coroutineScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val handler = RememberProtectionHandler { handlerContext }

        val target = SavedProtectionTarget(
            packageName = "com.android.settings",
            navigationPath = "Battery",
            eventType = "Type View Click"
        )
        RememberProtectionEngine.saveAllTargets(context, listOf(target))

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.android.settings"
            text.add("Battery")
        }

        handler.handleEvent(
            packageName = "com.android.settings",
            event = event,
            rootSupplier = { null }
        )

        assertEquals(1, backActionCount)
        assertEquals(0, homeActionCount)

        // Cancel pending delayed HOME
        handler.cancelPending()

        // Advance main looper past 150ms
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        assertEquals("HOME action must NOT execute after cancelPending()", 0, homeActionCount)
    }
}
