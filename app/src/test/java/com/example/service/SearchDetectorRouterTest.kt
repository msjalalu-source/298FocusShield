package com.example.service

import com.example.service.search.SearchBlockingTarget
import com.example.service.search.SearchDetectorRouter
import com.example.service.search.YouTubeSearchDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchDetectorRouterTest {

    @Test
    fun testRouterDefaultDetectors() {
        val router = SearchDetectorRouter()
        val detectors = router.getDetectors()
        assertEquals(1, detectors.size)
        assertTrue(detectors.contains(YouTubeSearchDetector))
    }

    @Test
    fun testFindDetectorForYouTube() {
        val router = SearchDetectorRouter()
        val detector = router.findDetector("com.google.android.youtube")
        assertNotNull(detector)
        assertEquals(SearchBlockingTarget.YOUTUBE, detector?.target)
        assertTrue(router.isSearchTarget("com.google.android.youtube"))
    }

    @Test
    fun testUnrecognizedPackageReturnsNull() {
        val router = SearchDetectorRouter()
        val detector = router.findDetector("com.example.unrelated.app")
        assertNull(detector)
        assertFalse(router.isSearchTarget("com.example.unrelated.app"))
    }

    @Test
    fun testRepeatedLookupReturnsCachedResult() {
        val router = SearchDetectorRouter()
        val first = router.findDetector("com.google.android.youtube")
        val second = router.findDetector("com.google.android.youtube")
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun testCachedNegativeLookup() {
        val router = SearchDetectorRouter()
        val nonSearchPkg = "com.android.settings"
        assertNull(router.findDetector(nonSearchPkg))
        assertFalse(router.isSearchTarget(nonSearchPkg))

        // Second lookup should hit negative cache
        assertNull(router.findDetector(nonSearchPkg))
        assertFalse(router.isSearchTarget(nonSearchPkg))
    }

    @Test
    fun testConcurrentLookups() {
        val router = SearchDetectorRouter()
        val threadCount = 8
        val iterations = 100
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        val packages = listOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.unknown.app1",
            "com.unknown.app2",
            "com.android.settings"
        )

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterations) {
                        for (pkg in packages) {
                            val detector = router.findDetector(pkg)
                            val isTarget = router.isSearchTarget(pkg)
                            if (pkg.contains("youtube")) {
                                if (detector == null || !isTarget) errors.incrementAndGet()
                            } else {
                                if (detector != null || isTarget) errors.incrementAndGet()
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errors.get())
    }

    @Test
    fun testCacheInvalidationOnRegisterAndUnregister() {
        val router = SearchDetectorRouter()
        val customPkg = "com.custom.video.search"
        val customDetector = object : com.example.service.search.SearchDetector {
            override val target: SearchBlockingTarget = SearchBlockingTarget.YOUTUBE
            override fun canHandle(packageName: String?): Boolean = packageName == customPkg
            override fun extractSearchQuery(
                event: android.view.accessibility.AccessibilityEvent,
                rootSupplier: () -> android.view.accessibility.AccessibilityNodeInfo?
            ): com.example.service.search.SearchDetector.ExtractedQuery? = null
            override fun isSearchSubmissionAttempt(
                event: android.view.accessibility.AccessibilityEvent,
                rootSupplier: () -> android.view.accessibility.AccessibilityNodeInfo?
            ): Boolean = false
        }

        // Before registration: should be negative cached
        assertNull(router.findDetector(customPkg))

        // Register custom detector
        router.registerDetector(customDetector)
        val resolved = router.findDetector(customPkg)
        assertNotNull(resolved)
        assertSame(customDetector, resolved)

        // Unregister: should invalidate cache and return null
        router.unregisterDetector(customDetector)
        assertNull(router.findDetector(customPkg))
    }
}
