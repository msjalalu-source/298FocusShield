package com.example.service.search

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unified Search Detector Router.
 * Dispatches accessibility events to the appropriate SearchDetector implementation
 * based on the active application package name.
 */
class SearchDetectorRouter(
    customDetectors: List<SearchDetector>? = null
) {

    private val detectors = CopyOnWriteArrayList<SearchDetector>()
    private val detectorCache = ConcurrentHashMap<String, SearchDetector>()
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()

    init {
        if (customDetectors != null) {
            detectors.addAll(customDetectors)
        } else {
            detectors.add(YouTubeSearchDetector)
        }
    }

    /**
     * Finds the detector registered for the given package name.
     * Uses O(1) concurrent caching for both positive and negative results.
     */
    fun findDetector(packageName: String?): SearchDetector? {
        if (packageName.isNullOrBlank()) return null

        val cached = detectorCache[packageName]
        if (cached != null) return cached
        if (negativeCache.contains(packageName)) return null

        for (detector in detectors) {
            if (detector.canHandle(packageName)) {
                detectorCache[packageName] = detector
                return detector
            }
        }

        negativeCache.add(packageName)
        return null
    }

    /**
     * Checks if any registered detector handles the given package.
     */
    fun isSearchTarget(packageName: String?): Boolean {
        return findDetector(packageName) != null
    }

    /**
     * Extracts search query using the appropriate detector for the given event.
     */
    fun extractQuery(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): SearchDetector.ExtractedQuery? {
        val packageName = event.packageName?.toString() ?: return null
        val detector = findDetector(packageName) ?: return null
        return detector.extractSearchQuery(event, rootSupplier)
    }

    /**
     * Checks if the event is a search submission attempt.
     */
    fun isSearchSubmissionAttempt(
        event: AccessibilityEvent,
        rootSupplier: () -> AccessibilityNodeInfo?
    ): Boolean {
        val packageName = event.packageName?.toString() ?: return false
        val detector = findDetector(packageName) ?: return false
        return detector.isSearchSubmissionAttempt(event, rootSupplier)
    }

    /**
     * Registers a custom detector (useful for extensions and testing).
     */
    fun registerDetector(detector: SearchDetector) {
        if (!detectors.contains(detector)) {
            detectors.add(detector)
            clearCache()
        }
    }

    /**
     * Unregisters a detector.
     */
    fun unregisterDetector(detector: SearchDetector) {
        if (detectors.remove(detector)) {
            clearCache()
        }
    }

    /**
     * Clears cached resolution results.
     */
    fun clearCache() {
        detectorCache.clear()
        negativeCache.clear()
    }

    /**
     * Returns all registered detectors.
     */
    fun getRegisteredDetectors(): List<SearchDetector> = detectors.toList()

    /**
     * Alias for getRegisteredDetectors.
     */
    fun getDetectors(): List<SearchDetector> = getRegisteredDetectors()
}
