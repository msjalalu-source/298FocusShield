package com.example.service.search

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Resilient, multi-version YouTube search detector.
 */
object YouTubeSearchDetector : BaseMediaSearchDetector() {

    override val target: SearchBlockingTarget = SearchBlockingTarget.YOUTUBE

    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.kids",
        "com.google.android.youtube.music",
        "com.google.android.apps.youtube.creator",
        "com.google.android.apps.youtube.unplugged"
    )

    override fun canHandle(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return YOUTUBE_PACKAGES.contains(packageName) ||
                packageName.startsWith("com.google.android.youtube") ||
                packageName.contains(".youtube")
    }

    override fun isSearchSubmitButton(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        return viewId.contains("search_btn") || viewId.contains("search_button") || viewId.contains("submit")
    }
}
