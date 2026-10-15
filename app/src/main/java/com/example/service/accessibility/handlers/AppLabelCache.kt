package com.example.service.accessibility.handlers

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache for resolved application labels.
 * Prevents synchronous PackageManager IPC from running on the Accessibility Service hot path.
 */
object AppLabelCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun get(packageName: String): String? = cache[packageName]

    fun put(packageName: String, label: String) {
        if (packageName.isNotBlank() && label.isNotBlank()) {
            cache[packageName] = label
        }
    }

    fun remove(packageName: String) {
        cache.remove(packageName)
    }

    fun clear() {
        cache.clear()
    }
}
