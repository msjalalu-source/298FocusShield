package com.example.service

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.accessibility.handlers.RememberProtectionHandler
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.safety.FocusShieldIdentity
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SafetyProtectionContext
import com.example.service.search.SearchDetectorRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurgicalOptimizationsVerificationTest {

    private lateinit var context: Context
    private lateinit var accessibilityService: FocusAccessibilityService
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    open class TestNode(
        val name: String,
        private val parentNode: AccessibilityNodeInfo? = null
    ) : AccessibilityNodeInfo() {
        var isRecycled = false
        override fun getParent(): AccessibilityNodeInfo? = parentNode
        override fun recycle() {
            isRecycled = true
        }
    }

    private fun createMockEvent(source: AccessibilityNodeInfo?): AccessibilityEvent {
        val event = AccessibilityEvent.obtain()
        if (source != null) {
            shadowOf(event).setSourceNode(source)
        }
        return event
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppRepository.resetForTesting()
        AppRepository.getDeviceProtectedPrefs(context).edit().clear().commit()
        CriticalProtectionBootstrap.resetForTesting()
        RememberProtectionEngine.resetForTesting(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
        accessibilityService = Robolectric.buildService(FocusAccessibilityService::class.java).get()
    }

    @After
    fun tearDown() {
        if (database.isOpen) {
            database.close()
        }
        RememberProtectionEngine.resetForTesting(context)
    }

    // =========================================================================
    // STEP 1: Cached Settings Tests
    // =========================================================================

    @Test
    fun testCachedSettingsInstanceStabilityAndSemantics() {
        val settingsStore = mutableMapOf("key_a" to "value_a", "key_b" to "value_b")
        val ctx = AccessibilityHandlerContext(
            service = accessibilityService,
            searchDetectorRouter = SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { key -> settingsStore[key] },
            isSettingEnabled = { key, defaultVal -> settingsStore[key]?.toBoolean() ?: defaultVal },
            performHomeAction = { true },
            performBackAction = { true },
            showToast = { _, _ -> }
        )

        // 1. Instance Stability: multiple accesses must return the EXACT same instance
        val map1 = ctx.cachedSettings
        val map2 = ctx.cachedSettings
        val map3 = ctx.cachedSettings
        assertSame("cachedSettings must be a stable instance and not allocate on every access", map1, map2)
        assertSame("cachedSettings must be a stable instance and not allocate on every access", map2, map3)

        // 2. Semantics: get and containsKey
        assertEquals("value_a", map1["key_a"])
        assertEquals("value_b", map1["key_b"])
        assertTrue(map1.containsKey("key_a"))
        assertFalse(map1.containsKey("key_c"))
        assertNull(map1["key_c"])

        // 3. Dynamic lookup without reallocation
        settingsStore["key_c"] = "value_c"
        assertTrue(map1.containsKey("key_c"))
        assertEquals("value_c", map1["key_c"])
    }

    private fun getSafetyContext(): SafetyProtectionContext {
        return SafetyProtectionContext(
            context = context,
            identity = FocusShieldIdentity.fromContext(context),
            performBack = { true },
            performHome = { true }
        )
    }

    // =========================================================================
    // STEP 2: getEffectiveRootNode Bounded & Safe Tests
    // =========================================================================

    @Test
    fun testGetEffectiveRootNodeRootSupplierTakesPrecedence() {
        val supplierRoot = TestNode("supplier_root")
        val event = createMockEvent(TestNode("ignored_source"))

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { supplierRoot },
            event = event
        )

        assertSame(supplierRoot, result)
    }

    @Test
    fun testGetEffectiveRootNodeNullParent() {
        val source = TestNode("single_leaf", null)
        val event = createMockEvent(source)

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { null },
            event = event,
            sourceSupplier = { source }
        )

        assertSame("When parent is null, source should be returned directly", source, result)
        assertFalse("Single leaf should not be recycled", source.isRecycled)
    }

    @Test
    fun testGetEffectiveRootNodeNormalHierarchyClimbsToRoot() {
        val p2 = TestNode("p2", null)
        val p1 = TestNode("p1", p2)
        val source = TestNode("source", p1)
        val event = createMockEvent(source)

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { null },
            event = event,
            sourceSupplier = { source }
        )

        assertSame("Must climb to top-most ancestor", p2, result)
        // Intermediate nodes must be recycled to prevent leak
        assertTrue("Source node must be recycled", source.isRecycled)
        assertTrue("P1 node must be recycled", p1.isRecycled)
        assertFalse("Root node (p2) must not be recycled", p2.isRecycled)
    }

    @Test
    fun testGetEffectiveRootNodeBoundedAt20Levels() {
        // Build a chain of 31 nodes (0 to 30)
        val chain = ArrayList<TestNode>()
        var parent: TestNode? = null
        for (i in 0..30) {
            val node = TestNode("level_$i", parent)
            chain.add(node)
            parent = node
        }
        // chain[30] is source. Climbing 20 parents stops at chain[10]
        val source = chain[30]
        val event = createMockEvent(source)

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { null },
            event = event,
            sourceSupplier = { source }
        )

        assertSame("Must terminate at depth 20", chain[10], result)
        assertFalse("Effective root must not be recycled", chain[10].isRecycled)
        assertTrue("Source must be recycled", source.isRecycled)
        assertTrue("Level 29 must be recycled", chain[29].isRecycled)
        assertTrue("Level 11 must be recycled", chain[11].isRecycled)
    }

    @Test
    fun testGetEffectiveRootNodeCyclicHierarchy() {
        class CyclicNode : TestNode("cyclic") {
            override fun getParent(): AccessibilityNodeInfo = this
        }
        val source = CyclicNode()
        val event = createMockEvent(source)

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { null },
            event = event,
            sourceSupplier = { source }
        )

        assertSame(source, result)
        assertFalse(source.isRecycled)
    }

    @Test
    fun testGetEffectiveRootNodeExceptionHandling() {
        class FailingNode : TestNode("failing") {
            override fun getParent(): AccessibilityNodeInfo = throw RuntimeException("Simulated Binder failure")
        }
        val source = FailingNode()
        val event = createMockEvent(source)

        val result = getSafetyContext().getEffectiveRootNode(
            rootSupplier = { null },
            event = event,
            sourceSupplier = { source }
        )

        assertSame("Safely returns current node without propagating exception", source, result)
        assertFalse(source.isRecycled)
    }

    // =========================================================================
    // STEP 3: Async Persistence & Non-Blocking Tests
    // =========================================================================

    @Test
    fun testRememberProtectionHandlerAsyncPersistenceAndNoBlocking() {
        val ctx = AccessibilityHandlerContext(
            service = accessibilityService,
            repositoryProvider = { repository },
            searchDetectorRouter = SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { key ->
                CriticalProtectionBootstrap.getSetting(key)
            },
            isSettingEnabled = { key, defaultVal -> defaultVal },
            performHomeAction = { true },
            performBackAction = { true },
            showToast = { _, _ -> },
            coroutineScope = testScope
        )

        val handler = RememberProtectionHandler { ctx }
        RememberProtectionEngine.setEnabled(true)

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.miui.securitycenter"
        }

        val mockRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.miui.securitycenter"
        }

        // Invoke handler - general content changes in Xiaomi Security should not block or crash
        val handled = handler.handleEvent(
            packageName = "com.miui.securitycenter",
            event = event,
            rootSupplier = { mockRoot }
        )

        // General content change without match is allowed
        assertFalse("General content changes without target must not block", handled)
    }

    @Test
    fun testRememberProtectionPersistenceExceptionDoesNotCrashService() {
        val ctx = AccessibilityHandlerContext(
            service = accessibilityService,
            repositoryProvider = { repository },
            searchDetectorRouter = SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { key ->
                CriticalProtectionBootstrap.getSetting(key)
            },
            isSettingEnabled = { key, defaultVal -> defaultVal },
            performHomeAction = { true },
            performBackAction = { true },
            showToast = { _, _ -> },
            coroutineScope = testScope
        )

        val handler = RememberProtectionHandler { ctx }
        RememberProtectionEngine.setEnabled(true)

        // Close database so Room write throws an IllegalStateException
        database.close()

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.miui.securitycenter"
        }
        val mockRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.miui.securitycenter"
        }

        // Should not crash even when DB is closed
        val handled = handler.handleEvent(
            packageName = "com.miui.securitycenter",
            event = event,
            rootSupplier = { mockRoot }
        )

        assertFalse("Unrelated event must not block", handled)
    }
}
